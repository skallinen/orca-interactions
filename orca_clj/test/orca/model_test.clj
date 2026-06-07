(ns orca.model-test
  "Tests for the CmdStan data/draws plumbing that doesn't need a live sampler:
   thinning, 0-based -> 1-based category shifting, and draw projection/rounding."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.model :as model]
   [tablecloth.api :as tc]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-9))

(deftest thin-to-test
  (is (= [0 2 4 6 8] (model/thin-to 10 5)))
  (is (= [0 1 2 3] (model/thin-to 4 4)))
  (is (= [0 2 4] (model/thin-to 7 3)))
  (testing "always returns n indices, strictly within [0,total)"
    (let [idxs (model/thin-to 2000 500)]
      (is (= 500 (count idxs)))
      (is (every? #(< -1 % 2000) idxs)))))

(deftest stan-data-test
  (let [ds   (tc/dataset {:interaction         [1 0]
                          :depth_ord_std       [0.5 -0.5]
                          :autopilot_on        [1 0]
                          :speed_ord_std       [0.1 0.2]
                          :boat_length_ord_std [0.3 0.4]
                          :distance_ord_std    [0.5 0.6]
                          :wind_ord_std        [0.7 0.8]
                          :sea_state_ord_std   [0.9 1.0]
                          :sailing_mode_idx    [0 1]
                          :antifoul_idx        [2 0]
                          :hull_colour_idx     [0 1]
                          :rudder_idx          [1 0]})
        md   {:categories {:sailing_mode ["a" "b"]
                           :antifoul     ["w" "x" "y"]
                           :hull_colour  ["p" "q"]
                           :rudder       ["m" "n" "o" "p"]}}
        sd   (model/stan-data ds md)]
    (is (= 2 (:N sd)))
    (is (= [1 0] (:y sd)))
    (testing "category indices shift 0-based -> 1-based"
      (is (= [1 2] (:sailing sd)))
      (is (= [3 1] (:antifoul sd))))
    (testing "category counts come from metadata"
      (is (= 2 (:n_sailing sd)))
      (is (= 3 (:n_antifoul sd)))
      (is (= 4 (:n_rudder sd))))
    (testing "autopilot coerced to double"
      (is (= [1.0 0.0] (:autopilot sd))))))

(deftest draws->rows-test
  (let [n     10
        ds    (tc/dataset (zipmap model/stan-cols
                                  (repeat (mapv #(+ % 0.123456) (range n)))))
        rows  (model/draws->rows ds 5)]
    (testing "shape: n-out rows x 30 layout slots"
      (is (= 5 (count rows)))
      (is (every? #(= (count model/stan-cols) (count %)) rows)))
    (testing "thinned at [0 2 4 6 8] and rounded to 4 dp"
      (is (close? 0.1235 (ffirst rows)))
      (is (close? 8.1235 (first (last rows)))))))
