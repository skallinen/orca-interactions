(ns orca.diagnostics-test
  "Diagnostics tests. R̂/ESS have no committed oracle, so these assert the
   ArviZ-defining properties on deterministic seeded data:
   well-mixed chains give R̂≈1 and ESS≈N, separated chains give large R̂,
   autocorrelated chains give ESS≪N. ETI/HDI are checked against exact values."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.diagnostics :as d]))

(defn- seeded-chains
  "n chains of `len` draws from a fixed-seed Gaussian, optional per-chain shift."
  ([n len] (seeded-chains n len (repeat 0.0)))
  ([n len shifts]
   (let [r (java.util.Random. 7)]
     (mapv (fn [shift]
             (vec (repeatedly len #(+ shift (.nextGaussian r)))))
           (take n shifts)))))

(deftest rhat-converged-test
  (testing "well-mixed iid chains → R̂ ≈ 1.00"
    (is (< 0.99 (d/rhat (seeded-chains 4 1000)) 1.02)))
  (testing "chains separated by 5 → R̂ ≫ 1"
    (is (> (d/rhat (seeded-chains 2 1000 [0.0 5.0])) 1.3))))

(deftest ess-magnitude-test
  (testing "iid chains → bulk/tail ESS near the total draw count"
    (let [chains (seeded-chains 4 1000)]
      (is (< 3000 (d/ess-bulk chains) 4200))
      (is (< 2500 (d/ess-tail chains) 4200))))
  (testing "AR(1) φ=0.8 → ESS far below N (theory N·(1-φ)/(1+φ) ≈ N/9)"
    (let [r   (java.util.Random. 11)
          ar  (vec (repeatedly
                     4 #(let [xs (double-array 1000)]
                          (aset xs 0 (.nextGaussian r))
                          (dotimes [i 999]
                            (aset xs (inc i)
                                  (+ (* 0.8 (aget xs i)) (.nextGaussian r))))
                          (vec xs))))]
      (is (< (d/ess-bulk ar) 1000)))))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1.0e-6))

(deftest eti-test
  (testing "central prob mass = symmetric quantiles"
    (let [[lo hi] (d/eti (range 101) 0.90)]
      (is (close? 5.0 lo))
      (is (close? 95.0 hi)))
    (let [[lo hi] (d/eti (range 101) 0.80)]
      (is (close? 10.0 lo))
      (is (close? 90.0 hi)))))

(deftest hdi-test
  (testing "uniform data → first minimal window (ArviZ np.argmin tie-break)"
    (is (= [0.0 90.0] (d/hdi (range 101) 0.90))))
  (testing "HDI excludes a far outlier that the ETI would straddle"
    (let [xs (concat (range 0 20) [1000.0])] ; 21 values, one outlier
      ;; 80% HDI should sit in the dense low cluster, well below the outlier
      (is (< (second (d/hdi xs 0.80)) 100.0)))))

(deftest summarize-test
  (let [chains (seeded-chains 4 500)
        {:keys [mean sd rhat ess-bulk eti-lo eti-hi]} (d/summarize chains)]
    (is (< (Math/abs (double mean)) 0.2))
    (is (< 0.8 sd 1.2))
    (is (< 0.99 rhat 1.03))
    (is (pos? ess-bulk))
    (is (< eti-lo eti-hi))))
