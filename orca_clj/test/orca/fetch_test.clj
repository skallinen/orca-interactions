(ns orca.fetch-test
  "Tests for the pure transforms in the API client. The network calls
   (api-get / fetch-report / fetch-reports) are exercised live from the REPL;
   these cover the data shaping that turns API JSON into rows + CSV columns."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.fetch :as f]
   [tablecloth.api :as tc]))

(def ^:private ordered-columns @#'f/ordered-columns)
(def ^:private summary-row @#'f/summary-row)
(def ^:private task-list @#'f/task-list)

(deftest flatten-response-test
  (testing "unwraps {:Q :A} to the answer, trimming strings"
    (is (= {:boat_type "Yacht"} (f/flatten-response {:boat_type {:Q "?" :A "  Yacht "}}))))
  (testing "non-string answers pass through untrimmed"
    (is (= {:lat 37} (f/flatten-response {:lat {:Q "?" :A 37}}))))
  (testing "values without :A pass through unchanged"
    (is (= {:raw 5 :m {:x 1}} (f/flatten-response {:raw 5 :m {:x 1}})))))

(deftest ordered-columns-test
  (testing "priority columns first (in order), remaining alphabetically"
    (let [ds (tc/dataset [{:report_id "a" :zeta 1 :alpha 2 :serial 3}])]
      (is (= [:report_id :serial :alpha :zeta]
             (ordered-columns ds [:report_id :serial :missing])))))
  (testing "drops priority keys that aren't present"
    (let [ds (tc/dataset [{:b 1 :a 2}])]
      (is (= [:a :b] (ordered-columns ds [:nope]))))))

(deftest summary-row-test
  (testing "keywordised id -> string, missing fields default to empty string"
    (is (= {:report_id "10710" :type "incident" :serial 120
            :time "2023-04-20" :lat 35.8 :long -5.8}
           (summary-row "incident" [:10710 {:serial 120 :time "2023-04-20"
                                            :lat 35.8 :long -5.8}])))
    (is (= {:report_id "1" :type "uneventful" :serial "" :time "" :lat "" :long ""}
           (summary-row "uneventful" [:1 {}])))))

(deftest task-list-test
  (testing "incidents then uneventful, each tagged with type + string id"
    (is (= [{:report-id "10" :summary {:serial 1} :report-type "incident"}
            {:report-id "20" :summary {:serial 2} :report-type "uneventful"}]
           (task-list {:incident {:10 {:serial 1}}
                       :uneventful {:20 {:serial 2}}})))))
