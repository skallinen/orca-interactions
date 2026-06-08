# orca_clj: JVM/Clojure Bayesian orca analysis

A pure-JVM Bayesian analysis of orca-vessel interactions:

- **Data acquisition:** JDK `java.net.http` client over the CA orca-survey API (`orca.fetch`)
- **Data prep:** [tablecloth](https://github.com/scicloj/tablecloth) (`orca.prepare`)
- **MCMC:** [CmdStan](https://mc-stan.org/) NUTS, driven from Clojure (`orca.stan`)
- **Diagnostics:** rank-normalized split-R̂, bulk/tail ESS, ETI/HDI (`orca.diagnostics`)
- **Model comparison:** WAIC (`orca.waic`)
- **Solar/time-of-day:** `commons-suncalc` (Java) for solar position (`orca.timeofday`)
- **Stats / plots:** Apache Commons Math (`orca.stats`), XChart PNGs (`orca.plot`)

A set of committed reference artifacts is the validation oracle:

| Clojure output | Validated against |
|----------------|-------------------|
| `orca.prepare` modeling data + metadata | `bayesian_orca/data/modeling_data.csv`, `metadata.json` |
| `orca.model` posterior draws            | `blogpost/posterior_draws.json` |
| `orca.timeofday` rate ratio             | methodology.html (0.56, 89% CI 0.43 to 0.72) |

## Setup

Everything runs inside the repo's nix dev shell (`nix develop` / `nix-shell`),
which provides clojure, cmdstan, clang, gnumake, babashka.

CmdStan must be built against a **writable** copy of the store tree (the store
is read-only and its prebuilt precompiled header is compiler-mismatched on
Darwin). One-time:

```bash
SRC=$(dirname $(dirname $(readlink -f $(nix-shell -p cmdstan --run 'which stan'))))/opt/cmdstan
DST=$HOME/.cache/orca-cmdstan
cp -R "$SRC" "$DST" && chmod -R u+w "$DST"
rm -rf "$DST"/stan/src/stan/model/model_header.hpp.gch   # rebuild PCH with active clang
export CMDSTAN="$DST"
```

The first model compile rebuilds the PCH (~3-5 min); later compiles are fast.

## REPL-driven workflow

```bash
# start a socket REPL inside the tooling shell (keeps stdin open so it persists)
nix-shell -p clojure jdk21 cmdstan clang gnumake babashka \
  --run 'tail -f /dev/null | clojure -M:repl' &

# send forms to it
echo "(require 'orca.prepare :reload)" | bb re.clj
```

## Data acquisition (`orca.fetch`)

`orca.fetch` is an API client, not a scraper: it pulls the report list, then
fetches every detailed incident / uneventful-passage report concurrently (bounded thread pool, with
retries), flattens each `{:Q :A}` response, and writes `reportlist.json`,
`all_reports_detailed.json`, and the per-type CSVs into `:api :out-dir`
(`../orca_data`). The committed `orca_data/` snapshot is the analysis input, so
a re-fetch is only needed to refresh from the live (slow) API:

```clojure
(require 'orca.fetch)
(orca.fetch/fetch-reports)          ; ~5-10 min against the live CA API
```

Endpoints, headers, worker count, retries and CSV column orderings live under
`:api` in `resources/config.edn`.

## Analysis namespaces

| Namespace | What it does |
|-----------|--------------|
| `orca.prepare`    | Build the modeling-ready dataset + metadata from the raw reports |
| `orca.model`      | Final calculator refit: relaxed-prior no-daylight M3, 500-draw export |
| `orca.models`     | The M0-M4 ladder: builders, fits, summaries, prior-predictive, parameter recovery, PPC, risk scenarios |
| `orca.waic`       | WAIC + model comparison from pointwise `log_lik` |
| `orca.results`    | Interpretation: slope/category tables, contrasts, risk scenarios, coefficient/effect plots |
| `orca.sensitivity`| Prior sensitivity sweep over four intercept priors |
| `orca.findings`   | Validations of the two headline findings (black antifoul, night/day) |
| `orca.encoding`   | Time-of-day encoding studies (the justification for excluding time of day) |
| `orca.timeofday`  | Exposure-based night/day Poisson rate ratio (0.56 [0.43, 0.72]) + 4-rate time-of-day interaction tests |
| `orca.eda`        | Stratified distribution comparison + figures |
| `orca.dag`        | Causal DAG, adjustment sets, and caveats |
| `orca.diagnostics`| Rank-normalized split-R̂, bulk/tail ESS, ETI/HDI |
| `orca.stats`      | χ², Fisher's exact, two-sample t-test |
| `orca.plot`       | Headless XChart PNG helpers |
| `orca.validate`   | Checks the Clojure outputs against the committed reference artifacts |
| `orca.core`       | `run-all`, the whole pipeline end to end |

### Method notes

- **Primary model M3** excludes any time-of-day predictor. Time of day is
  handled separately as an exposure-based Poisson rate ratio (`orca.timeofday`),
  because the reports record incident times as a single period while uneventful
  passages record every period covered, so no binary day/night encoding is well
  posed (`orca.encoding` shows the sensitivity).
- **Time-of-day × risk-factor interactions** are tested in `orca.timeofday`
  (`interaction-report`) with a 4-rate stratified Poisson model
  (`stan/rate4.stan`): incident rate per yacht-hour in four cells, factor
  present/absent crossed with day/night. Black antifoul 2.1× day / 2.3× night
  (interaction 1.1×, 89% CI 0.65 to 1.83) and Motoring 4.1× / 5.5× (interaction
  1.4×, 89% CI 0.75 to 2.22). Both interaction CIs span 1.0, so the effects are
  stable across time of day, which licenses applying the single 0.56 night/day
  ratio as a uniform multiplier.
- **M4** = M3 + moon + tide + cloud cover (33 params).
- **Model comparison is WAIC** (`orca.waic`), validated on the M3-vs-M4
  ordering. M3 is preferred; M4 adds no credible effects.
- **Convergence gate:** R̂ < 1.01, ESS > 400, 0 divergences.

## Route planner (`route-planner/`)

A standalone ClojureScript web app that turns the model into an interactive
transit-risk map for the Iberian orca zone. It lives in `route-planner/`
(`index.html` shell, `src/planner_core.cljs` pure math, `src/planner_app.cljs`
Leaflet/Reagent UI) and is served as static files; see `route-planner/README.md`
for how to open and use it.

This project produces its data artifacts and gates it:

| Artifact | What it is |
|----------|------------|
| `route-planner/posterior_planner.json` | The `presence-effort-seasonal` model: 500 draws of an `attr` block (relative vessel effects) and a `spatial` block (occupancy field + seasonal drift). Schema in `route-planner/data/POSTERIOR_SCHEMA.md` |
| `route-planner/geo_grid.json`          | Depth/distance ordinals for 34,933 sea cells over 25-50°N, 20°W-5°E at 0.1° |
| `route-planner/data/*`                 | Data-prep artifacts: `harbor_coords.edn`, `planner_dataset.edn`, `effort_grid.json`, `background_sample.edn` |

| Source | What it does |
|--------|--------------|
| `src/orca/planner_fit.clj` (`orca.planner-fit`) | Fits both model parts, calibrates, exports `posterior_planner.json` (`clojure -X:planner-fit`) |
| `stan/attr_logit.stan`                           | Part A: attribute logistic (incident vs uneventful) |
| `stan/spatial.stan`                              | Part B: occupancy field logistic (`tau ~ normal(0,0.4)`) |
| `scripts/gen_geo_grid.clj`                       | Babashka geo-grid generator, writes `geo_grid.json` |
| `scripts/prepare_planner_data.clj`              | Babashka: extract the prepared dataset + day-of-year |
| `scripts/gen_effort_surface.clj`               | Babashka: harbor-track effort surface + effort-weighted background |
| `scripts/geocode_harbors.clj`                  | Babashka: cache-aware Claude-API harbor geocoder |

### Modelling decision

Risk is a Poisson hazard `h0 * RR(loc, day-of-year) * attr_mult(vessel)`, fit as
two logistic models combined at runtime:

- **Part A** (`attr_logit.stan`): incident vs uneventful reports on their shared
  attributes (depth, distance, sailing mode, antifoul, hull, rudder, speed,
  length, wind, sea) -> relative vessel effects. Identified for both classes
  because it drops the spatial term (autopilot is excluded; incidents lack it).
- **Part B** (`spatial.stan`): incident locations vs an **effort-weighted**
  background (harbor-to-harbor sailing tracks, not uniform sea cells) -> a bounded
  RBF occupancy field whose centre drifts north/south with day-of-year
  (ecology-fixed sinusoid, peak-north in late summer). Per-draw normalization
  makes `RR` mean ~1 over sailed waters.

This replaces the earlier presence/**background** smoother, whose uniform
pseudo-absences produced a ~13-logit hotspot and a runaway 100% risk for any
passage near Gibraltar. The rebuilt model reads ~20% (with a real 89% CI) for a
W-Portugal -> Gibraltar passage, < 1% for an open-Atlantic hop, and its hotspot
moves with the season.

### Headless gates

Two JVM-Playwright runners (under `test/`, served via the JDK SimpleFileServer)
gate the app:

```bash
clojure -X:planner-smoke   # core math: runs route-planner/test/core_test.html
clojure -X:app-smoke       # full app suite + sanity/season checks on index.html
```

`:planner-smoke` asserts the pure-core math (Poisson round-trip, CI ordering,
monotonicity, spatial hotspot > open ocean, bounded field, seasonal drift).
`:app-smoke` loads the real app in headless Chromium and drives it through the
`window.__planner` hooks (data load, heatmap re-tint, waypoint/segment/route
risk, parity vs calculator).

### Known intentional gaps (not regenerated)

A blog reader should not assume every number in the two posts is recomputed here.
These are deliberate, documented boundaries:

- **Historical night coefficient (+2.06, OR ≈ 7.8×) is not reproduced
  numerically, by design.** That is the *original* with-daylight finding that
  was then removed. `orca.encoding` / `stan/m3_daylight.stan` reproduces the
  qualitative *direction-flip* story (β_daylight ≈ −1.09 under encoding A vs
  +1.57 under B), but the tighter ladder priors + full controls shrink the
  magnitude. The committed oracle is not regenerated; the +2.06 figure is
  historical narrative.
- **Absolute night/day exposure totals.** The exposure integration uses a
  200 h (~8-day) max-passage cut (`config.edn :max-passage-hours`) that drops
  multi-month data-entry "passages". This reconciles the totals with the
  published model (night 4,270 / day 7,944 yacht-hours; 11.7 / 20.9 per 1,000 h),
  and the load-bearing rate ratio (0.56) is preserved either way.
- **Risk scenarios are relative, not absolute configs.** `orca.models/risk-scenarios`
  marginalizes the categorical offsets (antifoul/sailing/hull/rudder) at their
  prior-centered mean of 0, so the printed `P=x%` is an average-category vessel.
  Per the blog, treat these as **ordinal** comparisons across scenarios; named
  absolute risk for a concrete configuration is the in-browser calculator's job
  (`index.html`, out of scope, already ClojureScript).
