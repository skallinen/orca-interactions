(ns orca.core-test
  "Smoke tests for the orca.core runner: confirm the pipeline wires together and
   the no-MCMC stage works. Heavy MCMC stages (the ladder, results, sensitivity,
   findings, encoding) are exercised by their own namespaces' end-to-end runs and
   are deliberately not invoked here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.core :as core]))

(deftest entry-points-present
  (testing "the two top-level runners and each stage are bound and callable"
    (is (fn? @(resolve 'orca.core/run-all)))
    (is (fn? @(resolve 'orca.core/run-everything)))
    (is (fn? @(resolve 'orca.core/stage-prep)))
    (is (fn? @(resolve 'orca.core/stage-m3-calculator)))
    (is (fn? @(resolve 'orca.core/stage-timeofday)))))

(deftest stage-prep-passes
  (testing "the non-MCMC data-prep stage validates row-for-row vs the oracle"
    (let [result (atom nil)
          out    (with-out-str (reset! result (core/stage-prep)))]
      (is (string? out))
      (is (true? (:pass? @result))
          "validate-prep must pass (row order, categories, standardization)"))))
