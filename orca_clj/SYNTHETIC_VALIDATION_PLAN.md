# Synthetic-Data + Parameter-Recovery Validation Harness

Status: PLAN (no code written yet beyond this file).
Scope: validate the existing Bayesian orca-vessel risk model (`orca_clj/`,
CmdStan + JVM Clojure) by the *simulate -> recover* workflow before trusting
real-data fits. Read-only against existing code: this harness adds NEW
namespaces and at most one NEW `.stan` file; it does not edit `spatial.stan`,
`attr_logit.stan`, `planner_fit.clj`, or the runtime.

---

## 1. Why this exists (the user's actual problem)

The route-planner heatmap is driven by Part B (`stan/spatial.stan`): a
use-availability logistic point process with an 84-center RBF field PLUS a
continuous depth quadratic (`b_d1*z + b_d2*z2`, fitted ~ +1.32 / -1.61, a
peaked shelf/slope preference). The open question:

> **Is the depth/distance signal real, or is it being absorbed (or invented) by
> the flexible spatial RBF field — and does the heatmap therefore mislead?**

A smooth RBF smoother with M=84 centers and lengthscale 150 km can soak up a
smooth depth effect. We cannot diagnose that from the real fit alone, because
we never observe the truth. The only rigorous answer is **parameter recovery**:
generate data from a KNOWN intensity surface (known RBF weights, known depth
coefficients), fit the SAME model, and check whether the fit recovers depth or
lets the field steal it. McElreath's "draw the owl" step 4: confirm recovery on
synthetic data before believing the real posterior.

This harness delivers: (A) a Part-A attribute-logit recovery check, (B) a
Part-B point-process recovery check with an explicit **depth-vs-field
confounding experiment** (the crux), (C) King-Zeng intercept correction so a
correct use-availability fit is not falsely flagged biased, (D) prior-predictive
sanity, and (E) optional SBC for full calibration.

---

## 2. What already exists (reuse, do not reinvent)

Verified by reading the files:

- `orca.stan` — `compile-model`, `sample-chains` (returns a VECTOR of per-chain
  tablecloth datasets, the layout R-hat/ESS need), `sample` (pooled). Writes
  `out-dir/data.json` and per-chain `draws_<c>.csv`. Uses `$CMDSTAN`
  (default `~/.cache/orca-cmdstan`). **Reuse for all fitting.**
- `orca.diagnostics` — `rhat` (rank-normalized folded split-R-hat), `ess-bulk`,
  `ess-tail`, `eti` (default 0.89), `hdi`, `summarize`. Inputs are seqs of
  per-chain numeric seqs (exactly what `sample-chains` columns give). **Reuse
  for all scoring; do not hand-roll diagnostics.**
- `orca.util` — `mean`, `pstdev` (population sd, ddof=0), `quantile`
  (numpy-linear), `sigmoid`, `write-json`, `read-json`. **Reuse.**
- `orca.planner-fit` (the model-under-test's design builder) exposes the REAL
  geometry we must reuse so synthetic data lives on the real grid:
  - `haversine-km`, `drifted`, `basis-at`, `raw-basis-row` — RBF basis.
  - `candidate-centers`, `prune-centers` — produce the real 84 centers.
  - `load-bathy`, `sample-depth`, `logdepth` — real bathymetry -> log-depth.
  - `build-spatial-design` — returns
    `{:y :Bsp :z :z2 :col-means :logdepth-mean :logdepth-sd :z-bg-mean
      :z2-bg-mean :N :M :centers :ell :presences :background}`.
  - `incident-points`, `background-points` — real presence/effort points.
  - Constants: `lengthscale-km` 150.0, `center-spacing` 2.0,
    `center-keep-deg` 2.0, `fit-seed` 42, `drift` {:a-lat 3.5 :phi 149.0 ...}.
- Stan data blocks (verified):
  - `attr_logit.stan`: `data {N; K; matrix[N,K] X; y}` ; params `alpha`,
    `vector[K] beta`; priors `alpha~N(0,5)`, `beta~N(0,1)`.
    (NB: the methodology spec calls for N(0,0.5) slopes; the *checked-in* file
    uses N(0,1). The harness fits the file as-is and scores against it. If we
    want N(0,0.5) recovery, add a sibling `.stan` — see Section 6 note.)
  - `spatial.stan`: `data {N; M; y; matrix[N,M] Bsp; vector[N] z; vector[N] z2}`
    ; params `b0`, `vector[M] w`, `tau>0`, `b_d1`, `b_d2`; priors `b0~N(0,5)`,
    `tau~N(0,0.4)T[0,]`, `w~N(0,tau)`, `b_d1,b_d2~N(0,0.5)`.
- Test runner: `clojure -X:test` (cognitect). Tests live in
  `orca_clj/test/orca/*_test.clj`, ns `orca.<x>-test`, pure functions only
  (no CmdStan). Convention example: `orca.util-test` with a local
  `close?` epsilon helper.
- RNG convention: `(java.util.Random. 42)` with `.nextGaussian` /
  `.nextDouble`, exactly as `orca.models/parameter-recovery` and
  `orca.models/prior-predictive` already do. Reuse this so generators are
  deterministic given a seed.

**`.stan` formatter rule:** NEVER run `standard-clojure-style` (or any Clojure
formatter) on `.stan` files — it mangles them. New `.stan` files are authored
by hand and compiled via `orca.stan/compile-model`.

---

## 3. New namespaces (each sized for one subagent's context)

All under `orca_clj/src/orca/`. One responsibility each; signatures + one-line
contracts below.

### 3.1 `orca.synth` — Part-A synthetic attribute generator (PURE)

Generates incident/uneventful data from KNOWN intercept + slopes with settable
predictor distributions, for `attr_logit.stan` recovery.

```
(ns orca.synth)

(defn logit ^double [^double p])
;; -> log(p/(1-p)); intercept from base rate: alpha = (logit base-rate).

(defn make-rng [seed])
;; -> java.util.Random. Single source of all randomness (determinism).

(defn draw-continuous [rng spec n])
;; spec {:dist :normal :mean 0 :sd 1} -> vector of n standardized draws.
;; OPTIONAL {:dist :mvnormal :mean [..] :cov [[...]]} for correlated predictors
;; (Cholesky of a fixed correlation matrix; document the matrix in the call).

(defn draw-categorical [rng probs n])
;; probs e.g. [0.6 0.3 0.1]; returns n index codes 0..K-1 (Categorical(p)).
;; Index coding matches attr_logit's continuous X columns (one column/index).

(defn simulate-attr
  [{:keys [base-rate predictors true-slopes seed n]}])
;; predictors: ordered vector of column specs (continuous/categorical).
;; true-slopes: vector aligned to the EXPANDED design columns.
;; Returns {:N :K :X :y :truth {"alpha" .. "beta.1" .. ...} :seed}.
;; y_i ~ Bernoulli(sigmoid(alpha + sum_k beta_k x_ik)). Deterministic given seed.

(defn design-matrix [predictors raw-draws])
;; Expand specs+raw draws into the N x K matrix attr_logit expects
;; (continuous as-is; categorical index value placed in its single column,
;;  matching how planner-fit standardizes ordinals into single columns).
```

Contract notes: `base-rate` 0.03 -> `alpha = logit(0.03) = -3.476`. The
EXPOSED knobs are `base-rate`, per-predictor distribution, and `true-slopes`
("the user knows the result"). No CmdStan dependency here.

### 3.2 `orca.simtracks` — Part-B point-process / track simulator (PURE)

The heart of the depth-vs-field question. Builds a KNOWN intensity surface on
the REAL grid (real centers, real bathy), simulates route corridors with
settable mixing, and emits presence/background design rows compatible with
`spatial.stan`.

```
(ns orca.simtracks
  (:require [orca.planner-fit :as pf] [orca.util :as util]))

(defn real-centers [])
;; Reuse pf/candidate-centers + pf/prune-centers on
;; pf/incident-points + pf/background-points -> the SAME 84 centers as the fit.

(defn known-weights [centers {:keys [seed tau-true]}])
;; Draw KNOWN RBF weights w_true ~ Normal(0, tau-true) (tau-true ~ 0.4),
;; OR accept an explicit vector. Returns {:w w-true :tau tau-true}.

(defn log-lambda
  [{:keys [centers w-true b0-true b1-true b2-true ell bathy
           logdepth-mean logdepth-sd]} lat lon doy])
;; log lambda(s) = b0 + (basis(s) . w-true) + b1*z(s) + b2*z(s)^2 ,
;; z(s) standardized log-depth from REAL bathy (pf/sample-depth, pf/logdepth).
;; This is the GROUND TRUTH surface; mirrors spatial.stan's linear predictor.

(defn route-corridors [config])
;; Vector of corridors, each {:waypoints [[lat lon]...] :width-km}.
;; Mix weights pi_r are SETTABLE (controls availability/route-choice dist).

(defn sample-background
  [rng {:keys [corridors pi seasonal-drift n-bg]}])
;; Background ~ effort along the mixed corridors (drift via pf/drifted).
;; -> [[lat lon doy] ...]. Can also be set to MIMIC real
;; pf/background-points (pass them through) per the "mimic real" requirement.

(defn sample-presences
  [rng {:keys [surface background n-pres]}])
;; Thinning: draw incident points with prob ∝ lambda(s)/effort(s) over
;; candidate sites (rejection on exp(log-lambda)). -> [[lat lon doy]...].

(defn build-sim-design
  [{:keys [presences background centers ell bathy ...]}])
;; Reproduce pf/build-spatial-design's column-centering + depth z,z2
;; standardization on the SIMULATED points. Returns the SAME map shape
;; pf/fit-spatial-chains consumes: {:N :M :y :Bsp :z :z2 ...}
;; PLUS :truth {"b0" .. "b_d1" .. "b_d2" .. :w-true :z* :lambda-true-cells}.

(defn separable-config [knobs])
;; THE CRUX helper. Returns a sim config where DEPTH varies at a SHARPER
;; spatial scale than ell=150km (e.g. uses raw bathy gradients) and is
;; decorrelated from the smooth basis, so depth and field are recoverable
;; SEPARATELY. Document the decorrelation method inline.
```

Contract: every randomness path flows through one seeded `java.util.Random`.
`build-sim-design` MUST stay schema-compatible with `pf/fit-spatial-chains`
so the EXISTING `spatial.stan` is fit unchanged. Store `lambda-true` per
geo-grid cell for surface scoring.

### 3.3 `orca.recover` — fit + score harness (IMPURE: drives CmdStan)

Ties generators to `orca.stan` + `orca.diagnostics`, applies thresholds and the
King-Zeng correction, returns pass/fail rows. This is the Tier-I entry point.

```
(ns orca.recover
  (:require [orca.stan :as stan] [orca.diagnostics :as diag]
            [orca.util :as util] [orca.synth :as synth]
            [orca.simtracks :as sim] [orca.planner-fit :as pf]))

;; thresholds — code-ready constants (Section 5)
(def rhat-max 1.01)
(def ess-min 400.0)
(def bias-z-max 2.0)
(def ci-prob 0.90)
(def surface-corr-min 0.90)

(defn mcmc-gate [chains-by-param])
;; -> {:rhat-ok :ess-ok :pass} ; pass iff all rhat<1.01, ess-bulk/tail>400.
;; (Divergence count must be parsed from CmdStan CSV # comments -> :n-divergent;
;;  read raw lines via slurp, the sampler output header carries it.)

(defn coverage [chains true-val])
;; -> {:mean :sd :ci [lo hi] :covered (in central 90%) :z ((mean-true)/sd)
;;     :bias-ok (|z|<2)}. CI via diag/eti at ci-prob.

(defn kingzeng-offset [b0-hat tau ybar])
;; expected use-availability intercept shift:
;;   b0_corrected = b0_hat - ln( ((1-tau)/tau) * (ybar/(1-ybar)) ).
;; Apply to the intercept BEFORE coverage so a correct fit isn't flagged.
;; tau here = presence fraction of the sample (n_pres/N), ybar = same.

(defn score-attr [sim-result chains])
;; Per-parameter coverage + bias + mcmc-gate against synth truth.

(defn score-spatial [sim-result chains])
;; Per-param coverage; intercept via kingzeng-offset; surface Pearson
;; corr(lambda-hat, lambda-true) >= 0.90; depth peak z* = -b1/(2 b2) brackets
;; true z* (report in METRES via inverse of logdepth standardization);
;; b2 posterior 90% upper bound < 0 (excludes 0 on negative side).

(defn run-attr-recovery [opts]) ;; generate -> fit attr_logit.stan -> score.
(defn run-spatial-recovery [opts]) ;; generate -> fit spatial.stan -> score.

(defn depth-vs-field-experiment [opts])
;; TWO ARMS on the SAME simulated data:
;;  Arm 1: fit spatial_depthonly.stan (no field) -> upper bound on b1,b2,z*.
;;  Arm 2: fit spatial.stan (RBF + depth)        -> same data.
;;  PASS iff Arm2 b1,b2,z* stay within tolerance of Arm1 AND b2<0 holds.
;;  If Arm2 collapses b2->0, REPORT "field steals depth" = the real confound.
```

### 3.4 `orca.sbc` — simulation-based calibration (IMPURE, OPTIONAL, last)

```
(ns orca.sbc
  (:require [orca.stan :as stan] [orca.diagnostics :as diag]
            [orca.synth :as synth] [orca.simtracks :as sim]))

(defn one-replication [rng model-key]) ;; draw theta~prior, sim, fit, rank.
(defn rank-stat [draws true-val]) ;; # draws below truth (the SBC rank).
(defn run-sbc [{:keys [m model-key seed]}]) ;; m datasets from PRIORS.
(defn ecdf-diff-bands [ranks]) ;; uniform=calibrated; n=overconfident; skew=biased.
;; Smoke M=100 ; full M>=256. Heaviest tier — run only after Tier I passes.
```

---

## 4. New Stan file (one)

`orca_clj/stan/spatial_depthonly.stan` — Arm 1 upper-bound model: depth
quadratic, NO RBF field. Hand-author (no formatter). Exact contents:

```
data {
  int<lower=0> N;
  array[N] int<lower=0,upper=1> y;
  vector[N] z;
  vector[N] z2;
}
parameters {
  real b0;
  real b_d1;
  real b_d2;
}
model {
  b0 ~ normal(0, 5);
  b_d1 ~ normal(0, 0.5);
  b_d2 ~ normal(0, 0.5);
  y ~ bernoulli_logit(b0 + b_d1 * z + b_d2 * z2);
}
```

`attr_logit.stan` and `spatial.stan` are REUSED unchanged. (Optional sibling
`attr_logit_tight.stan` only if we decide to recover against N(0,0.5) slopes
rather than the checked-in N(0,1); not required for the core experiment.)

---

## 5. Recovery thresholds (code-ready constants -> `orca.recover`)

```clojure
(def rhat-max         1.01)   ; convergence gate
(def ess-min          400.0)  ; ess-bulk AND ess-tail must exceed
(def max-divergences  0)      ; from CmdStan CSV header
(def ci-prob          0.90)   ; central credible interval for coverage
(def bias-z-max       2.0)    ; |(mean - true)/sd| must be < this
(def surface-corr-min 0.90)   ; Pearson corr(lambda-hat, lambda-true), cell-wise
(def b2-neg?          true)   ; depth quadratic 90% upper bound must be < 0
(def depth-peak-tol-m 50.0)   ; true z* must lie inside z*'s 90% CI (report metres)
(def arm-gap-tol      0.30)   ; |Arm2 - Arm1| on b1,b2 (logit units) to "not steal"
(def sbc-m-smoke      100)
(def sbc-m-full       256)
(def recovery-seed    42)     ; java.util.Random seed (matches existing pattern)
```

Pass rules:
- Per-parameter PASS = `covered AND bias-ok AND mcmc-gate`.
- Intercept PASS uses King-Zeng-corrected truth, NOT raw base rate.
- Surface PASS = `corr >= 0.90`.
- Depth PASS = `z* CI brackets true z*` AND `b2 upper-90% < 0`.
- Confound PASS = Arm2 within `arm-gap-tol` of Arm1 AND depth PASS in Arm2.

---

## 6. Test plan (`orca_clj/test/orca/`, `clojure -X:test`)

Pure-function tests only (no CmdStan), seed 42, `close?` epsilon helper as in
`orca.util-test`:

- `synth_test.clj` (`orca.synth-test`):
  - `logit` known values (logit 0.03 ≈ -3.476).
  - `simulate-attr` deterministic given seed (two calls equal).
  - empirical incident rate of simulated y ≈ base-rate within tolerance at
    large N (sanity that alpha maps to the base rate).
  - `draw-categorical` empirical proportions ≈ probs.
- `simtracks_test.clj` (`orca.simtracks-test`):
  - `real-centers` count == 84 (matches POSTERIOR_SCHEMA).
  - `log-lambda` reproduces `spatial.stan`'s linear predictor for a hand
    case (basis . w + b0 + b1 z + b2 z2).
  - `build-sim-design` returns the keys `pf/fit-spatial-chains` reads
    (`:N :M :y :Bsp :z :z2`) with consistent shapes (Bsp is N x M, etc.).
  - true z* = -b1/(2 b2) computed correctly.
- `recover_test.clj` (`orca.recover-test`):
  - `kingzeng-offset` matches the closed form on a worked example.
  - `coverage` flags covered/bias correctly on synthetic mean/sd inputs.
  - `mcmc-gate` thresholds (boundary cases at 1.01 / 400).
  - Pearson corr helper on a known pair.

(The fit-driving fns — `run-*-recovery`, `depth-vs-field-experiment`, SBC —
are exercised via REPL / a manual `:recover` alias, NOT in `:test`, since they
need CmdStan and the nix shell.)

Run-recovery entry, mirroring `:planner-fit`, to add to `deps.edn` aliases when
implementing (not in this plan file):
`nix develop ../ --command bash -c 'export CMDSTAN=$HOME/.cache/orca-cmdstan; clojure -X:recover ...'`.

---

## 7. Prior-predictive checks (do BEFORE recovery)

Extend the existing pattern in `orca.models/prior-predictive`:
- Part B: draw `b0~N(-3.5,..)`-style intercept + `b_d1,b_d2~N(0,0.5)`, sample
  `w~N(0,tau)`, `tau~N(0,0.4)T[0,]`; compute implied per-passage probabilities
  over the real grid; CONFIRM mass is rare/plausible (bulk < ~10%, no pile-up
  at 0 or 1).
- Depth quadratic priors -> finite, unimodal curves over the observed z range
  (no runaway convex bowls). Live in `orca.simtracks` (pure) + a check fn in
  `orca.recover`. Gate: if prior predictive is implausible, STOP and revisit
  before any recovery run.

---

## 8. Build order (parallel vs dependent) + iterate gate

Independent (parallelizable across fresh subagents — each has all it needs from
this plan):
- **B1** `orca.synth` (pure; depends only on `orca.util`). + `synth_test`.
- **B2** `stan/spatial_depthonly.stan` (hand-authored; no formatter).
- **B3** `orca.simtracks` (pure; depends on `orca.planner-fit` read-only +
  `orca.util`). + `simtracks_test`.

Dependent (after the above):
- **B4** `orca.recover` (needs `orca.synth`, `orca.simtracks`, `orca.stan`,
  `orca.diagnostics`; needs B2 for Arm 1). + `recover_test` (pure parts).
- **B5** Prior-predictive checks (uses B1/B3; gate before B6).
- **B6** Run Tier-I recovery: `run-attr-recovery`, `run-spatial-recovery`,
  then `depth-vs-field-experiment`. **VALIDATE GATE:** all Section-5 pass
  rules must hold. If the confound experiment shows Arm2 collapses b2->0, that
  is the *diagnosis* of the real-model problem — report it, then iterate the
  `separable-config` (sharpen depth's spatial scale / increase decorrelation)
  and re-run until depth is recoverable on synthetic data OR we conclude the
  real model genuinely cannot separate them at ell=150km.
- **B7** (OPTIONAL, last) `orca.sbc`: smoke M=100, then M>=256.

Validate-and-iterate gate applies after B6: do NOT trust any real-data
re-interpretation until synthetic recovery passes. If it fails, iterate the
simulator/config, not the thresholds.

---

## 9. Flag: distance-to-shore is currently UNUSED in the location model

`spatial.stan` uses only the RBF field + depth quadratic; there is no
distance-to-shore covariate in Part B (it exists as a Part-A ordinal
`distance_ord` only). Once **depth recovery is trusted** (B6 passes), distance
-to-shore is the natural next covariate to add to Part B and validate by the
SAME recovery loop (extend `log-lambda`, add a coefficient, add it to the
two-arm confound test). Do NOT add it before depth recovery is established —
adding a second smooth location covariate while depth/field separability is
unproven would compound the confounding.

---

## 10. King-Zeng note (so a correct fit is not flagged biased)

The Part-B intercept `b0` is NOT the population base rate: in a use-
availability / case-control design it absorbs the presence:background sampling
ratio. Expected corrected intercept:

```
b0_corrected = b0_hat - ln( ((1 - tau)/tau) * (ybar/(1 - ybar)) )
```

where `tau` = sampled presence fraction and `ybar` = same in this balanced-by
-design setup. Slopes (`b_d1`, `b_d2`) and field weights are UNAFFECTED by the
offset; only the intercept check uses the correction. Bake this into
`score-spatial` so the intercept coverage test compares the corrected estimate
to the true `b0`, not the raw one.
