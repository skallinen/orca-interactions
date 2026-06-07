(ns orca.params-test
  "Unit tests for the shared posterior-draws / category accessors. The asserting
   cat-index is the key correctness property (a -1 .indexOf would silently
   corrupt a contrast)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.params :as params]
   [tablecloth.api :as tc]))

(def ^:private md
  {:categories {:antifoul ["Black" "Blue" "Coppercoat"]}})

(deftest cat-index-asserts-present
  (testing "found category → its 0-based position"
    (is (= 0 (params/cat-index md :antifoul "Black")))
    (is (= 2 (params/cat-index md :antifoul "Coppercoat"))))
  (testing "absent category / typo → loud AssertionError, never -1"
    (is (thrown? AssertionError (params/cat-index md :antifoul "Bllack")))
    (is (thrown? AssertionError (params/cat-index md :antifoul "Red")))
    (is (thrown? AssertionError (params/cat-index md :missing-key "Black")))))

(deftest cat-col-is-1-based
  (testing "0-based category i reads Stan's 1-based family.(i+1) column"
    (let [draws (tc/dataset {"alpha_antifoul.1" [1.0 2.0]
                             "alpha_antifoul.3" [9.0 9.0]})]
      (is (= [1.0 2.0] (params/cat-col draws "alpha_antifoul" 0)))
      (is (= [9.0 9.0] (params/cat-col draws "alpha_antifoul" 2))))))

(deftest contrast-arithmetic
  (testing "a − b mean, ETI, odds, P(a>b)"
    (let [{:keys [mean odds p-gt lo hi]}
          (params/contrast [3.0 2.0 4.0] [1.0 1.0 1.0])]
      ;; diffs = [2 1 3] → mean 2.0
      (is (< (abs (- mean 2.0)) 1e-9))
      (is (< (abs (- odds (Math/exp 2.0))) 1e-9))
      (is (== p-gt 1.0))
      (is (<= lo mean hi))))
  (testing "p-gt counts strictly-positive diffs"
    (let [{:keys [p-gt]} (params/contrast [1.0 0.0 2.0] [1.0 1.0 1.0])]
      ;; diffs = [0 -1 1] → one positive of three
      (is (< (abs (- p-gt (/ 1.0 3.0))) 1e-9)))))

(deftest category-contrast-labels
  (testing "category-contrast adds an 'a vs b' label"
    (let [draws (tc/dataset {"alpha_antifoul.1" [2.0 2.0]
                             "alpha_antifoul.3" [0.0 0.0]})
          {:keys [contrast mean]}
          (params/category-contrast draws md "alpha_antifoul" :antifoul
                                    "Black" "Coppercoat")]
      (is (= "Black vs Coppercoat" contrast))
      (is (< (abs (- mean 2.0)) 1e-9)))))
