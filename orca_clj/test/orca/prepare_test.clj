(ns orca.prepare-test
  "Tests for the data-prep encoders. The full row-for-row match against
   modeling_data.csv lives in orca.validate; these cover the unit semantics
   (trimming, missing -> nil, ddof=1 standardization, index coding)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.prepare :as prep]))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-9))

(deftest encode-ordinal-test
  (testing "maps known levels, trimming whitespace"
    (is (= 0 (prep/encode-ordinal prep/depth-map "Up to 20m")))
    (is (= 0 (prep/encode-ordinal prep/depth-map "  Up to 20m  ")))
    (is (= 3 (prep/encode-ordinal prep/depth-map "200m+"))))
  (testing "unknown / nil -> nil (becomes NaN downstream)"
    (is (nil? (prep/encode-ordinal prep/depth-map "nope")))
    (is (nil? (prep/encode-ordinal prep/depth-map nil)))
    (is (nil? (prep/encode-ordinal prep/depth-map "")))))

(deftest autopilot-on-test
  (is (= 1 (prep/autopilot-on "On")))
  (is (= 1 (prep/autopilot-on "Engaged on")))
  (is (= 0 (prep/autopilot-on "off")))
  (is (= 0 (prep/autopilot-on "  OFF ")))
  (testing "neither/empty/nil -> nil"
    (is (nil? (prep/autopilot-on "")))
    (is (nil? (prep/autopilot-on "n/a")))
    (is (nil? (prep/autopilot-on nil)))))

(deftest index-column-test
  (testing "nil -> Unknown, sorted-unique 0-based codes"
    (let [[codes cats] (prep/index-column ["b" "a" nil "a"])]
      (is (= ["Unknown" "a" "b"] cats))
      (is (= [2 1 0 1] codes))))
  (testing "trims before coding"
    (let [[codes cats] (prep/index-column [" x " "x"])]
      (is (= ["x"] cats))
      (is (= [0 0] codes)))))

(deftest standardize-test
  (testing "z-score with sample sd (ddof=1) and returned mean/sd"
    (let [[std m sd] (prep/standardize [0.0 1.0 2.0 3.0])]
      (is (close? 1.5 m))
      (is (close? (Math/sqrt (/ 5.0 3.0)) sd))
      (is (close? (/ (- 0.0 1.5) sd) (first std)))
      (is (close? 0.0 (reduce + std)))))
  (testing "missing values pass through as nil and are excluded from stats"
    (let [[std m sd] (prep/standardize [0.0 nil 2.0])]
      (is (close? 1.0 m))
      (is (close? (Math/sqrt 2.0) sd))
      (is (nil? (second std)))
      (is (close? (/ (- 0.0 1.0) sd) (first std))))))
