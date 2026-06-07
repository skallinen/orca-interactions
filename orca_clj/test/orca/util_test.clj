(ns orca.util-test
  "Pure-function tests for the numeric helpers. These pin the exact semantics
   (population sd, numpy-style linear-interpolated quantile) the validation
   harness depends on, so the dtype-next reimplementation can't drift."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.util :as util]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-9))

(deftest mean-test
  (is (close? 2.5 (util/mean [1 2 3 4])))
  (is (close? 5.0 (util/mean [5])))
  (is (close? 0.0 (util/mean [-2 0 2]))))

(deftest pstdev-test
  (testing "population sd (ddof=0), classic worked example"
    (is (close? 2.0 (util/pstdev [2 4 4 4 5 5 7 9]))))
  (is (close? 0.0 (util/pstdev [3 3 3]))))

(deftest quantile-test
  (testing "linear interpolation matches numpy default"
    (is (close? 2.5 (util/quantile [1 2 3 4] 0.5)))
    (is (close? 17.5 (util/quantile [10 20 30 40] 0.25))))
  (testing "endpoints are min/max"
    (is (close? 1.0 (util/quantile [4 1 3 2] 0.0)))
    (is (close? 4.0 (util/quantile [4 1 3 2] 1.0))))
  (testing "input need not be pre-sorted"
    (is (close? 2.5 (util/quantile [4 2 1 3] 0.5)))))
