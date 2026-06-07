(ns orca.eda-test
  "Unit tests for the pure rate computations in orca.eda (level / category /
   binary interaction rates and the overall base rate), checked against
   hand-computed values."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.eda :as eda]
   [tablecloth.api :as tc]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1.0e-9))

(def data
  (tc/dataset
    {:depth_ord    [0 0 1 1 nil]          ; level 0: 2 rows; level 1: 2 rows; nil dropped
     :antifoul_idx [0 0 1 2 1]            ; Black=0, Coppercoat=1, Unknown=2
     :is_daytime   [1 0 1 0 1]
     :interaction  [1 0 1 1 0]}))

(def md {:categories {:antifoul ["Black" "Coppercoat" "Unknown"]}})

(deftest level-rates-test
  (testing "rates per present ordinal level, nil dropped"
    (is (= [{:level 0 :n 2 :incidents 1 :rate 0.5}
            {:level 1 :n 2 :incidents 2 :rate 1.0}]
           (eda/level-rates data :depth_ord)))))

(deftest category-rates-test
  (testing "Unknown excluded; empty categories dropped"
    (let [rows (eda/category-rates data :antifoul_idx (get-in md [:categories :antifoul]))]
      ;; Black (idx0): rows 0,1 → 1 inc/2 → 0.5
      ;; Coppercoat (idx1): rows 2,4 → 1 inc/2 → 0.5
      ;; Unknown excluded
      (is (= [{:category "Black" :n 2 :incidents 1 :rate 0.5}
              {:category "Coppercoat" :n 2 :incidents 1 :rate 0.5}]
             rows)))))

(deftest binary-rates-test
  (testing "level 0 then 1"
    (let [[lo hi] (eda/binary-rates data :is_daytime)]
      ;; is_daytime 0: rows 1,3 → interaction 0,1 → 0.5
      ;; is_daytime 1: rows 0,2,4 → interaction 1,1,0 → 2/3
      (is (= {:level 0 :n 2 :incidents 1 :rate 0.5} lo))
      (is (= {:level 1 :n 3 :incidents 2 :rate (/ 2.0 3)} hi)))))

(deftest overall-rate-test
  (is (close? 0.6 (eda/overall-rate data))))
