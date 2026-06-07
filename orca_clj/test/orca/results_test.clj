(ns orca.results-test
  "Unit tests for the pure contrast arithmetic in orca.results (the novel logic;
   the fitting/printing/plotting paths are exercised end-to-end in the REPL per
   porting.md §7). Draws are supplied as a tiny synthetic tablecloth dataset so no
   CmdStan run is needed."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.results :as results]
   [tablecloth.api :as tc]))

(def ^:private md
  {:categories {:antifoul ["Black" "Blue" "Coppercoat"]}})

(deftest category-contrast-arithmetic
  (testing "Black − Coppercoat contrast: mean diff, odds ratio, P(a>b)"
    ;; alpha_antifoul.1 = Black, .3 = Coppercoat (Stan columns are 1-based)
    (let [draws (tc/dataset {"alpha_antifoul.1" [1.0 2.0 3.0 4.0]
                             "alpha_antifoul.2" [0.0 0.0 0.0 0.0]
                             "alpha_antifoul.3" [0.0 1.0 0.0 1.0]})
          {:keys [contrast mean odds p-gt]}
          (results/category-contrast draws md "alpha_antifoul" :antifoul
                                     "Black" "Coppercoat")]
      ;; diffs = [1 1 3 3] → mean 2.0, OR e^2, all positive
      (is (= "Black vs Coppercoat" contrast))
      (is (< (abs (- mean 2.0)) 1e-9))
      (is (< (abs (- odds (Math/exp 2.0))) 1e-9))
      (is (== p-gt 1.0)))))

(deftest slope-effect-arithmetic
  (testing "single-slope mean, OR, and P(effect < 0)"
    (let [draws (tc/dataset {"beta_autopilot" [-1.0 -2.0 -3.0 1.0]})
          {:keys [param mean odds p-neg]}
          (results/slope-effect draws "beta_autopilot")]
      (is (= "beta_autopilot" param))
      (is (< (abs (- mean -1.25)) 1e-9))
      (is (< (abs (- odds (Math/exp -1.25))) 1e-9))
      ;; three of four draws are negative
      (is (== p-neg 0.75)))))
