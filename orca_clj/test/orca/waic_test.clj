(ns orca.waic-test
  "Unit tests for WAIC, checked against hand-computed values (porting.md §4.3).
   WAIC is validated on its own arithmetic plus the M3-vs-M4 ordering in the
   end-to-end run."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.waic :as waic]
   [tablecloth.api :as tc]))

(defn- close?
  ([a b] (close? a b 1.0e-6))
  ([a b tol] (< (Math/abs (- (double a) (double b))) tol)))

(deftest waic-single-observation
  (testing "one obs, draws [-1 -2]: lppd=log(mean(e^-1,e^-2)), p_waic=var1"
    (let [{:keys [elpd-waic p-waic waic n n-draws]} (waic/waic [[-1.0 -2.0]])]
      (is (= 1 n))
      (is (= 2 n-draws))
      ;; lppd = log((e^-1 + e^-2)/2), p_waic = (−1−(−2))²/2 = 0.5
      (let [lppd (Math/log (/ (+ (Math/exp -1.0) (Math/exp -2.0)) 2.0))]
        (is (close? 0.5 p-waic))
        (is (close? (- lppd 0.5) elpd-waic 1.0e-9))
        (is (close? (* -2.0 elpd-waic) waic))))))

(deftest waic-constant-columns
  (testing "two obs, each constant across draws: p_waic=0, elpd=Σ c_i, se=|c1-c2|"
    (let [{:keys [elpd-waic p-waic waic se]}
          (waic/waic [(repeat 4 -1.0) (repeat 4 -3.0)])]
      (is (close? 0.0 p-waic))
      (is (close? -4.0 elpd-waic))
      (is (close? 8.0 waic))
      ;; se = sqrt(N · var1([-1,-3])) = sqrt(2 · 2) = 2
      (is (close? 2.0 se)))))

(deftest compare-orders-best-first
  (testing "higher elpd model ranks first with Δelpd 0; worse model positive Δ"
    (let [wa  (waic/waic [(repeat 4 -1.0) (repeat 4 -1.0)])   ; elpd -2
          wb  (waic/waic [(repeat 4 -2.0) (repeat 4 -2.0)])   ; elpd -4
          cmp (waic/compare {"A" wa "B" wb})]
      (is (= ["A" "B"] (mapv :name cmp)))
      (is (close? 0.0 (:d-elpd (first cmp))))
      (is (close? 0.0 (:d-se (first cmp))))
      ;; B is 2 elpd worse, both points equal so Δ has zero variance → d-se 0
      (is (close? 2.0 (:d-elpd (second cmp))))
      (is (close? 0.0 (:d-se (second cmp)))))))

(deftest log-lik-cols-orders-by-index
  (testing "columns extracted and sorted by 1-based observation index"
    (let [draws (tc/dataset {"alpha"       [0.0 0.0]
                             "log_lik.1"   [-1.0 -1.5]
                             "log_lik.2"   [-2.0 -2.5]
                             "log_lik.10"  [-9.0 -9.5]
                             "divergent__" [0 0]})
          cols  (waic/log-lik-cols draws)]
      ;; three log_lik columns, ordered 1,2,10 (numeric, not lexical)
      (is (= 3 (count cols)))
      (is (= [-1.0 -1.5] (first cols)))
      (is (= [-9.0 -9.5] (last cols))))))
