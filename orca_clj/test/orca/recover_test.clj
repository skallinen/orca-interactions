(ns orca.recover-test
  "Pure-function tests for the recovery SCORING logic (no CmdStan). Pins the
   coverage / bias-z gate, the King–Zeng intercept offset arithmetic, the
   Pearson-corr helper, the z* depth-peak formula, and the per-parameter PASS
   rule. The fit-driving fns (recover-attr/spatial, two-arm-depth, smoke) need
   CmdStan + the nix shell and are exercised via `smoke`, not here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.recover :as rec]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-9))

(deftest coverage-test
  (testing "truth inside the central interval is covered"
    (let [{:keys [covered z bias-ok]} (rec/coverage 0.0 1.0 -1.0 1.0 0.5)]
      (is covered)
      ;; z = (mean - truth)/sd = (0.0 - 0.5)/1.0 = -0.5
      (is (close? -0.5 z))
      (is bias-ok)))
  (testing "truth outside the interval is not covered"
    (is (false? (:covered (rec/coverage 0.0 1.0 -1.0 1.0 2.0)))))
  (testing "bias z-score is (mean - truth)/sd"
    (is (close? 2.0 (:z (rec/coverage 1.0 0.5 0.0 2.0 0.0)))))
  (testing "bias gate at the |z|<2 boundary"
    ;; z = 2.0 exactly must FAIL (strict <)
    (is (false? (:bias-ok (rec/coverage 2.0 1.0 0.0 4.0 0.0))))
    ;; z = 1.999 passes
    (is (true? (:bias-ok (rec/coverage 1.999 1.0 0.0 4.0 0.0)))))
  (testing "zero sd: exact match is unbiased, mismatch is infinite z"
    (is (true? (:bias-ok (rec/coverage 3.0 0.0 3.0 3.0 3.0))))
    (is (false? (:bias-ok (rec/coverage 3.0 0.0 3.0 3.0 4.0))))))

(deftest mcmc-ok-test
  (testing "passes when all diagnostics clear the gate"
    (is (true? (rec/mcmc-ok? {:rhat 1.005 :ess-bulk 500.0 :ess-tail 450.0}))))
  (testing "fails on rhat at/above 1.01"
    (is (false? (rec/mcmc-ok? {:rhat 1.01 :ess-bulk 500.0 :ess-tail 500.0})))
    (is (false? (rec/mcmc-ok? {:rhat 1.02 :ess-bulk 500.0 :ess-tail 500.0}))))
  (testing "fails on ess at/below 400"
    (is (false? (rec/mcmc-ok? {:rhat 1.0 :ess-bulk 400.0 :ess-tail 500.0})))
    (is (false? (rec/mcmc-ok? {:rhat 1.0 :ess-bulk 500.0 :ess-tail 400.0}))))
  (testing "passes just above the ess boundary"
    (is (true? (rec/mcmc-ok? {:rhat 1.0 :ess-bulk 400.1 :ess-tail 400.1})))))

(deftest kingzeng-test
  (testing "balanced design (tau=ybar) leaves zero offset"
    (let [{:keys [offset b0-corrected]} (rec/kingzeng-intercept -1.0 0.5 0.5)]
      (is (close? 0.0 offset))
      (is (close? -1.0 b0-corrected))))
  (testing "worked closed-form example"
    ;; tau=0.03, ybar=0.5 ->
    ;;   offset = ln( ((1-0.03)/0.03) * (0.5/0.5) ) = ln(0.97/0.03) = ln(32.3333..)
    (let [tau 0.03 ybar 0.5
          expected (Math/log (* (/ (- 1.0 tau) tau) (/ ybar (- 1.0 ybar))))
          {:keys [offset b0-corrected]} (rec/kingzeng-intercept -2.0 tau ybar)]
      (is (close? expected offset))
      (is (close? (- -2.0 expected) b0-corrected))))
  (testing "subtracting the offset recovers a population intercept"
    ;; if b0_hat = true_b0 + offset, the correction returns true_b0.
    (let [true-b0 -3.5 tau 0.02 ybar 0.5
          offset (Math/log (* (/ (- 1.0 tau) tau) (/ ybar (- 1.0 ybar))))
          {:keys [b0-corrected]} (rec/kingzeng-intercept (+ true-b0 offset) tau ybar)]
      (is (close? true-b0 b0-corrected))))
  (testing "tau = ybar collapses the offset to zero regardless of the value"
    ;; when the sample fraction already equals the population fraction there is
    ;; no oversampling, so the correction is a no-op (offset 0). This is exactly
    ;; the honest fallback recover-spatial uses when the truth exposes no base
    ;; rate (tau := ybar), making the REPORTED b0_corrected == b0_hat.
    (doseq [p [0.0671 0.2 0.4 0.5 0.9]]
      (let [{:keys [offset b0-corrected]} (rec/kingzeng-intercept -1.78 p p)]
        (is (close? 0.0 offset))
        (is (close? -1.78 b0-corrected)))))
  (testing "the offset depends ONLY on tau and ybar, not on b0_hat"
    ;; sanity: the King–Zeng offset is a property of the sampling design, so two
    ;; different fitted intercepts share the same offset for fixed (tau, ybar).
    (let [tau 0.067 ybar 0.5
          o1 (:offset (rec/kingzeng-intercept -1.0 tau ybar))
          o2 (:offset (rec/kingzeng-intercept +3.7 tau ybar))]
      (is (close? o1 o2)))))

(deftest spatial-b0-excluded-from-verdict-test
  (testing "spatial-truth-params scores ONLY the depth coeffs — b0 is excluded"
    ;; b0 is a use-availability log-intensity intercept (not a base-rate logit)
    ;; and the presence-thinning never uses it, so it is NOT identified as a base
    ;; rate (King & Zeng) and must NOT be in the pass/fail truth map.
    (let [tp (#'rec/spatial-truth-params {:b0 -1.0 :b1 1.3 :b2 -1.6})]
      (is (= #{"b_d1" "b_d2"} (set (keys tp))))
      (is (not (contains? tp "b0")))
      (is (not (contains? tp "b0-corrected")))
      (is (close? 1.3 (get tp "b_d1")))
      (is (close? -1.6 (get tp "b_d2"))))))

(deftest pearson-test
  (testing "perfect positive correlation"
    (is (close? 1.0 (rec/pearson [1 2 3 4] [2 4 6 8]))))
  (testing "perfect negative correlation"
    (is (close? -1.0 (rec/pearson [1 2 3 4] [4 3 2 1]))))
  (testing "constant series -> 0 (no variance)"
    (is (close? 0.0 (rec/pearson [1 1 1 1] [1 2 3 4]))))
  (testing "known intermediate value (matches the closed form)"
    (let [xs [1 2 3 4] ys [1 3 2 5]
          mx 2.5 my 2.75
          dx (map #(- % mx) xs) dy (map #(- % my) ys)
          sxy (reduce + (map * dx dy))
          expected (/ sxy (Math/sqrt (* (reduce + (map #(* % %) dx))
                                        (reduce + (map #(* % %) dy)))))]
      (is (close? expected (rec/pearson xs ys))))))

(deftest depth-peak-test
  (testing "z* = -b_d1/(2 b_d2)"
    (is (close? 0.40625 (rec/depth-peak 1.3 -1.6)))
    (is (close? -0.5 (rec/depth-peak 2.0 2.0))))
  (testing "b_d2 = 0 has no peak (NaN)"
    (is (Double/isNaN (rec/depth-peak 1.0 0.0)))))

(deftest score-recovery-test
  (testing "a clean fit passes every per-param row and overall"
    (let [fit {:summary {"a" {:mean 0.0 :sd 1.0 :eti-low -1.6 :eti-high 1.6
                              :rhat 1.001 :ess-bulk 800.0 :ess-tail 700.0}
                         "b" {:mean 2.0 :sd 0.5 :eti-low 1.2 :eti-high 2.8
                              :rhat 1.002 :ess-bulk 900.0 :ess-tail 900.0}}
               :divergences 0}
          {:keys [rows pass divergences-ok]} (rec/score-recovery
                                              fit {"a" 0.1 "b" 1.9})]
      (is divergences-ok)
      (is pass)
      (is (every? :pass rows))))
  (testing "any divergence fails every row (fit-level gate)"
    (let [fit {:summary {"a" {:mean 0.0 :sd 1.0 :eti-low -1.6 :eti-high 1.6
                              :rhat 1.0 :ess-bulk 800.0 :ess-tail 800.0}}
               :divergences 3}
          {:keys [pass divergences-ok rows]} (rec/score-recovery fit {"a" 0.0})]
      (is (false? divergences-ok))
      (is (false? pass))
      (is (false? (:pass (first rows))))))
  (testing "out-of-interval truth fails coverage and overall"
    (let [fit {:summary {"a" {:mean 0.0 :sd 1.0 :eti-low -1.0 :eti-high 1.0
                              :rhat 1.0 :ess-bulk 800.0 :ess-tail 800.0}}
               :divergences 0}
          {:keys [pass rows]} (rec/score-recovery fit {"a" 5.0})]
      (is (false? pass))
      (is (false? (:covered (first rows)))))))
