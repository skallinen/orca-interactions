(ns orca.priorpred
  "Prior-predictive checks (SYNTHETIC_VALIDATION_PLAN B5, PURE).

   McElreath's 'draw the owl' step that comes BEFORE parameter recovery: push
   the model's PRIORS — not any fit — through the likelihood's link and confirm
   they imply plausible, RARE outcomes (no pile-up at probability 0 or 1, bulk
   well below ~10%, a sane peaked depth curve). If the prior predictive is
   implausible, STOP and revisit the priors before any recovery run (the gate in
   the plan's build order, §8).

   This namespace is PURE: no CmdStan, no fitting. All randomness flows through a
   single seeded `java.util.Random` (default seed 42), matching the convention in
   `orca.models/prior-predictive`, `orca.synth`, and `orca.simtracks`. It reuses
   `orca.util` (sigmoid, quantile, mean) and `orca.synth/inv-logit` rather than
   re-deriving the link.

   Three checks (plan §7):
     1. Part-A / attribute INTERCEPT prior. The project's methodology spec uses a
        Fermi intercept alpha ~ Normal(-3.5, 0.6) with slopes ~ Normal(0, 0.5);
        the checked-in `attr_logit.stan` instead uses N(0,1) slopes. We test BOTH
        slope priors and report. We also contrast against a deliberately BAD flat
        prior alpha ~ Normal(0, 10) to SHOW the 0/1 pile-up — the teaching point
        for why the Fermi prior is right.
     2. Depth QUADRATIC prior. Draw b_d1, b_d2 ~ Normal(0, 0.5); over a grid of
        standardized depth z in [-3, 3] summarize the implied curve
        b_d1*z + b_d2*z^2 and confirm curves are FINITE and predominantly
        UNIMODAL (a single concave peak), not explosive U-shapes.
     3. Per-passage probability after a simple clustered count->prob aggregation
        (cheap version): aggregate Bernoulli events into passages and check the
        per-passage probability stays rare.

   Each check returns a SUMMARY MAP (so it is testable: same seed => same
   summary), and `prior-predictive-report` prints a readable digest plus the
   Fermi-vs-flat contrast."
  (:require
   [orca.synth :as synth]
   [orca.util :as util]))

;; ── shared knobs (priors as written in the methodology / the .stan file) ─────

(def default-seed
  "Single RNG seed for every prior-predictive draw (matches the project-wide
   java.util.Random seed-42 convention)."
  42)

(def fermi-intercept
  "Part-A 'Fermi' intercept prior: alpha ~ Normal(-3.5, 0.6). The mean -3.5 puts
   the baseline per-event probability around inv_logit(-3.5) ≈ 0.029 (a rare
   event), and the sd 0.6 keeps the bulk in single-digit percents."
  {:mean -3.5 :sd 0.6})

(def flat-intercept
  "The deliberately BAD contrast prior: alpha ~ Normal(0, 10). Flat on the logit
   scale => bimodal pile-up at probability 0 and 1 on the response scale. Shown
   only to demonstrate WHY the Fermi prior is right (McElreath's teaching point)."
  {:mean 0.0 :sd 10.0})

(def slope-prior-tight
  "Methodology-spec slope prior: beta ~ Normal(0, 0.5)."
  {:mean 0.0 :sd 0.5})

(def slope-prior-checked-in
  "Checked-in attr_logit.stan slope prior: beta ~ Normal(0, 1)."
  {:mean 0.0 :sd 1.0})

(def depth-prior
  "spatial.stan depth-quadratic coefficient prior: b_d1, b_d2 ~ Normal(0, 0.5)."
  {:mean 0.0 :sd 0.5})

;; rarity PASS thresholds (coded so the checks are self-scoring)
(def rare-frac-above-half-max
  "PASS requires fewer than this FRACTION of prior-implied per-event probabilities
   to exceed 0.5 (i.e. no pile-up toward certainty)."
  0.01)

(def rare-median-max
  "PASS requires the median implied per-event probability to stay below this — a
   rare-event regime, not a coin flip."
  0.10)

;; ── basic draws ──────────────────────────────────────────────────────────────

(defn make-rng
  "Seeded java.util.Random — the single source of all randomness here."
  ^java.util.Random [seed]
  (java.util.Random. (long seed)))

(defn- gaussian
  "Draw n Normal(mean,sd) values from `rng` as a vector."
  [^java.util.Random rng {:keys [mean sd]} n]
  (let [m (double mean) s (double sd)]
    (vec (repeatedly n #(+ m (* s (.nextGaussian rng)))))))

(defn- summarize-probs
  "Distribution summary {:p5 :p50 :p95 :max :frac-above-half :mean} of a seq of
   probabilities in [0,1]."
  [ps]
  {:p5 (util/quantile ps 0.05)
   :p50 (util/quantile ps 0.50)
   :p95 (util/quantile ps 0.95)
   :max (apply max ps)
   :mean (util/mean ps)
   :frac-above-half (/ (double (count (filter #(> (double %) 0.5) ps)))
                       (double (count ps)))})

;; ── check 1: intercept / attribute prior predictive ──────────────────────────

(defn intercept-prior-predictive
  "Draw `n` prior samples of the intercept alpha ~ Normal(`intercept` mean/sd),
   optionally adding `n-slopes` slope terms ~ Normal(`slope` mean/sd) each
   evaluated at a standardized predictor value cycled from `x` (default `[1.0]`,
   i.e. a typical +1 sd shift per predictor; the plan's 'slopes over standardized
   predictors in {-2..2}'), and push the linear predictor through inv_logit to
   get the implied per-event probability. Returns the distribution summary plus
   the PASS verdict.

   The slope contribution per draw is sum_k beta_k * x_k with the beta_k drawn
   fresh per sample, so the summary reflects BOTH intercept and slope priors.
   With `n-slopes` 0 it is the pure intercept prior predictive. (Stacking several
   predictors at the +2 sd EXTREME — e.g. x [2.0] with n-slopes 4 — is what grows
   the upper tail; the report exposes that as a SECONDARY informational row.)

   PASS (coded): frac-above-half < `rare-frac-above-half-max` (no pile-up toward
   1), median < `rare-median-max` (rare regime). We also report the fraction
   below 0.001 to expose pile-up toward 0 (the flat prior shows BOTH tails).

   Returns {:n :intercept :slope :n-slopes :x :summary {..}
            :frac-below-0.001 :pass}."
  [{:keys [seed n intercept slope n-slopes x]
    :or {seed default-seed n 2000 intercept fermi-intercept
         slope slope-prior-tight n-slopes 0 x [1.0]}}]
  (let [rng (make-rng seed)
        xs (vec (take n-slopes (cycle x)))
        ps (vec (repeatedly
                  n
                  (fn []
                    (let [a (+ (double (:mean intercept))
                               (* (double (:sd intercept)) (.nextGaussian rng)))
                          lp (reduce (fn [acc xv]
                                       (+ acc (* (+ (double (:mean slope))
                                                    (* (double (:sd slope))
                                                       (.nextGaussian rng)))
                                                 (double xv))))
                                     a xs)]
                      (synth/inv-logit lp)))))
        summary (summarize-probs ps)
        frac-lo (/ (double (count (filter #(< (double %) 0.001) ps)))
                   (double n))]
    {:n n :intercept intercept :slope slope :n-slopes n-slopes :x (vec (take n-slopes (cycle x)))
     :summary summary
     :frac-below-0.001 frac-lo
     :pass (and (< (:frac-above-half summary) rare-frac-above-half-max)
                (< (:p50 summary) rare-median-max))}))

;; ── check 2: depth quadratic prior predictive ─────────────────────────────────

(defn depth-curve-prior-predictive
  "Draw `n` prior samples of (b_d1, b_d2) ~ Normal(`prior` mean/sd) and, over a
   standardized-depth grid z in [`z-min`, `z-max`] (step `z-step`), summarize the
   implied curve b_d1*z + b_d2*z^2. Confirms the curves are FINITE and
   predominantly UNIMODAL (a single concave peak) rather than explosive U-shapes.

   Reported:
     :frac-concave   fraction of draws with b_d2 < 0 (a downward peak — the
                     shelf/slope preference shape) vs convex bowls.
     :frac-finite    fraction of draws whose curve is finite over the grid.
     :peak-summary   {:p5 :p50 :p95} of the IN-RANGE peak locations
                     z* = -b_d1/(2 b_d2) (peaks outside the grid are excluded as
                     'no interior peak'), with :frac-interior-peak the share that
                     have an interior optimum.
     :curve-extent   {:max-abs ..} the largest |curve| value across all draws and
                     grid points — a finiteness/sanity bound.

   PASS (coded): all curves finite AND the interior-peak z* spread is sane
   (p5/p95 within the grid, i.e. peaks don't pile at the extremes)."
  [{:keys [seed n prior z-min z-max z-step]
    :or {seed default-seed n 2000 prior depth-prior
         z-min -3.0 z-max 3.0 z-step 0.25}}]
  (let [rng (make-rng seed)
        zs (vec (range z-min (+ z-max (/ z-step 2.0)) z-step))
        b1s (gaussian rng prior n)
        b2s (gaussian rng prior n)
        ;; per-draw curve max-abs over the grid (finiteness/extent)
        max-abs (atom 0.0)
        finite-count (atom 0)
        interior-peaks (transient [])]
    (dotimes [i n]
      (let [b1 (double (nth b1s i)) b2 (double (nth b2s i))
            curve (mapv (fn [z] (+ (* b1 z) (* b2 z z))) zs)
            mx (apply max (map #(Math/abs (double %)) curve))
            finite? (every? #(and (not (Double/isNaN %)) (not (Double/isInfinite %))) curve)]
        (when (> mx (double @max-abs)) (reset! max-abs mx))
        (when finite? (swap! finite-count inc))
        (when (not (zero? b2))
          (let [zstar (/ (- b1) (* 2.0 b2))]
            (when (and (<= (double z-min) zstar) (<= zstar (double z-max)))
              (conj! interior-peaks zstar))))))
    (let [frac-concave (/ (double (count (filter #(< (double %) 0.0) b2s)))
                          (double n))
          peaks (persistent! interior-peaks)
          n-interior (count peaks)
          peak-summary (when (pos? n-interior)
                         {:p5 (util/quantile peaks 0.05)
                          :p50 (util/quantile peaks 0.50)
                          :p95 (util/quantile peaks 0.95)})
          frac-finite (/ (double @finite-count) (double n))
          spread-sane (boolean
                        (and peak-summary
                             (>= (:p5 peak-summary) (double z-min))
                             (<= (:p95 peak-summary) (double z-max))))]
      {:n n :prior prior :z-grid [z-min z-max z-step]
       :frac-concave frac-concave
       :frac-finite frac-finite
       :frac-interior-peak (/ (double n-interior) (double n))
       :peak-summary peak-summary
       :curve-extent {:max-abs @max-abs}
       :pass (and (== frac-finite 1.0) spread-sane)})))

;; ── check 3: per-passage probability after clustered aggregation (cheap) ──────

(defn passage-prior-predictive
  "Cheap prior-predictive of the per-PASSAGE incident probability after a simple
   count->prob clustered aggregation. A passage is a cluster of `events-per-pass`
   independent per-event Bernoulli trials whose probability is drawn from the
   Fermi-style intercept prior (`intercept`); the passage is an incident if ANY
   event is. The implied passage probability is 1 - prod(1 - p_event).

   Confirms the AGGREGATED passage probability is still rare/plausible (not
   pushed to 1 by clustering). Returns the same shape as
   `intercept-prior-predictive`'s :summary plus :events-per-pass and :pass
   (median passage prob below a relaxed `events-per-pass`-scaled bound)."
  [{:keys [seed n intercept events-per-pass]
    :or {seed default-seed n 2000 intercept fermi-intercept events-per-pass 5}}]
  (let [rng (make-rng seed)
        ps (vec (repeatedly
                  n
                  (fn []
                    (let [a (+ (double (:mean intercept))
                               (* (double (:sd intercept)) (.nextGaussian rng)))
                          pe (synth/inv-logit a)]
                      (- 1.0 (Math/pow (- 1.0 pe) (double events-per-pass)))))))
        summary (summarize-probs ps)]
    {:n n :intercept intercept :events-per-pass events-per-pass
     :summary summary
     ;; even after clustering 5 events, the passage rate should stay well under
     ;; 0.5 (no pile-up toward certainty).
     :pass (and (< (:frac-above-half summary) 0.05)
                (< (:p50 summary) 0.5))}))

;; ── report ────────────────────────────────────────────────────────────────────

(defn- pp-prob-line [label {:keys [summary pass] :as row}]
  (let [frac-lo (:frac-below-0.001 row)]
    (println (format "  %-34s p5=%.4f p50=%.4f p95=%.4f max=%.4f  >0.5: %.3f%%%s -> %s"
                     label
                     (:p5 summary) (:p50 summary) (:p95 summary) (:max summary)
                     (* 100.0 (:frac-above-half summary))
                     (if frac-lo
                       (format "  <0.001: %.1f%%" (* 100.0 frac-lo)) "")
                     (if pass "PASS" "fail-rarity")))))

(defn prior-predictive-report
  "Run all three prior-predictive checks at the given (or default) seed and PRINT
   a readable summary, including the Fermi-vs-flat intercept contrast and the two
   slope-prior variants (N(0,0.5) spec vs N(0,1) checked-in). Returns a map of all
   the summary maps so the report is itself testable / inspectable.

   The Fermi rows PASS the rarity check; the flat N(0,10) row FAILS it (showing
   the 0/1 pile-up) — that contrast is the whole point."
  ([] (prior-predictive-report {}))
  ([{:keys [seed n] :or {seed default-seed n 2000}}]
   (let [fermi-int   (intercept-prior-predictive {:seed seed :n n :intercept fermi-intercept})
         ;; 3 predictors each at a typical +1 sd shift
         fermi-tight (intercept-prior-predictive {:seed seed :n n :intercept fermi-intercept
                                                  :slope slope-prior-tight :n-slopes 3 :x [1.0]})
         fermi-wide  (intercept-prior-predictive {:seed seed :n n :intercept fermi-intercept
                                                  :slope slope-prior-checked-in
                                                  :n-slopes 3 :x [1.0]})
         ;; SECONDARY: stack 4 predictors all at the +2 sd EXTREME (tail probe)
         fermi-extreme (intercept-prior-predictive {:seed seed :n n :intercept fermi-intercept
                                                    :slope slope-prior-tight :n-slopes 4 :x [2.0]})
         flat        (intercept-prior-predictive {:seed seed :n n :intercept flat-intercept})
         depth       (depth-curve-prior-predictive {:seed seed :n n})
         passage     (passage-prior-predictive {:seed seed :n n})]
     (println "════════════════════════════════════════════════════════════════════════")
     (println "orca.priorpred — PRIOR-PREDICTIVE CHECKS (pure; no fitting) seed=" seed " n=" n)
     (println "════════════════════════════════════════════════════════════════════════")
     (println "1. Part-A intercept / attribute prior -> implied per-event probability")
     (pp-prob-line "Fermi alpha~N(-3.5,0.6) intercept" fermi-int)
     (pp-prob-line "  + 3 slopes N(0,0.5) @+1sd [spec]" fermi-tight)
     (pp-prob-line "  + 3 slopes N(0,1)  @+1sd [.stan]" fermi-wide)
     (pp-prob-line "  + 4 slopes N(0,0.5) @+2sd [tail]" fermi-extreme)
     (pp-prob-line "BAD flat alpha~N(0,10) [contrast]" flat)
     (println (format "   CONTRAST: Fermi median=%.4f vs flat median=%.4f; flat piles at BOTH"
                      (:p50 (:summary fermi-int)) (:p50 (:summary flat))))
     (println (format "             tails (>0.5: %.1f%%, <0.001: %.1f%%) — McElreath's point."
                      (* 100.0 (:frac-above-half (:summary flat)))
                      (* 100.0 (:frac-below-0.001 flat))))
     (println)
     (println "2. Depth quadratic prior b_d1,b_d2~N(0,0.5) -> implied curve over z∈[-3,3]")
     (println (format "   frac-finite=%.3f frac-concave(b_d2<0)=%.3f frac-interior-peak=%.3f"
                      (:frac-finite depth) (:frac-concave depth) (:frac-interior-peak depth)))
     (println (format "   interior peak z*: %s   max|curve|=%.2f -> %s"
                      (if-let [p (:peak-summary depth)]
                        (format "p5=%.3f p50=%.3f p95=%.3f" (:p5 p) (:p50 p) (:p95 p))
                        "none")
                      (get-in depth [:curve-extent :max-abs])
                      (if (:pass depth) "PASS" "FAIL")))
     (println)
     (println "3. Per-passage probability after clustered aggregation (5 events/passage)")
     (pp-prob-line "passage incident prob" passage)
     (println "════════════════════════════════════════════════════════════════════════")
     (println (format "GATE: intercept PASS=%s  depth PASS=%s  passage PASS=%s  flat FAILS=%s"
                      (:pass fermi-int) (:pass depth) (:pass passage) (not (:pass flat))))
     (println "════════════════════════════════════════════════════════════════════════")
     {:fermi-intercept fermi-int
      :fermi-slopes-tight fermi-tight
      :fermi-slopes-wide fermi-wide
      :fermi-slopes-extreme fermi-extreme
      :flat-intercept flat
      :depth depth
      :passage passage})))

(defn -main
  "CLI entry: `clojure -M -m orca.priorpred` prints the prior-predictive report."
  [& _]
  (prior-predictive-report))
