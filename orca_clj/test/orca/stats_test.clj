(ns orca.stats-test
  "Unit tests for the frequentist tests, checked against hand-computed and
   well-known scipy reference values."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.stats :as stats]))

(defn- close?
  ([a b] (close? a b 1.0e-6))
  ([a b tol] (< (Math/abs (- (double a) (double b))) tol)))

(deftest value-counts-test
  (is (= {:a 2 :b 1 nil 1}
         (stats/value-counts [:a :b :a nil]))))

(deftest crosstab-2x2-test
  (testing "rows/cols ordered false then true, like pd.crosstab"
    (let [rows [false false true true true]
          cols [false true true true false]]
      ;; ¬r∧¬c=1, ¬r∧c=1, r∧¬c=1, r∧c=2
      (is (= [[1 1] [1 2]] (stats/crosstab-2x2 rows cols)))))
  (testing "nil pairs dropped"
    (is (= [[1 0] [0 1]]
           (stats/crosstab-2x2 [false nil true] [false false true])))))

(deftest odds-ratio-test
  (is (close? 3.75 (stats/odds-ratio [[100 20] [40 30]])))
  (is (= Double/POSITIVE_INFINITY (stats/odds-ratio [[5 0] [3 4]]))))

(deftest chi2-yates-test
  (testing "2×2 gets Yates correction by default (hand-computed)"
    (let [{:keys [chi2 dof p]} (stats/chi2-contingency [[100 20] [40 30]])]
      (is (= 1 dof))
      (is (close? 14.31794 chi2 1.0e-4))
      (is (close? 1.543867e-4 p 1.0e-8))))
  (testing "correction can be turned off"
    (let [no  (:chi2 (stats/chi2-contingency [[100 20] [40 30]]
                                             {:correction? false}))
          yes (:chi2 (stats/chi2-contingency [[100 20] [40 30]]))]
      ;; uncorrected χ² is larger than Yates-corrected
      (is (> no yes))
      (is (close? 15.639456 no 1.0e-4)))))

(deftest fisher-exact-test
  (testing "Fisher tea-tasting table [[3 1][1 3]] → OR 9, p 0.485714 (exact)"
    (let [{:keys [odds-ratio p]} (stats/fisher-exact [[3 1] [1 3]])]
      (is (close? 9.0 odds-ratio))
      (is (close? 0.4857142857 p 1.0e-9))))
  (testing "[[8 2][1 5]] → two-sided p 0.034965 (hand-computed hypergeometric)"
    (let [{:keys [odds-ratio p]} (stats/fisher-exact [[8 2] [1 5]])]
      (is (close? 20.0 odds-ratio))
      (is (close? 0.03496503 p 1.0e-7)))))

(deftest t-test-pooled-test
  (testing "pooled (equal_var=True) Student's t vs scipy ttest_ind default"
    (let [{:keys [t df p diff]} (stats/t-test [1.0 2.0 3.0 4.0]
                                              [2.0 3.0 4.0 5.0 6.0])]
      (is (= 7 df))
      (is (close? -1.527525 t 1.0e-5))
      (is (close? 0.170471 p 1.0e-5))
      (is (close? -1.5 diff)))))
