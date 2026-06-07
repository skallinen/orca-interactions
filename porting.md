# Porting plan: Python → Clojure (full analysis)

Status: **planning**. This document is the agreed scope and sequence for moving
the remaining Python analysis (`bayesian_orca/*.py`, `scrape_orca_data.py`) into
the JVM/Clojure + CmdStan stack under `orca_clj/`, so that the Python tree can be
removed with **everything replicated**.

The user has chosen: **port everything** (including plots, frequentist tests, and
WAIC model comparison — see §6.3), then delete Python.

---

## 0. Guiding principles

- **scicloj + Java libs.** tablecloth/dtype-next for data, CmdStan (via
  `orca.stan`) for MCMC, `commons-suncalc` for solar, plus two new Java deps
  (Apache Commons Math for stats, XChart for PNGs). No Python at runtime.
- **REPL-driven.** Evaluate each namespace after every edit; format with the
  Standard Clojure Style (`npx @chrisoakman/standard-clojure-style fix`); lint
  with `clj-kondo`; thread-first/last where it reads well.
- **Faithful first, then noted.** Port what the Python code *does*. Where the
  code diverges from the current method as documented in the blog, port the code
  and record the discrepancy (see §6) rather than silently "fixing" the science.
- **Committed artifacts are the oracle.** `bayesian_orca/data/modeling_data.csv`,
  `metadata.json`, `blogpost/posterior_draws.json`, and `bayesian_orca/results/*.png`
  stay as reference. New Clojure outputs are validated against them; Python is
  never re-run.

---

## 1. Current state of the method (from the two blog posts)

`blogpost/methodology.html` and `index.html` are the source of truth. They settle
several "is this script still current?" questions:

| Topic | Current state (blog) | Implication for porting |
|---|---|---|
| **Primary model** | M3 **without** daylight, 30 params, relaxed priors (`α~N(-1,1)`, `β_depth,β_autopilot~N(0,1)`, others `N(0,0.5)`) — from `refit_no_daylight.py` | **Already ported** as `orca.model` + `stan/m3.stan`. ✅ |
| **Calculator export** | 500 draws × 30 params, no-daylight layout | **Already ported** (`orca.model/run`). `export_draws.py` (31-param, *with* `b_daylight`) is **deprecated**. |
| **Time of day** | Removed from the regression; handled by an exposure-based Poisson rate ratio = **0.56 [0.43, 0.72]** | **Already ported** as `orca.timeofday`. ✅ The encoding scripts are the *justification* for the removal. |
| **Model ladder M0–M4** | Built with the Fermi intercept prior `N(-3.5,0.6)`; M3 primary, M4 "no credible effects" | Port the ladder (`models.py`). |
| **Case-control / King–Zeng** | Applied at inference time in the calculator | Calculator is already ClojureScript in `index.html`; nothing to port. |
| **Calculator** | Scittle/ClojureScript in `index.html` | **Not Python**; out of scope. |

### Deprecated / superseded (do **not** port; record in commit + README)
- `export_draws.py` — superseded by `refit_no_daylight.py` (the no-daylight M3 is
  the calculator's model). The Clojure `orca.model` already reproduces the live one.

---

## 2. Dependency additions (`orca_clj/deps.edn`)

```clojure
org.apache.commons/commons-math3 {:mvn/version "3.6.1"}  ;; χ², Fisher (hypergeom), t-test, distributions
org.knowm.xchart/xchart          {:mvn/version "3.8.8"}  ;; headless PNG charts (pure Java/Java2D)
```

Both are plain Java libraries (allowed by the project goal). XChart writes PNGs
directly with `BitmapEncoder`; it covers histograms, bar charts, error-bar
"forest" plots, and box plots (our stand-in for matplotlib violins). Commons
Math provides `ChiSquareTest`, `TTest`, and `HypergeometricDistribution` (for an
exact Fisher 2×2 p-value).

---

## 3. New Stan models (`orca_clj/stan/`)

`stan/m3.stan` (no-daylight, relaxed priors) already exists. New ones, all with a
`generated quantities { vector[N] log_lik; }` block so WAIC can consume them:

| File | Model | Notes |
|---|---|---|
| `m0.stan` | Intercept only | `α~N(-3.5,0.6)`; emit `log_lik` |
| `m1.stan` | + length, rudder, antifoul, hull | index priors `N(0,0.5)` |
| `m2.stan` | + sailing, speed, autopilot | |
| `m3_build.stan` | M3 (no daylight), Fermi prior `N(-3.5,0.6)`, all slopes `N(0,0.5)` | the model-building M3 (ladder); reuses the no-daylight predictor set per methodology |
| `m4.stan` | **M3 + moon + tide + cloud cover** (33 params), Fermi prior | **methodology spec** (decision §6.2); builds on the no-daylight M3 |
| `m3_prior.stan` | M3-with-daylight, intercept prior `μ,σ` passed as **data** | drives the 4-prior sensitivity analysis (`sensitivity_analysis.py` keeps daylight in M3) |
| `daylight_only.stan` | `α + β_day·is_daytime` | `validate_daylight` single-predictor |
| `antifoul_only.stan` | `α + α_antifoul[k]` | `validate_antifoul` single-predictor |
| `m3_daylight.stan` | M3 **with** daylight (Fermi prior) | only for `test_night_encoding` (encoding A vs B, swap `is_daytime` vector) and as the historical model-building M3 variant |

Add `generated quantities { vector[N] log_lik; }` to **every** fittable model so
WAIC can consume the pointwise log-likelihood (see §4.3). Add it to the existing
`m3.stan` as well.

---

## 4. New / extended Clojure namespaces

### 4.1 Extend `orca.prepare` (prerequisite for the ladder + validations)
`prepare_data.py` emits a 24-column `modeling_data.csv`; the current Clojure prep
emits the reduced set the no-daylight M3 needs. Add the missing derived columns so
the full ladder, M4, and the validation scripts have their inputs **and** so we can
validate row-for-row against the full `modeling_data.csv`:
- `is_daytime` (harmonise `darkness_or_daylight`: contains "day" → 1 else 0)
- `cloud_cover_ord` (+ `_std`)
- `moon_illumination` (regex `(\d+)%`) (+ `_std`), `moon_waxing` (waxing=1/waning=0)
- `is_spring_tide` ("within 3 days" & not "not" → 1; "not" → 0; else nil)
- `is_towing` (harmonise `towing`/`trailing`)
- `month`, `year` (parse date from `date_of_interaction` | `date_passage_commenced`)
- Extend `orca.validate/validate-prep` to cover the new columns + `cloud_cover` category/std.

### 4.2 `orca.stats` — frequentist tests (Commons Math)
- `chi2-contingency` (2×2 → χ², dof, p) via `ChiSquareTest`
- `fisher-exact` (2×2 OR + two-sided p) via `HypergeometricDistribution`
- `t-test` (Welch/independent) via `TTest`
- small helpers: `crosstab`, `value-counts`, `odds-ratio`
- Unit-tested against known hand-computed 2×2 tables.

### 4.3 `orca.waic` — WAIC model comparison (decision §6.3)
- Read per-draw pointwise `log_lik[N]` from the CmdStan draws (emitted by each
  model's `generated quantities`; pooled by `orca.stan`).
- WAIC = `-2 (lppd - p_waic)` where `lppd_i = log mean_s exp(log_lik[s,i])` and
  `p_waic_i = var_s(log_lik[s,i])`; report `elpd_waic`, `p_waic`, `waic`, and `se`
  (`sqrt(N · var_i(elpd_i))`). No Pareto smoothing → simpler and lower-risk than PSIS.
- `compare` → table of `elpd_waic`, `Δelpd`, `se(Δ)` for a set of models (M3 vs M4).
- Validated on *ordering* + approximate magnitude vs the committed comparison.
  (WAIC and PSIS-LOO agree closely for well-behaved models, so the M3-vs-M4 ranking
  should reproduce.) Note in docstring that the Python used `az.compare` (PSIS-LOO);
  we use WAIC by design.

### 4.4 `orca.plot` — XChart PNG helpers
Thin wrappers returning/saving `*.png`:
- `histogram`, `forest` (error-bar/`barh` with 89% ETI), `box`/`violin-ish`,
  `trace` (line per chain), `compare` (LOO dot+error).
- Used by the ladder, results, sensitivity, and EDA namespaces. Output dir mirrors
  `bayesian_orca/results/` (configurable; default a Clojure `out/results/`).

### 4.5 `orca.models` — the M0–M4 ladder (`models.py`)
- `stan-data-*` builders per model (reuse `orca.model/stan-data` shape; add
  daylight for the M3-with-daylight build variant, and moon/tide/cloud-cover for M4).
- `fit` each via `orca.stan/sample`; `summary` (mean/sd/89% ETI/`r_hat`/`ess`) —
  `r_hat`/`ess` computed in Clojure (split-R̂, bulk/tail ESS) in `orca.util` or here.
- `prior-predictive-check` (sample priors → P(interaction) summary + histogram).
- `parameter-recovery` (simulate N=654 from known α/β, refit a 3-param Stan model,
  check 89% HDI coverage).
- `posterior-predictive-check` (replicate y, predicted-rate histogram vs observed).
- `risk-scenarios` (low/med/high scenarios → predicted P with 89% HDI).
- `compare` (calls `orca.waic` on M3 vs M4) + trace/coefficient/index-effect plots.
- `run-all` orchestration mirroring `models.py:main`.

### 4.6 `orca.results` — interpretation & plots (`run_results.py`)
- Re-fit M3_build + M4, print slope/category tables (mean, 89% ETI, OR, `***`),
  risk scenarios (the `run_results` variants with index choices),
  key contrasts (Black vs Coppercoat, Motoring vs Sailing, Night vs Day,
  Autopilot, Depth), and save `coefficient_forest.png`, `antifoul_effects.png`,
  `sailing_mode_effects.png`.

### 4.7 `orca.sensitivity` — prior sensitivity (`sensitivity_analysis.py`)
- Fit `m3_prior.stan` under 4 intercept priors: `N(-4.5,.6)`, `N(-3.5,.6)`,
  `N(-2.9,.6)`, `N(0,1.5)`.
- Intercept/implied-P table; slope comparison (robustness); Black-vs-Coppercoat &
  Motoring contrasts; `sensitivity_intercept.png`, `sensitivity_slopes.png`.

### 4.8 `orca.findings` — finding validations (`validate_antifoul` + `validate_daylight`)
- Raw value-counts from `incident_reports.csv`/`uneventful_reports.csv` (or the raw
  JSON via `orca.fetch` outputs).
- 2×2 contingency + χ² + Fisher (via `orca.stats`); confound t-tests across
  predictors; single-predictor Bayesian model (`antifoul_only.stan` /
  `daylight_only.stan`); contrasts and predicted P(interaction | night/day),
  risk ratio. Two entry points: `validate-antifoul`, `validate-daylight`.

### 4.9 `orca.encoding` — time/night encoding studies
- `explore_time_encoding` (4 encodings A–D, interaction-rate comparison; console).
- `test_night_encoding` (encodings A vs B, refit M3_build under each, compare
  `beta_daylight`; reuses `night_m3` data swap).
- `solar_encoding` (solar period via `commons-suncalc` — reuse `orca.timeofday`
  helpers; night-fraction per passage; solar-vs-reported agreement). Console + any
  small tables. (Mostly exploratory; no committed PNGs.)

### 4.10 `orca.dag` — causal DAG (`causal_dag.py`)
- Pure documentation: emit the Mermaid diagram + adjustment-set notes to console
  (and optionally a `.md`). Trivial port.

### 4.11 `orca.eda` — distribution comparison (`distribution_comparison.py`)
- Stratified frequency tables + interaction rates by category; save
  `distribution_ordinal.png`, `distribution_categorical.png`,
  `distribution_binary.png` via `orca.plot`.

### 4.12 `orca.core` — extend the runner
- Add the new analyses to `run-all` (or a `run-everything`) so the whole pipeline
  is one entry point, mirroring the Python script order in methodology §10.

---

## 5. Inventory & mapping (Python → Clojure, with status)

| Python | Clojure target | Status |
|---|---|---|
| `scrape_orca_data.py` | `orca.fetch` | ✅ done |
| `prepare_data.py` | `orca.prepare` (+extend §4.1) | ⚠️ extend |
| `refit_no_daylight.py` | `orca.model` + `stan/m3.stan` | ✅ done |
| `export_draws.py` | — | 🚫 deprecated (skip) |
| `models.py` (M0–M4, PPC, recovery, model comparison) | `orca.models` + `orca.waic` + `orca.plot` + new Stan | ⬜ port |
| `run_results.py` | `orca.results` | ⬜ port |
| `sensitivity_analysis.py` | `orca.sensitivity` | ⬜ port |
| `validate_antifoul.py` | `orca.findings` + `orca.stats` | ⬜ port |
| `validate_daylight.py` | `orca.findings` + `orca.stats` | ⬜ port |
| `test_night_encoding.py` | `orca.encoding` | ⬜ port |
| `explore_time_encoding.py` | `orca.encoding` | ⬜ port |
| `solar_encoding.py` | `orca.encoding` (reuse `orca.timeofday`) | ⬜ port |
| `causal_dag.py` | `orca.dag` | ⬜ port |
| `distribution_comparison.py` | `orca.eda` + `orca.plot` | ⬜ port |

---

## 6. Discrepancies & decisions

1. **M3 daylight.** `models.py` M3 *includes* `beta_daylight` (Fermi prior); the
   methodology's primary M3 and the calculator's M3 *exclude* it. **Decision:** keep
   the canonical no-daylight M3 as `orca.model`/`stan/m3.stan`; port the
   model-building M3 (with daylight) as `m3_build.stan` used only inside
   `orca.models`/`orca.results`. Document both.

2. **M4 contents.** methodology M4 = "+ moon, tide, cloud cover" (33 params);
   `models.py` M4 = "+ moon, tide, towing, month sin/cos". **DECIDED: match the
   methodology text** — Clojure M4 = M3 (no daylight) + moon + tide + cloud cover,
   33 params. The `models.py` M4 (towing/month) is treated as superseded; the
   docstring/README will note the committed `trace_M4_full.png`/`ppc_M4.png` came
   from that older variant.

3. **Model comparison: WAIC, not PSIS-LOO.** **DECIDED: use WAIC** (`orca.waic`)
   instead of reimplementing ArviZ's PSIS-LOO. Simpler, lower-risk; validated on
   M3-vs-M4 ordering + approximate magnitude. Documented as an intentional
   methodological substitution (Python used `az.compare`/PSIS-LOO).

4. **Plots.** matplotlib violins → XChart box plots (no native violin). Layout/
   styling won't be pixel-identical to the committed PNGs; the *data* shown will
   match. **Decision:** match content, not pixels.

5. **`r_hat`/`ess`.** PyMC/ArviZ report split-R̂ and bulk/tail ESS. We'll implement
   these in Clojure; values should match to ~2 sig figs.

---

## 7. Validation strategy

- **Data prep:** extend `orca.validate/validate-prep` to the full 24-column
  `modeling_data.csv` + `metadata.json` (categories, standardization incl.
  cloud cover & moon).
- **Models:** posterior means/SDs of M3 vs the committed reference within existing
  tolerances (`:validate`); M0–M4 fit cleanly (R̂<1.01, ESS>400, 0 divergences).
- **WAIC:** M3 vs M4 ordering matches; `elpd_waic` within documented tolerance.
- **Findings:** χ²/Fisher p-values and contrasts match `validate_*` console numbers;
  single-predictor posteriors within tolerance.
- **Stats unit tests:** χ²/Fisher/t-test against hand-computed values.
- **Smoke:** every analysis namespace loads, formats clean, lints clean, and its
  pure functions are unit-tested; heavy MCMC paths run at least once end-to-end.

---

## 8. Phased sequence (task list)

**How phases are run.** This port executes under a standing **goal**, not an
interactive session. The **main context orchestrates** (plan, dispatch, review,
record progress); **each phase below is run by a fresh sequential subagent** with
its own context window, so phase-local memory never crowds the orchestrator
(replaces the older "clear context after each phase" habit). See
`orca_clj/AGENTS.md` for the execution model.

**Review after every phase.** When a phase's code is written, formatted, linted
clean and green, run the **code-review panel** before moving on: launch separate
subagent reviewers that each LARP one voice — **Rich Hickey** (simplicity /
decomplecting), **Alex Miller** (idiomatic Clojure & tooling), **Zachary Tellman**
(naming, composition, robustness), **Daniel Slutsky** (scicloj / data-science
idioms, numerics), **Richard McElreath** (Bayesian/statistical correctness). Act
on the findings (record skips with a reason), re-lint, re-test; *then* the phase
is done. Roster detail lives in `AGENTS.md`.

**Phase A — foundations (no MCMC):**
1. Add deps (Commons Math, XChart); confirm resolve + load.
2. `orca.stats` + unit tests.
3. `orca.plot` (smoke: write one PNG of each kind).
4. Extend `orca.prepare` (+ derived columns) and `validate-prep`; revalidate
   row-for-row vs `modeling_data.csv`.
5. `orca.util` (or `orca.diagnostics`): split-R̂, bulk/tail ESS, HDI/ETI helpers.

**Phase B — model ladder & comparison:**
6. Stan: `m0/m1/m2/m3_build/m4` (M4 = +moon/tide/cloud cover) (+`log_lik`); compile each once.
7. `orca.models`: builders, fit, summaries, prior-predictive, recovery, PPC,
   risk scenarios; trace/coef/index plots.
8. `orca.waic`: WAIC + `compare`; validate M3 vs M4 ordering.

**Phase C — interpretation & robustness:**
9. `orca.results` (contrasts + 3 PNGs).
10. `orca.sensitivity` (`m3_prior.stan` × 4 priors + 2 PNGs).

**Phase D — findings & encodings:**
11. `daylight_only.stan`, `antifoul_only.stan`; `orca.findings`.
12. `orca.encoding` (explore + night-test + solar).
13. `orca.dag`, `orca.eda`.

**Phase E — wire-up, removal & blog alignment:**
14. Extend `orca.core/run-all` (wire in `orca.results`, `orca.sensitivity`, and
    the Phase-D namespaces); update `orca_clj/README.md` (new namespaces,
    deprecations, discrepancies).
15. Full end-to-end run; confirm all validations pass.
16. `git rm` the Python tree (`bayesian_orca/*.py`, `scrape_orca_data.py`,
    `bayesian_env/`, `__pycache__`); keep committed data/result artifacts and the
    blog. Commit in semantic chunks.
17. **Scrub Python references** now that Python is gone: docstrings/comments that
    say "port of `bayesian_orca/*.py`" become descriptive (what the code does, not
    what it mirrored); remove Python mentions from `config.edn` comments,
    `README.md`, `AGENTS.md`, and any other file. The Clojure system should read as
    the implementation, not a translation. (Keep the §6 decision log's historical
    notes — those explain *why* the method is what it is.)
18. **Update the methodology blog to follow the Clojure implementation:** revise
    `blogpost/methodology.html` (and `index.html` where it names tooling) so the
    described stack and method match what `orca_clj` actually does — CmdStan/NUTS
    (not PyMC), Clojure diagnostics (split-R̂/ESS), **WAIC** model comparison (not
    PSIS-LOO), `commons-suncalc` (not `astral`), XChart figures, and the
    no-daylight M3 + separate time-of-day rate model. **Do not regenerate the
    committed numeric oracle** (`posterior_draws.json`) or the validated figures —
    the numbers stand; only the prose/method narrative tracks the new
    implementation.
19. **Double-check pass:** re-read the implementation against both blog posts and
    flag any remaining errors or important gaps — claims in the blog that the code
    doesn't support (or vice-versa), un-ported analyses, missing validations,
    sign/scale mismatches vs the oracle. Fix or record each. This is the gate
    before the final review.

**Phase F — whole-system review (final pass):**
20. Run the **same code-review panel** (Hickey / Miller / Tellman / Slutsky /
    McElreath) **over the entire `orca_clj` system** — architecture,
    cross-namespace consistency, naming/simplicity at the system level, and the
    science end-to-end — not just one phase's diff. Address the findings; land a
    final clean `clojure -X:test` + lint + format and a full end-to-end run.

---

## 9. Risks / watch-items

- **WAIC** is the trickiest new numeric code, but far simpler than PSIS-LOO
  (no Pareto tail fit). Emit `log_lik` from Stan and cross-check pointwise.
- **MCMC time.** Many models × 4 chains × 3000 iters → minutes each, plus PCH
  compiles. The full run is long; structure so namespaces are independently runnable.
- **Diagnostics parity.** split-R̂/ESS must match ArviZ conventions to be trusted.
- **Raw CSV inputs.** `validate_*`/`encoding` read `incident_reports.csv` /
  `uneventful_reports.csv`; ensure these exist (committed in `orca_data/`, or
  regenerate via `orca.fetch`).
- **bayesian_env/** is a committed virtualenv (large); removing it is pure cleanup
  but verify nothing references it.

---

## 10. Out of scope
- The in-browser calculator (already ClojureScript in `index.html`).
- Re-running Python or regenerating the committed oracle artifacts.
- Pixel-identical reproduction of matplotlib figures (content parity only).

---

## 11. Deferred / known gaps (Phase E double-check, item 19)

Surfaced while re-reading the implementation against both blog posts. Recorded
here for the Phase F whole-system review.

1. **Time-of-day × risk-factor interaction (4-rate Poisson model) — NOT ported.**
   Both blog posts (methodology §7 "Time-of-Day Interactions"; index.html
   "do the other risk factors behave differently at night?") describe an
   *extension* of the exposure Poisson model that stratifies incident rate per
   yacht-hour by time-of-day × {antifoul, sailing mode} — e.g. "Black antifoul
   2.1× day / 2.4× night, interaction ratio 1.2× [0.7, 1.8]"; "Motoring 4.1× day
   / 5.5× night, ratio 1.4× [0.8, 2.2]". `orca.timeofday` implements only the
   single overall night/day rate ratio (0.56 [0.43, 0.72]), not this stratified
   4-rate interaction model. This analysis was never enumerated in §4 of this
   plan, so it was out of the ported scope; the headline rate-ratio claim *is*
   reproduced, and the blog uses the interaction result only to justify applying
   that single ratio as a uniform multiplier (the interactions are negligible).
   **Deferred:** add an `orca.timeofday` stratified-exposure rate model
   (time-of-day × antifoul/sailing) if/when this section needs reproducing.

2. **Historical night-coefficient magnitude (+2.06, OR 7.8×) is not reproduced
   numerically — by design.** The blog frames "+2.06 / 7.8×" as the *original*
   with-daylight finding that was then *removed*. The Clojure with-daylight study
   (`orca.encoding`/`m3_daylight.stan`) reproduces the *direction-flip* story
   (β_daylight ≈ −1.09 under encoding A vs +1.57 under B), but not that exact
   magnitude — m3_build uses the tighter ladder priors + full controls, so the
   coefficient is shrunk. The committed oracle is not regenerated, and the blog
   number is historical narrative, so this is expected, not a defect.

3. **Autopilot sign nuance (pre-existing).** The fitted model's `b_autopilot` is
   genuinely negative (autopilot-on lowers the odds), which matches the blog's
   "autopilot off is the strongest predictor" framing once the sign convention is
   read correctly. Noted in the Phase C progress memory; not a port artifact.

4. **Absolute night/day incident rates differ slightly (ratio preserved).** The
   Clojure exposure model gives ~13.6 incidents per 1,000 yacht-hours at night
   and ~23.7 by day (index.html quotes 11.8 and 20.9). The *ratio* — the
   load-bearing claim — is 0.57, matching the published 0.56 [0.43, 0.72]; the
   night-hour fraction (34.5% vs "35%") and incident-night fraction (23.1% vs
   "23%") also match. The absolute per-1,000-hour rates differ because the
   exposure-hour totals depend on the sampling interval / max-passage filter; the
   committed oracle is not regenerated, so this minor offset is left as-is.
   Verified counts: y_night=50, y_day=166, T_night≈3689, T_day≈6999 yacht-hours.

**Cross-checks that PASSED (item 19).** Encoding A/B data table reproduces the
blog exactly (A: night n=76, 11 uneventful, 85.5% / day 26.1%; B: night 21.1% /
day 36.3%); M3 complete-case N=605; antifoul Black 44.5% vs Coppercoat 17.0%,
Fisher OR≈3.9, single-predictor Black−Coppercoat +1.16 [0.72,1.60]; WAIC ranks
M3 over M4 (Δ within 1 se), matching "M4 no credible effects"; the four-prior
sensitivity sweep keeps slopes stable while the intercept swings. Blog stack/
method prose (CmdStan/NUTS, Clojure diagnostics, WAIC, commons-suncalc) now
matches the implementation.
