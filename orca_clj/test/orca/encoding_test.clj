(ns orca.encoding-test
  "Unit tests for the pure encoding transforms in orca.encoding (exposure-window
   splitting, the night-test column derivations, the solar binary mapping),
   checked against hand-computed values. The MCMC night-encoding refit runs
   end-to-end elsewhere."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.encoding :as enc]))

;; Raw reports: 1 incident (Night) + 2 uneventful (one Day-only, one Day+Night).
(def raw
  [{:report_type "incident" :darkness_or_daylight "Night"}
   {:report_type "uneventful" :darkness_or_daylight "Day"}
   {:report_type "uneventful" :darkness_or_daylight "Dawn , Day , Dusk , Night"}])

(deftest split-windows-test
  (testing "incident → one window; multi-period uneventful → one window per binary value"
    (let [windows (enc/split-windows (group-by #(keyword (:report_type %)) raw)
                                     enc/period->binary)]
      ;; incident Night → {:interaction 1 :is-daytime 0}
      ;; une Day → {:interaction 0 :is-daytime 1}
      ;; une Dawn,Day,Dusk,Night → bins {1 (Dawn/Day) 0 (Dusk/Night)} → 2 windows
      (is (= 4 (count windows)))
      (is (= {:interaction 1 :is-daytime 0} (first windows)))
      (is (= #{0 1} (set (map :is-daytime (drop 2 windows))))))))

(deftest encoding-c-vs-d-test
  (testing "Dawn/Dusk flip between split encodings C and D"
    (is (= {"Day" 1 "Night" 0 "Dawn" 1 "Dusk" 0} enc/period->binary))
    (is (= {"Day" 1 "Night" 0 "Dawn" 0 "Dusk" 1} enc/period->binary-alt))))

(deftest night-encoding-column-test
  (testing "encoding A: exact {Night,Dawn} → 0, else present → 1"
    (is (= [0 1 1] (enc/night-encoding-column raw :a))))
  (testing "encoding B: contains 'Night' → 0, else present → 1"
    ;; incident 'Night' → 0; une 'Day' → 1; une 'Dawn , Day , Dusk , Night' → 0
    (is (= [0 1 0] (enc/night-encoding-column raw :b))))
  (testing "blank/nil → nil"
    (is (= [nil]
           (enc/night-encoding-column
             [{:report_type "incident" :darkness_or_daylight nil}] :a)))))

(deftest periods-via-split-windows-test
  (testing "a single-period incident keeps a defined binary; unknown period → nil"
    (let [windows (enc/split-windows
                    {:incident [{:report_type "incident" :darkness_or_daylight "Twilight"}]
                     :uneventful []}
                    enc/period->binary)]
      (is (= [{:interaction 1 :is-daytime nil}] windows)))))
