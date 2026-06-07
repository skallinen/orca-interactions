(ns orca.dag-test
  "Unit tests for orca.dag — pure documentation data. We assert the Mermaid
   source and the adjustment notes contain the load-bearing anchors (the diagram
   shape, the collider caveat, and the M3-honesty note) so the prose can't
   silently lose them."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [orca.dag :as dag]))

(deftest mermaid-anchors-test
  (testing "the Mermaid source is a fenced graph with the outcome + key edges"
    (is (str/starts-with? dag/mermaid "```mermaid"))
    (is (str/ends-with? dag/mermaid "```"))
    (is (str/includes? dag/mermaid "graph TD"))
    (is (str/includes? dag/mermaid "INT[\"INTERACTION\"]"))
    (is (str/includes? dag/mermaid "AF[\"antifoul_colour\"]"))
    (is (str/includes? dag/mermaid "AF --> INT"))))

(deftest adjustment-notes-anchors-test
  (testing "the adjustment notes keep the two estimands and the caveats"
    (is (str/includes? dag/adjustment-notes "ESTIMAND 1 (Causal)"))
    (is (str/includes? dag/adjustment-notes "ESTIMAND 2 (Predictive)"))
    (is (str/includes? dag/adjustment-notes "ADJUSTMENT SETS")))
  (testing "the collider caveat on post-outcome variables survives"
    (is (str/includes? dag/adjustment-notes "COLLIDER WARNING"))
    (is (str/includes? dag/adjustment-notes "tow_required")))
  (testing "the honesty note: fitted M3 is predictive/associational, month unadjusted"
    (is (str/includes? dag/adjustment-notes "PREDICTIVE estimand"))
    (is (str/includes? dag/adjustment-notes "associational"))
    (is (str/includes? dag/adjustment-notes "month"))
    (is (str/includes? dag/adjustment-notes "mediators"))))

(deftest print-dag-returns-data-test
  (testing "print-dag returns the mermaid + notes values"
    (let [{:keys [mermaid notes]} (dag/print-dag)]
      (is (= dag/mermaid mermaid))
      (is (= dag/adjustment-notes notes)))))
