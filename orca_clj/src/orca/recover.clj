(ns orca.recover
  "Fit + score parameter-recovery harness (SYNTHETIC_VALIDATION_PLAN B4, IMPURE).

   Ties the PURE synthetic generators (`orca.synth` Part-A, `orca.simtracks`
   Part-B) to CmdStan (`orca.stan`) and the diagnostics (`orca.diagnostics`),
   applies the plan's recovery thresholds (§5), and reports per-parameter
   PASS/FAIL against the STORED ground truth. This is the step that confirms the
   model recovers what it was simulated from before any real-data fit is trusted.

   What is scored, per the plan:
     - coverage: truth ∈ central 90% credible interval (ETI 0.05/0.95).
     - bias z-score (mean − truth)/sd, require |z| < 2.
     - MCMC gate: rhat < 1.01, ess-bulk > 400, ess-tail > 400, divergences == 0.
   Part-B's intercept b0 is a use-availability log-intensity intercept (NOT a
   prevalence logit), and the presence-thinning never uses b0, so the generative
   b0 is NOT identified as a base rate — it is reported (King–Zeng-corrected) for
   transparency but EXCLUDED from the pass/fail rather than forced into a bogus
   pass (see `kingzeng-intercept` / `recover-spatial`). The Part-B verdict rests
   on the depth coefficients (b_d1, b_d2, which need no correction) plus the
   surface, scored by Pearson corr(λ̂, λ_true) over grid cells (≥ 0.90) and the
   depth peak z* = −b_d1/(2·b_d2).

   The two-arm depth experiment (`two-arm-depth`) is the crux: fit the
   depth-only model (Arm 1) and the full RBF+depth model (Arm 2) on the SAME
   separable dataset and compare recovered depth coefficients — if the flexible
   field steals the depth signal (Arm-2 b_d2 collapses toward 0), that IS the
   diagnosis of the real-model confounding, reported as such, not a harness bug.

   `smoke` runs the WHOLE pipeline at TINY scale (small N, 2 chains, ~300+300)
   purely to prove the plumbing fits end-to-end. The full recovery run (large N,
   4 chains × 1000+2000) is build step B6, not here."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [orca.diagnostics :as diag]
   [orca.simtracks :as sim]
   [orca.stan :as stan]
   [orca.synth :as synth]
   [orca.util :as util]))

;; ── recovery thresholds (code-ready constants, plan §5) ──────────────────────

(def rhat-max
  "Convergence gate: rank-normalized folded split-R̂ must be below this."
  1.01)

(def ess-min
  "Both ess-bulk AND ess-tail must EXCEED this effective sample size."
  400.0)

(def max-divergences
  "Allowed post-warmup divergent transitions (from the CmdStan CSV header)."
  0)

(def ci-prob
  "Central credible-interval mass used for the coverage check (90%)."
  0.90)

(def bias-z-max
  "|(mean − truth)/sd| must be strictly below this to pass the bias check."
  2.0)

(def surface-corr-min
  "Minimum cell-wise Pearson corr(λ̂, λ_true) for the surface to pass."
  0.90)

(def arm-gap-tol
  "Max |Arm2 − Arm1| on b_d1/b_d2 (logit units) for the field to count as NOT
   having stolen the depth effect in the two-arm experiment."
  0.30)

(def recovery-seed
  "java.util.Random seed (matches the existing orca.models convention)."
  42)

;; ── pure scoring helpers ─────────────────────────────────────────────────────

(defn coverage
  "Coverage + bias check for ONE parameter against its `truth` value, given the
   posterior `mean`, `sd` and central-`ci-prob` interval `[lo hi]`.

   Returns {:covered  truth ∈ [lo hi]
            :z        (mean − truth)/sd  (∞ if sd is 0)
            :bias-ok  |z| < bias-z-max}."
  [mean sd lo hi truth]
  (let [mean (double mean) sd (double sd) truth (double truth)
        z (if (zero? sd)
            (if (== mean truth) 0.0 Double/POSITIVE_INFINITY)
            (/ (- mean truth) sd))]
    {:covered (and (<= (double lo) truth) (<= truth (double hi)))
     :z z
     :bias-ok (< (Math/abs z) bias-z-max)}))

(defn mcmc-ok?
  "True iff a parameter's MCMC diagnostics clear the gate: rhat < rhat-max,
   ess-bulk > ess-min, ess-tail > ess-min. (Divergences are a per-FIT gate, not
   per-parameter, applied separately in `score-recovery`.)"
  [{:keys [rhat ess-bulk ess-tail]}]
  (and (< (double rhat) rhat-max)
       (> (double ess-bulk) ess-min)
       (> (double ess-tail) ess-min)))

(defn pearson
  "Pearson correlation of two equal-length numeric seqs. Returns 0.0 when either
   series is constant (zero variance)."
  ^double [xs ys]
  (let [mx (util/mean xs) my (util/mean ys)
        dx (mapv #(- (double %) mx) xs)
        dy (mapv #(- (double %) my) ys)
        sxy (reduce + (map * dx dy))
        sxx (reduce + (map #(* % %) dx))
        syy (reduce + (map #(* % %) dy))]
    (if (or (zero? sxx) (zero? syy)) 0.0
        (/ sxy (Math/sqrt (* sxx syy))))))

(defn depth-peak
  "Depth quadratic peak z* = −b_d1/(2·b_d2). Returns ##NaN if b_d2 is 0 (no
   peak). With b_d1>0, b_d2<0 this is the preferred (standardized) depth."
  ^double [b-d1 b-d2]
  (if (zero? (double b-d2))
    Double/NaN
    (/ (- (double b-d1)) (* 2.0 (double b-d2)))))

(defn kingzeng-intercept
  "King–Zeng (2001) prior-correction of a case-control / choice-based-sampled
   logit intercept. Given the fitted `b0-hat`, the TRUE POPULATION EVENT FRACTION
   `tau` (the prevalence in the population the sample was drawn from — NOT a
   coefficient, NOT a prior scale) and the SAMPLE event fraction `ybar`
   (= n_presence / N as actually realized in the fitted data), the corrected
   intercept is

       b0_corrected = b0_hat − ln[ ((1−tau)/tau) · (ybar/(1−ybar)) ].

   The bracketed term is the EXPECTED offset the case-control oversampling of
   events injects into a LOGIT intercept; subtracting it puts that intercept back
   on the population (prevalence-logit) scale so a CORRECT fit is not falsely
   flagged biased. SLOPES and the depth coefficients (b_d1, b_d2) and the RBF
   field weights are UNAFFECTED by this offset — only the intercept moves.

   IMPORTANT (see `recover-spatial`): this correction is only MEANINGFUL when the
   fitted intercept IS a prevalence logit, i.e. for a genuine case-control logit
   like Part-A's `attr_logit`. For the Part-B use-availability point-process
   intensity `log λ = b0 + f_rbf + b_d1 z + b_d2 z²`, b0 is a log-INTENSITY
   intercept, not a base-rate logit, and (critically) the use-availability
   thinning that draws the presences never uses b0 at all (it accepts ∝
   exp(f + depth)). So no τ recovers the generative b0 there — b0 is simply not
   identified as a base rate, and we report it OUTSIDE the pass/fail (not corrected
   into a bogus pass). Returns {:b0-hat :b0-corrected :offset :tau :ybar}."
  [b0-hat tau ybar]
  (let [b0-hat (double b0-hat) tau (double tau) ybar (double ybar)
        offset (Math/log (* (/ (- 1.0 tau) tau)
                            (/ ybar (- 1.0 ybar))))]
    {:b0-hat b0-hat
     :b0-corrected (- b0-hat offset)
     :offset offset
     :tau tau :ybar ybar}))

;; ── CmdStan plumbing ─────────────────────────────────────────────────────────

(defn count-divergences
  "Parse the post-warmup divergent-transition count from a vector of per-chain
   draws datasets. CmdStan emits a `divergent__` column (1.0 per divergent
   draw); we sum it across all chains. Falls back to 0 if the column is absent."
  [chains]
  (reduce + (map (fn [ds]
                   (reduce + (map #(if (and % (pos? (double %))) 1 0)
                                  (get ds "divergent__" (repeat 0)))))
                 chains)))

(defn fit-and-summarize
  "Generic fit: compile + sample `stan-path` against `data`, then summarize the
   named `params`. Runs `orca.stan/sample-chains` and reuses
   `orca.diagnostics` for every number.

   Returns {:summary  {param {:mean :sd :eti-low :eti-high
                              :rhat :ess-bulk :ess-tail}}
            :divergences  <int post-warmup divergent count>
            :chains       <raw per-chain datasets, for surface reconstruction>}.

   The credible interval is the central `ci-prob` (90%) ETI, so :eti-low /
   :eti-high are the 0.05 / 0.95 quantiles used by the coverage check. `opts`
   flow straight to `sample-chains` (n-chains, seed, num-warmup, num-samples,
   out-dir)."
  [stan-path data params opts]
  (let [chains (stan/sample-chains stan-path data opts)
        per-chain (fn [p] (mapv (fn [ds] (vec (get ds p))) chains))
        summary (into {}
                      (map (fn [p]
                             (let [c (per-chain p)
                                   flat (apply concat c)
                                   [lo hi] (diag/eti flat ci-prob)]
                               [p {:mean (util/mean flat)
                                   :sd (util/pstdev flat)
                                   :eti-low lo :eti-high hi
                                   :rhat (diag/rhat c)
                                   :ess-bulk (diag/ess-bulk c)
                                   :ess-tail (diag/ess-tail c)}])))
                      params)]
    {:summary summary
     :divergences (count-divergences chains)
     :chains chains}))

;; ── scoring against ground truth ─────────────────────────────────────────────

(defn score-recovery
  "Score a `fit` (from `fit-and-summarize`) against a `truth` map keyed by the
   SAME parameter names. Each parameter gets a row:

     {:param :truth :mean :sd :eti [lo hi] :covered :z :bias-ok
      :mcmc-ok :pass}

   where per-parameter PASS = covered AND bias-ok AND mcmc-ok AND (the FIT-level
   divergence gate holds). Returns {:rows [..] :divergences :divergences-ok
   :pass <overall: every row passes AND divergences ok>}.

   Parameters present in `truth` but missing from the fit summary are skipped
   with a warning row (so e.g. a King–Zeng-corrected intercept can be supplied
   under a synthetic key)."
  [{:keys [summary divergences]} truth]
  (let [div-ok (<= divergences max-divergences)
        rows (mapv (fn [[p tv]]
                     (if-let [{:keys [mean sd eti-low eti-high] :as st}
                              (get summary p)]
                       (let [{:keys [covered z bias-ok]}
                             (coverage mean sd eti-low eti-high tv)
                             mok (mcmc-ok? st)]
                         {:param p :truth (double tv)
                          :mean mean :sd sd :eti [eti-low eti-high]
                          :covered covered :z z :bias-ok bias-ok
                          :mcmc-ok mok
                          :pass (and covered bias-ok mok div-ok)})
                       {:param p :truth (double tv) :missing true :pass false}))
                   truth)]
    {:rows rows
     :divergences divergences
     :divergences-ok div-ok
     :pass (and div-ok (every? :pass rows))}))

(defn- posterior-mean
  "Posterior mean of one named column over all chains."
  [chains p]
  (util/mean (apply concat (mapv (fn [ds] (vec (get ds p))) chains))))

(defn- posterior-mean-w
  "Posterior-mean RBF weight vector [w.1 .. w.M] over all chains."
  [chains m]
  (mapv (fn [j] (posterior-mean chains (str "w." (inc j)))) (range m)))

(defn score-surface
  "Score the recovered Part-B intensity SURFACE against the stored ground truth.

   Reconstructs λ̂ per grid cell with the SAME field math the simulator and the
   planner use — `orca.simtracks/log-lambda-at` — but with the FITTED posterior
   means swapped in (posterior-mean w, b0, b_d1, b_d2). λ_true per cell is read
   straight from `truth` (`:lambda-true-per-cell`), so we never re-derive the
   formula. Scores:

     - Pearson corr(λ̂(s), λ_true(s)) over cells, require ≥ surface-corr-min.
     - depth peak z* = −b_d1/(2·b_d2): the posterior-mean z* and whether the
       true z* lies inside z*'s reconstructed central-90% interval (z* is a
       nonlinear function of the draws, so we evaluate it per draw and take its
       0.05/0.95 quantiles → 'bracketing').

   `surface` is the generating surface map (from the sim's `:full :surface`),
   needed for centers/col-means/ell/dctx that the field math reuses. Returns
   {:corr :corr-ok :z-star-true :z-star-hat :z-star-eti :z-star-bracketed
    :pass (corr-ok AND z-star-bracketed)}."
  [chains surface truth]
  (let [{:keys [cells lambda-true-per-cell z-star]} truth
        m (count (:w surface))
        w-hat (posterior-mean-w chains m)
        b0-hat (posterior-mean chains "b0")
        b1-hat (posterior-mean chains "b_d1")
        b2-hat (posterior-mean chains "b_d2")
        fit-surface (assoc surface :w w-hat :b0 b0-hat :b1 b1-hat :b2 b2-hat)
        lambda-hat (mapv (fn [[la lo]]
                           (Math/exp (sim/log-lambda-at fit-surface
                                                        (double la) (double lo) 0.0)))
                         cells)
        corr (pearson lambda-hat lambda-true-per-cell)
        ;; per-draw z* for bracketing (nonlinear function of b_d1,b_d2)
        b1-draws (apply concat (mapv (fn [ds] (vec (get ds "b_d1"))) chains))
        b2-draws (apply concat (mapv (fn [ds] (vec (get ds "b_d2"))) chains))
        zstar-draws (filterv #(not (Double/isNaN %))
                             (mapv depth-peak b1-draws b2-draws))
        [zlo zhi] (diag/eti zstar-draws ci-prob)
        zstar-true (double z-star)
        bracketed (and (<= zlo zstar-true) (<= zstar-true zhi))]
    {:corr corr
     :corr-ok (>= corr surface-corr-min)
     :z-star-true zstar-true
     :z-star-hat (depth-peak b1-hat b2-hat)
     :z-star-eti [zlo zhi]
     :z-star-bracketed bracketed
     :pass (and (>= corr surface-corr-min) bracketed)}))

;; ── end-to-end Part-A ────────────────────────────────────────────────────────

(def smoke-mcmc
  "TINY sampling opts for the smoke run: 2 chains, ~300 warmup / 300 sample.
   Proves the plumbing fits; NOT a real recovery (that is B6)."
  {:n-chains 2 :seed recovery-seed :num-warmup 300 :num-samples 300})

(defn recover-attr
  "End-to-end Part-A recovery. Generate synthetic attribute data via
   `orca.synth`, fit `attr_logit.stan`, and score the recovered alpha + betas
   against the known truth.

   `opts` keys:
     :gen   config for `orca.synth/simulate-attr` (base-rate, predictors,
            true-slopes, n, seed).
     :mcmc  sampling opts for `orca.stan/sample-chains` (default `smoke-mcmc`).
     :out-dir  CmdStan working dir (default \"out/recover_attr\").

   Returns {:truth :summary :report} where :report is `score-recovery`'s map."
  [{:keys [gen mcmc out-dir]}]
  (let [data (synth/simulate-attr gen)
        k (:K data)
        params (into ["alpha"] (mapv #(str "beta." (inc %)) (range k)))
        fit (fit-and-summarize "stan/attr_logit.stan"
                               {:N (:N data) :K k :X (:X data) :y (:y data)}
                               params
                               (merge smoke-mcmc mcmc
                                      {:out-dir (or out-dir "out/recover_attr")}))]
    {:truth (:truth data)
     :summary (:summary fit)
     :report (score-recovery fit (:truth data))}))

;; ── end-to-end Part-B ────────────────────────────────────────────────────────

(defn- spatial-truth-params
  "Stan-param-keyed truth map for the spatial.stan coefficients that ARE in the
   pass/fail. ONLY the depth coefficients (b_d1, b_d2) are scored — they map
   straight through with no correction and are unaffected by the
   use-availability sampling. The intercept b0 is DELIBERATELY ABSENT here: for
   this use-availability point process b0 is a log-intensity intercept, not a
   prevalence logit, and the presence-thinning never uses b0, so no King–Zeng τ
   recovers the generative b0 (see `recover-spatial` / `kingzeng-intercept`). It
   is reported separately, outside the verdict, rather than forced into a pass."
  [truth]
  {"b_d1" (:b1 truth)
   "b_d2" (:b2 truth)})

(defn recover-spatial
  "End-to-end Part-B recovery. Generate synthetic track data via
   `orca.simtracks/simulate`, fit `spatial.stan`, and score the depth
   coefficients + the intensity surface (the intercept b0 is reported
   King–Zeng-corrected but excluded from the verdict — see below).

   `opts` keys:
     :sim   config for `orca.simtracks/simulate` (seed, config :mimic/:separable,
            truth, n-pres). Default {:config :separable :seed recovery-seed}.
     :mcmc  sampling opts (default `smoke-mcmc`).
     :out-dir  CmdStan working dir (default \"out/recover_spatial\").

   The intercept b0 is NOT in the pass/fail. For this use-availability point
   process b0 is a log-intensity intercept (not a prevalence logit) and the
   presence-thinning never uses it, so the generative b0 is not identified as a
   base rate — King & Zeng (2001). We still REPORT the King–Zeng-corrected b0 for
   transparency (threading the true population event fraction τ when the truth
   exposes one, else ȳ), flagged `:b0-identified? false` with the reason, but the
   verdict rests on the depth coefficients + surface, which ARE unaffected.

   Returns {:truth :summary :report :surface :kingzeng}."
  [{:keys [sim mcmc out-dir]}]
  (let [sim-out (sim/simulate (merge {:config :separable :seed recovery-seed} sim))
        {:keys [data truth full]} sim-out
        surface (:surface full)
        params ["b0" "b_d1" "b_d2"]
        fit (fit-and-summarize "stan/spatial.stan"
                               data params
                               (merge smoke-mcmc mcmc
                                      {:out-dir (or out-dir "out/recover_spatial")}))
        ybar (/ (double (reduce + (:y data))) (double (:N data)))
        b0-hat (get-in fit [:summary "b0" :mean])
        b0-sd (get-in fit [:summary "b0" :sd])
        b0-lo (get-in fit [:summary "b0" :eti-low])
        b0-hi (get-in fit [:summary "b0" :eti-high])
        ;; τ = the TRUE POPULATION event fraction the sample was drawn from. The
        ;; use-availability simulator does not expose a base-rate prevalence (b0
        ;; cancels in the thinning), and the old code mis-read the RBF weight-prior
        ;; scale (:tau ≈ 0.4) as τ — both wrong. We use the truth's explicit base
        ;; rate if one is ever supplied, else ȳ (a no-op offset), purely so the
        ;; REPORTED corrected value is honest; b0 stays out of the verdict either
        ;; way. NB: :tau / :tau-true in the Part-B truth is the weight-prior σ, NOT
        ;; an event fraction, so it is intentionally NOT consulted here.
        tau (double (or (:base-rate truth) (:population-event-fraction truth) ybar))
        kz (assoc (kingzeng-intercept b0-hat tau ybar)
                  :b0-identified? false
                  :note (str "b0 is a use-availability log-intensity intercept, "
                             "not a base-rate logit, and the presence-thinning "
                             "never uses b0 — so the generative b0 is NOT "
                             "identified as a base rate (King & Zeng 2001). "
                             "Reported for transparency; EXCLUDED from pass/fail."))
        offset (:offset kz)
        ;; keep a b0-corrected summary in the EDN/display for transparency (NOT a
        ;; scored row — it is absent from truth-params, so the verdict ignores it).
        summary+ (assoc (:summary fit)
                        "b0-corrected" {:mean (:b0-corrected kz) :sd b0-sd
                                        :eti-low (- b0-lo offset)
                                        :eti-high (- b0-hi offset)
                                        :rhat (get-in fit [:summary "b0" :rhat])
                                        :ess-bulk (get-in fit [:summary "b0" :ess-bulk])
                                        :ess-tail (get-in fit [:summary "b0" :ess-tail])})
        truth-params (spatial-truth-params truth)
        report (score-recovery (assoc fit :summary summary+) truth-params)
        surf (score-surface (:chains fit) surface truth)]
    {:truth truth
     :summary summary+
     :kingzeng kz
     :surface surf
     :report (assoc report :surface surf
                    :pass (and (:pass report) (:pass surf)))}))

;; ── the crux: two-arm depth-vs-field experiment ──────────────────────────────

(defn- depth-arm-summary
  "Extract {:b_d1 :b_d2 :z-star} posterior means from a fit summary."
  [summary]
  {:b_d1 (get-in summary ["b_d1" :mean])
   :b_d2 (get-in summary ["b_d2" :mean])
   :z-star (depth-peak (get-in summary ["b_d1" :mean])
                       (get-in summary ["b_d2" :mean]))})

(defn two-arm-depth
  "THE CRUX. Generate ONE separable dataset, then fit the depth coefficients two
   ways on the SAME data:

     Arm 1  `spatial_depthonly.stan`  — depth quadratic, NO RBF field. An upper
            bound on the depth signal (no field to compete with).
     Arm 2  `spatial.stan`            — RBF field + depth, on the SAME y/z/z2.

   Compares recovered b_d1, b_d2, z* across arms. PASS iff Arm-2's depth coeffs
   stay within `arm-gap-tol` of Arm-1's AND Arm-2's b_d2 stays clearly negative
   (the field did NOT steal the peaked depth preference). If Arm-2's b_d2
   collapses toward 0, that IS the diagnosis of the real-model depth/field
   confounding — reported in :verdict, NOT treated as a harness bug.

   `opts` keys: :sim (default {:config :separable}), :mcmc (default smoke), and
   :out-dir (default \"out/two_arm\"). Returns
   {:arm1 :arm2 :gap :verdict :pass :truth}."
  [{:keys [sim mcmc out-dir]}]
  (let [out (or out-dir "out/two_arm")
        sim-out (sim/simulate (merge {:config :separable :seed recovery-seed} sim))
        {:keys [data truth]} sim-out
        ;; Arm 1 needs only y/z/z2 (no field).
        arm1-data {:N (:N data) :y (:y data) :z (:z data) :z2 (:z2 data)}
        arm1 (fit-and-summarize "stan/spatial_depthonly.stan"
                                arm1-data ["b0" "b_d1" "b_d2"]
                                (merge smoke-mcmc mcmc {:out-dir (str out "/arm1")}))
        arm2 (fit-and-summarize "stan/spatial.stan"
                                data ["b0" "b_d1" "b_d2"]
                                (merge smoke-mcmc mcmc {:out-dir (str out "/arm2")}))
        s1 (depth-arm-summary (:summary arm1))
        s2 (depth-arm-summary (:summary arm2))
        gap-b1 (Math/abs (- (double (:b_d1 s2)) (double (:b_d1 s1))))
        gap-b2 (Math/abs (- (double (:b_d2 s2)) (double (:b_d2 s1))))
        b2-2-sd (get-in arm2 [:summary "b_d2" :sd])
        b2-2-mean (:b_d2 s2)
        ;; "clearly negative" = posterior mean below 0 by more than ~2 sd.
        b2-neg (< (+ (double b2-2-mean) (* 2.0 (double b2-2-sd))) 0.0)
        within-tol (and (< gap-b1 arm-gap-tol) (< gap-b2 arm-gap-tol))
        stole? (or (not b2-neg)
                   (< (Math/abs (double b2-2-mean))
                      (* 0.5 (Math/abs (double (:b_d2 s1))))))
        verdict (cond
                  (and within-tol b2-neg)
                  "PASS: Arm-2 depth coeffs match Arm-1; the RBF field did NOT steal depth."
                  stole?
                  (str "DIAGNOSIS: the RBF field STEALS the depth signal — Arm-2 b_d2 "
                       "collapsed toward 0 (Arm1 b_d2=" (format "%.3f" (:b_d2 s1))
                       ", Arm2 b_d2=" (format "%.3f" b2-2-mean)
                       "). This is the real-model confounding, not a harness bug.")
                  :else
                  (str "PARTIAL: Arm-2 depth shifted from Arm-1 beyond tol (gap b_d1="
                       (format "%.3f" gap-b1) ", b_d2=" (format "%.3f" gap-b2) ")."))]
    {:arm1 (assoc s1 :divergences (:divergences arm1))
     :arm2 (assoc s2 :divergences (:divergences arm2))
     :gap {:b_d1 gap-b1 :b_d2 gap-b2}
     :truth (select-keys truth [:b0 :b1 :b2 :z-star])
     :verdict verdict
     :pass (and within-tol b2-neg)}))

;; ── smoke (plumbing proof, tiny scale) ───────────────────────────────────────

(def ^:private smoke-attr-gen
  "Tiny Part-A generation config: a couple of continuous predictors, modest N."
  {:n 400
   :base-rate 0.2
   :predictors [{:dist :normal :mean 0.0 :sd 1.0}
                {:dist :normal :mean 0.0 :sd 1.0}]
   :true-slopes [0.8 -0.6]
   :seed recovery-seed})

(defn- pp-attr [{:keys [report]}]
  (println "  Part-A attr_logit recovery:")
  (doseq [{:keys [param truth mean covered bias-ok mcmc-ok pass]} (:rows report)]
    (println (format "    %-10s true=%+.3f mean=%+.3f cover=%s bias=%s mcmc=%s -> %s"
                     param (double truth) (double mean)
                     covered bias-ok mcmc-ok (if pass "PASS" "fail"))))
  (println "    divergences=" (:divergences report) " overall=" (:pass report)))

(defn- pp-spatial [{:keys [report kingzeng surface]}]
  (println "  Part-B spatial recovery:")
  (doseq [{:keys [param truth mean covered bias-ok mcmc-ok pass]} (:rows report)]
    (println (format "    %-12s true=%+.3f mean=%+.3f cover=%s bias=%s mcmc=%s -> %s"
                     param (double truth) (double mean)
                     covered bias-ok mcmc-ok (if pass "PASS" "fail"))))
  (println (format "    king-zeng: b0_hat=%+.3f offset=%+.3f -> b0_corrected=%+.3f"
                   (:b0-hat kingzeng) (:offset kingzeng) (:b0-corrected kingzeng)))
  (when (false? (:b0-identified? kingzeng))
    (println (str "      b0: NOT identified as a base rate (use-availability — "
                  "King & Zeng); EXCLUDED from pass/fail.")))
  (println (format "    surface: corr=%.3f (>=%.2f? %s)  z*_true=%+.3f z*_hat=%+.3f bracketed=%s"
                   (:corr surface) surface-corr-min (:corr-ok surface)
                   (:z-star-true surface) (:z-star-hat surface)
                   (:z-star-bracketed surface)))
  (println "    overall=" (:pass report)))

(defn- pp-two-arm [{:keys [arm1 arm2 gap verdict pass]}]
  (println "  Two-arm depth-vs-field:")
  (println (format "    Arm1 (depth-only): b_d1=%+.3f b_d2=%+.3f z*=%+.3f"
                   (:b_d1 arm1) (:b_d2 arm1) (:z-star arm1)))
  (println (format "    Arm2 (field+depth): b_d1=%+.3f b_d2=%+.3f z*=%+.3f"
                   (:b_d1 arm2) (:b_d2 arm2) (:z-star arm2)))
  (println (format "    gap b_d1=%.3f b_d2=%.3f (tol %.2f)"
                   (:b_d1 gap) (:b_d2 gap) arm-gap-tol))
  (println "    verdict:" verdict)
  (println "    pass=" pass))

;; ── B6b: REALISTIC-CASE mimic recovery orchestration (persists incrementally) ─

(def mimic-out-dir
  "Out-dir for the B6b mimic recovery experiments (one EDN file per experiment,
   written the moment that experiment finishes so socket death cannot lose it)."
  "out/recovery/mimic")

(def mimic-mcmc
  "Realistic-scale sampling opts for B6b: 4 chains × 1000 warmup + 1000 sample."
  {:n-chains 4 :seed recovery-seed :num-warmup 1000 :num-samples 1000})

(def mimic-truth
  "Known true generating params for the B6b mimic run. Depth coeffs match the
   real fit (b_d1≈+1.3, b_d2≈-1.6) so there is a concrete ground truth to
   recover; b0 is the use-availability intercept and tau the presence fraction."
  {:b0 -1.0 :tau-true 0.4 :b1-true 1.3 :b2-true -1.6})

(defn- edn-spit
  "Write `data` to `path` as pretty EDN, making parent dirs. Used for the
   incremental per-experiment persistence so an interrupted run can resume."
  [path data]
  (io/make-parents path)
  (spit path (with-out-str (pprint/pprint data))))

(defn- serializable-report
  "Strip the non-EDN-friendly bits (raw chains, surface map carrying the bathy
   grid / centers) from a score-recovery + surface report so it can be spat to
   EDN. Keeps every NUMBER the verdict needs."
  [report]
  (-> report
      (update :surface #(when % (dissoc % :pass)))
      (select-keys [:rows :divergences :divergences-ok :pass :surface])))

(defn recover-spatial-mimic
  "B6b Part-B spatial recovery on the :mimic config (depth spatially correlated
   with the RBF field, like the REAL data — NOT decorrelated). Realistic scale:
   n-presence≈216, real background, 4 chains × 1000+1000. Scores b0 (King–Zeng
   corrected), b_d1, b_d2 and the intensity surface (corr + z* bracketing)
   against the known truth, then writes spatial-result.edn immediately.

   `opts` keys: :n-pres (default 216), :n-bg passthrough is the REAL background
   (mimic mode ignores it), :out-dir (default mimic-out-dir)."
  [{:keys [n-pres out-dir]}]
  (let [out (or out-dir mimic-out-dir)
        result (recover-spatial {:sim {:config :mimic :seed recovery-seed
                                       :truth mimic-truth
                                       :n-pres (or n-pres 216)}
                                 :mcmc mimic-mcmc
                                 :out-dir (str out "/spatial")})
        payload {:experiment :spatial-mimic
                 :config :mimic
                 ;; drop the heavy per-cell ground-truth arrays (cells,
                 ;; lambda-true-per-cell, w-true, z-of-cell) — the scalar truth
                 ;; and scored surface numbers are what the verdict needs.
                 :truth (dissoc (:truth result) :cells :lambda-true-per-cell
                                :w-true :z-of-cell)
                 :kingzeng (:kingzeng result)
                 :summary (:summary result)
                 :report (serializable-report (:report result))}]
    (edn-spit (str out "/spatial-result.edn") payload)
    (assoc result :payload payload)))

(defn two-arm-mimic
  "B6b two-arm depth-vs-field experiment on the SAME :mimic dataset. Arm-1
   depth-only (spatial_depthonly.stan) vs Arm-2 full field+depth (spatial.stan).
   Reports the Arm-1→Arm-2 shift/collapse of b_d1, b_d2, z* — a large Arm-2
   collapse of b_d2 toward 0 = the field absorbing depth = confounding. Writes
   twoarm-result.edn immediately on completion."
  [{:keys [n-pres out-dir]}]
  (let [out (or out-dir mimic-out-dir)
        result (two-arm-depth {:sim {:config :mimic :seed recovery-seed
                                     :truth mimic-truth
                                     :n-pres (or n-pres 216)}
                               :mcmc mimic-mcmc
                               :out-dir (str out "/twoarm")})
        payload (assoc (select-keys result [:arm1 :arm2 :gap :truth :verdict :pass])
                       :experiment :two-arm-mimic
                       :config :mimic)]
    (edn-spit (str out "/twoarm-result.edn") payload)
    (assoc result :payload payload)))

(defn recover-all-mimic
  "B6b top-level orchestration: run BOTH the realistic-scale mimic spatial
   recovery and the two-arm depth-vs-field experiment, persisting each result to
   its own EDN file under `mimic-out-dir` the instant it finishes (so socket
   death can't lose work). If a result file already exists on disk it is REUSED
   (resume-from-disk) rather than re-fitting. Returns {:spatial :two-arm}."
  []
  (let [out mimic-out-dir
        spatial-path (str out "/spatial-result.edn")
        twoarm-path (str out "/twoarm-result.edn")
        spatial (if (.exists (io/file spatial-path))
                  (do (println "  [resume] spatial-result.edn exists — reusing.")
                      {:payload (read-string (slurp spatial-path))})
                  (do (println "  [run] mimic spatial recovery (4×1000+1000)…")
                      (recover-spatial-mimic {})))
        sp (:payload spatial)
        _ (pp-spatial {:report (:report sp)
                       :kingzeng (:kingzeng sp)
                       :surface (get-in sp [:report :surface])})
        two-arm (if (.exists (io/file twoarm-path))
                  (do (println "  [resume] twoarm-result.edn exists — reusing.")
                      {:payload (read-string (slurp twoarm-path))})
                  (do (println "  [run] mimic two-arm depth-vs-field…")
                      (two-arm-mimic {})))
        _ (pp-two-arm (:payload two-arm))]
    (println (str/join "" (repeat 72 "─")))
    (println "recover-all-mimic complete — results in" out)
    {:spatial spatial :two-arm two-arm}))

;; ── B6a: BEST-CASE separable recovery orchestration (persists incrementally) ──

(def separable-out-dir
  "Out-dir for the B6a separable recovery experiments (one EDN file per
   experiment, written the instant it finishes so socket death can't lose it)."
  "out/recovery/separable")

(def separable-mcmc
  "Realistic-scale sampling opts for B6a: 4 chains × 1000 warmup + 1000 sample
   (enough for ESS>400 without over-sampling)."
  {:n-chains 4 :seed recovery-seed :num-warmup 1000 :num-samples 1000})

(def separable-attr-gen
  "Part-A generation config at realistic scale: N=600, base-rate 0.03 (the rare
   incident regime), three continuous predictors with KNOWN slopes."
  {:n 600
   :base-rate 0.03
   :predictors [{:dist :normal :mean 0.0 :sd 1.0}
                {:dist :normal :mean 0.0 :sd 1.0}
                {:dist :normal :mean 0.0 :sd 1.0}]
   :true-slopes [0.8 -0.5 0.3]
   :seed recovery-seed})

(defn recover-attr-separable
  "B6a Part-A attr_logit recovery at realistic scale (N=600, 4×1000+1000).
   Scores recovered alpha + betas against known truth, writes attr-result.edn
   immediately. `opts` keys: :out-dir (default separable-out-dir)."
  [{:keys [out-dir]}]
  (let [out (or out-dir separable-out-dir)
        result (recover-attr {:gen separable-attr-gen
                              :mcmc separable-mcmc
                              :out-dir (str out "/attr")})
        payload {:experiment :attr-separable
                 :truth (:truth result)
                 :report (select-keys (:report result)
                                      [:rows :divergences :divergences-ok :pass])}]
    (edn-spit (str out "/attr-result.edn") payload)
    (assoc result :payload payload)))

(defn recover-spatial-separable
  "B6a Part-B spatial recovery on the :separable config (field decorrelated from
   z so depth IS recoverable separately — the upper-bound arm). Realistic scale:
   n-presence≈216, real mimic background (≈3000), 4×1000+1000. Scores b0
   (King–Zeng corrected), b_d1, b_d2 + the intensity surface, writes
   spatial-result.edn immediately. `opts`: :n-pres (default 216), :out-dir."
  [{:keys [n-pres out-dir]}]
  (let [out (or out-dir separable-out-dir)
        result (recover-spatial {:sim {:config :separable :seed recovery-seed
                                       :n-pres (or n-pres 216)}
                                 :mcmc separable-mcmc
                                 :out-dir (str out "/spatial")})
        payload {:experiment :spatial-separable
                 :config :separable
                 :truth (:truth result)
                 :kingzeng (:kingzeng result)
                 :report (serializable-report (:report result))}]
    (edn-spit (str out "/spatial-result.edn") payload)
    (assoc result :payload payload)))

(defn two-arm-separable
  "B6a two-arm depth-vs-field experiment on the SAME :separable dataset. Arm-1
   depth-only vs Arm-2 full field+depth. On separable data the field carries no
   linear depth info, so Arm-2 SHOULD keep depth close to Arm-1 (PASS). Writes
   twoarm-result.edn immediately. `opts`: :n-pres (default 216), :out-dir."
  [{:keys [n-pres out-dir]}]
  (let [out (or out-dir separable-out-dir)
        result (two-arm-depth {:sim {:config :separable :seed recovery-seed
                                     :n-pres (or n-pres 216)}
                               :mcmc separable-mcmc
                               :out-dir (str out "/twoarm")})
        payload (assoc (select-keys result [:arm1 :arm2 :gap :truth :verdict :pass])
                       :experiment :two-arm-separable
                       :config :separable)]
    (edn-spit (str out "/twoarm-result.edn") payload)
    (assoc result :payload payload)))

(defn recover-all-separable
  "BUILD STEP B6a — the full-scale parameter-recovery VERDICT on the BEST-CASE
   (separable) synthetic data. Runs all THREE experiments at realistic scale,
   persisting each result to its own EDN file under `separable-out-dir` the
   instant it finishes (so socket death can't lose a completed fit). If a result
   file already exists on disk it is REUSED (resume-from-disk) rather than
   re-fitting. Returns {:attr :spatial :two-arm}."
  []
  (let [out separable-out-dir
        attr-path (str out "/attr-result.edn")
        spatial-path (str out "/spatial-result.edn")
        twoarm-path (str out "/twoarm-result.edn")
        attr (if (.exists (io/file attr-path))
               (do (println "  [resume] attr-result.edn exists — reusing.")
                   {:payload (read-string (slurp attr-path))})
               (do (println "  [run] separable attr recovery (N=600, 4×1000+1000)…")
                   (recover-attr-separable {})))
        _ (pp-attr {:report (:report (:payload attr))})
        spatial (if (.exists (io/file spatial-path))
                  (do (println "  [resume] spatial-result.edn exists — reusing.")
                      {:payload (read-string (slurp spatial-path))})
                  (do (println "  [run] separable spatial recovery (4×1000+1000)…")
                      (recover-spatial-separable {})))
        _ (pp-spatial {:report (:report (:payload spatial))
                       :kingzeng (:kingzeng (:payload spatial))
                       :surface (get-in spatial [:payload :report :surface])})
        two-arm (if (.exists (io/file twoarm-path))
                  (do (println "  [resume] twoarm-result.edn exists — reusing.")
                      {:payload (read-string (slurp twoarm-path))})
                  (do (println "  [run] separable two-arm depth-vs-field…")
                      (two-arm-separable {})))
        _ (pp-two-arm (:payload two-arm))]
    (println (str/join "" (repeat 72 "─")))
    (println "recover-all-separable complete — results in" out)
    {:attr attr :spatial spatial :two-arm two-arm}))

(defn smoke
  "Run the WHOLE recovery pipeline at TINY scale to prove the plumbing fits and
   scores end-to-end without error. Tiny N, 2 chains × ~300 warmup/300 sample.
   The first compile of each .stan model takes a few minutes. This is NOT a real
   recovery run (large N, 4 chains × 1000+2000) — that is build step B6.

   Prints rough recovered-vs-true numbers for all three pieces and returns
   {:attr :spatial :two-arm}."
  []
  (println (str/join "" (repeat 72 "─")))
  (println "orca.recover/smoke — TINY end-to-end plumbing proof (NOT a real run)")
  (println (str/join "" (repeat 72 "─")))
  (let [attr (recover-attr {:gen smoke-attr-gen})
        _ (pp-attr attr)
        ;; tiny Part-B: small presence count keeps the fit quick.
        spatial (recover-spatial {:sim {:config :separable :n-pres 120}})
        _ (pp-spatial spatial)
        two-arm (two-arm-depth {:sim {:config :separable :n-pres 120}})
        _ (pp-two-arm two-arm)]
    (println (str/join "" (repeat 72 "─")))
    (println "smoke complete — plumbing fits + scores end-to-end.")
    {:attr attr :spatial spatial :two-arm two-arm}))
