(ns orca.priorpred-test
  "Pure deterministic tests for the prior-predictive checks (no CmdStan).

   Pins: same seed => same summary (determinism); the Fermi intercept prior
   PASSES the rarity check (rare bulk, no pile-up); the deliberately BAD flat
   N(0,10) prior FAILS it (shows pile-up at BOTH 0 and 1) — the McElreath
   contrast; and the depth-quadratic prior implies finite, mostly-concave,
   sane-spread curves."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.priorpred :as pp]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-9))

(deftest determinism-test
  (testing "same seed => identical intercept summary"
    (let [a (pp/intercept-prior-predictive {:seed 42 :n 1000})
          b (pp/intercept-prior-predictive {:seed 42 :n 1000})]
      (is (= (:summary a) (:summary b)))
      (is (close? (:frac-below-0.001 a) (:frac-below-0.001 b)))))
  (testing "same seed => identical depth summary"
    (let [a (pp/depth-curve-prior-predictive {:seed 42 :n 1000})
          b (pp/depth-curve-prior-predictive {:seed 42 :n 1000})]
      (is (= (dissoc a :prior) (dissoc b :prior)))))
  (testing "different seed => different draws (sanity)"
    (let [a (pp/intercept-prior-predictive {:seed 42 :n 1000})
          b (pp/intercept-prior-predictive {:seed 1 :n 1000})]
      (is (not= (:summary a) (:summary b))))))

(deftest fermi-passes-rarity-test
  (testing "Fermi intercept prior implies a RARE-event distribution and PASSES"
    (let [r (pp/intercept-prior-predictive {:seed 42 :n 2000 :intercept pp/fermi-intercept})
          {:keys [p50 frac-above-half] mx :max} (:summary r)]
      (is (:pass r))
      ;; median well into the rare regime (around inv_logit(-3.5) ≈ 0.03)
      (is (< p50 pp/rare-median-max))
      ;; essentially no draws pile up toward certainty
      (is (< frac-above-half pp/rare-frac-above-half-max))
      ;; and the maximum probability is far from 1
      (is (< mx 0.5))))
  (testing "adding spec N(0,0.5) slopes at +1 sd keeps it rare + passing"
    (let [r (pp/intercept-prior-predictive {:seed 42 :n 2000 :n-slopes 3 :x [1.0]
                                            :slope pp/slope-prior-tight})]
      (is (:pass r))))
  (testing "the wider checked-in N(0,1) slopes keep a RARE median but widen the tail"
    ;; The FINDING: the checked-in attr_logit.stan N(0,1) slopes are wider than
    ;; the spec N(0,0.5); with 3 predictors at +1 sd the median stays rare
    ;; (~0.03) but ~3% of draws cross 0.5, slightly over the strict no-pile-up
    ;; gate — exactly why the methodology spec prefers the tighter N(0,0.5).
    (let [r (pp/intercept-prior-predictive {:seed 42 :n 2000 :n-slopes 3 :x [1.0]
                                            :slope pp/slope-prior-checked-in})
          tight (pp/intercept-prior-predictive {:seed 42 :n 2000 :n-slopes 3 :x [1.0]
                                                :slope pp/slope-prior-tight})]
      ;; median still rare under BOTH slope priors
      (is (< (:p50 (:summary r)) pp/rare-median-max))
      ;; the wider prior has a fatter upper tail than the tight spec prior
      (is (> (:frac-above-half (:summary r))
             (:frac-above-half (:summary tight))))))
  (testing "stacking 4 predictors at the +2 sd EXTREME grows the upper tail"
    ;; the secondary tail probe: this is informational, NOT a PASS — it shows
    ;; how the slopes widen the tail when several predictors are maxed at once.
    (let [r (pp/intercept-prior-predictive {:seed 42 :n 2000 :n-slopes 4 :x [2.0]
                                            :slope pp/slope-prior-tight})]
      (is (false? (:pass r)))
      (is (> (:frac-above-half (:summary r)) 0.01)))))

(deftest flat-prior-fails-test
  (testing "the BAD flat N(0,10) prior FAILS the rarity check (0/1 pile-up)"
    (let [r (pp/intercept-prior-predictive {:seed 42 :n 2000 :intercept pp/flat-intercept})
          {:keys [frac-above-half]} (:summary r)]
      (is (false? (:pass r)))
      ;; flat-on-logit piles up toward 1: a large fraction exceeds 0.5
      (is (> frac-above-half 0.30))
      ;; AND piles up toward 0: a large fraction is below 0.001
      (is (> (:frac-below-0.001 r) 0.20)))))

(deftest depth-curve-test
  (testing "depth-quadratic prior implies finite, sane-spread curves and PASSES"
    (let [r (pp/depth-curve-prior-predictive {:seed 42 :n 2000})]
      (is (:pass r))
      ;; every curve is finite over the grid
      (is (== 1.0 (:frac-finite r)))
      ;; symmetric N(0,0.5) prior => about half the curves are concave
      (is (< 0.4 (:frac-concave r) 0.6))
      ;; interior peaks have a sane spread inside the grid
      (let [{:keys [p5 p95]} (:peak-summary r)]
        (is (>= p5 -3.0))
        (is (<= p95 3.0))))))

(deftest passage-aggregation-test
  (testing "clustered per-passage probability stays rare and passes"
    (let [r (pp/passage-prior-predictive {:seed 42 :n 2000 :events-per-pass 5})]
      (is (:pass r))
      (is (< (:p50 (:summary r)) 0.5)))))
