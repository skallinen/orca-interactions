(ns orca.timeofday-test
  "Tests for the exposure/time-of-day logic: datetime parsing, overnight
   roll-over, day/night integration conservation, solar classification, the
   long-passage exclusion, and incident counting."
  (:require
   [clojure.test :refer [deftest is testing]]
   [orca.timeofday :as tod])
  (:import
   (java.time LocalDateTime)))

(defn- close? [a b] (< (abs (- (double a) (double b))) 1e-9))

(deftest parse-dt-test
  (is (= (LocalDateTime/of 2023 6 15 12 0 0)
         (tod/parse-dt "2023-06-15" "12:00:00")))
  (testing "blank / nil components -> nil"
    (is (nil? (tod/parse-dt nil "12:00:00")))
    (is (nil? (tod/parse-dt "2023-06-15" "")))
    (is (nil? (tod/parse-dt "  " "12:00:00")))))

(deftest passage-duration-hours-test
  (let [d #(tod/parse-dt "2023-06-15" %)]
    (testing "same-day"
      (is (close? 4.0 (tod/passage-duration-hours (d "10:00:00") (d "14:00:00")))))
    (testing "end <= start rolls to next day"
      (is (close? 4.0 (tod/passage-duration-hours (d "22:00:00") (d "02:00:00")))))))

(deftest passage-hours-conservation-test
  (let [day-p   [(tod/parse-dt "2023-06-15" "12:00:00")
                 (tod/parse-dt "2023-06-15" "14:00:00")]
        night-p [(tod/parse-dt "2023-06-15" "01:00:00")
                 (tod/parse-dt "2023-06-15" "03:00:00")]]
    (testing "day + night hours always sum to total duration"
      (let [[dh nh] (apply tod/passage-hours day-p)]
        (is (close? 2.0 (+ dh nh)))
        (is (close? 0.0 nh)))
      (let [[dh nh] (apply tod/passage-hours night-p)]
        (is (close? 2.0 (+ dh nh)))
        (is (close? 0.0 dh))))))

(deftest solar-period-test
  (testing "midday is day, small hours are night at the orca zone (37N,8W)"
    (is (= :day (tod/solar-period (LocalDateTime/of 2023 6 15 12 30 0))))
    (is (= :night (tod/solar-period (LocalDateTime/of 2023 6 15 2 0 0))))))

(deftest exposure-excludes-long-passages-test
  (let [uneventful [{:date_passage_commenced "2023-06-15" :time_passage_commenced "12:00:00"
                     :date_passage_ended     "2023-06-15" :time_passage_ended     "14:00:00"}
                    ;; 14-day "passage" = 336 h > 200 h cutoff -> dropped as a data error
                    {:date_passage_commenced "2023-06-01" :time_passage_commenced "00:00:00"
                     :date_passage_ended     "2023-06-15" :time_passage_ended     "00:00:00"}]
        {:keys [day night]} (tod/exposure uneventful)]
    (is (close? 2.0 (+ day night)))
    (is (close? 0.0 night))))

(deftest incident-counts-test
  (let [incidents [{:date_of_interaction "2023-06-15" :time_of_interaction "12:30:00"}
                   {:date_of_interaction "2023-06-15" :time_of_interaction "02:00:00"}
                   {:date_of_interaction "2023-06-15" :time_of_interaction nil}]]
    (is (= {:night 1 :day 1} (tod/incident-counts incidents)))))

(deftest incident-counts-4-test
  (testing "incidents split into the four present/absent x day/night cells"
    (let [incidents [;; present + day
                     {:date_of_interaction "2023-06-15" :time_of_interaction "12:30:00" :f true}
                     ;; present + night
                     {:date_of_interaction "2023-06-15" :time_of_interaction "02:00:00" :f true}
                     ;; absent + day (two of them)
                     {:date_of_interaction "2023-06-15" :time_of_interaction "13:00:00" :f false}
                     {:date_of_interaction "2023-06-15" :time_of_interaction "11:00:00" :f false}
                     ;; absent + night
                     {:date_of_interaction "2023-06-15" :time_of_interaction "03:00:00" :f false}
                     ;; missing timestamp is skipped
                     {:date_of_interaction "2023-06-15" :time_of_interaction nil :f true}]]
      (is (= {:pd 1 :pn 1 :ad 2 :an 1}
             (tod/incident-counts-4 incidents :f))))))

(deftest exposure-4-test
  (testing "uneventful yacht-hours split into the four cells, conserving totals"
    (let [uneventful [;; present, all-day, 2 h
                      {:date_passage_commenced "2023-06-15" :time_passage_commenced "12:00:00"
                       :date_passage_ended     "2023-06-15" :time_passage_ended     "14:00:00"
                       :f true}
                      ;; absent, all-night, 2 h
                      {:date_passage_commenced "2023-06-15" :time_passage_commenced "01:00:00"
                       :date_passage_ended     "2023-06-15" :time_passage_ended     "03:00:00"
                       :f false}
                      ;; absent, 14-day "passage" > 200 h cutoff -> dropped
                      {:date_passage_commenced "2023-06-01" :time_passage_commenced "00:00:00"
                       :date_passage_ended     "2023-06-15" :time_passage_ended     "00:00:00"
                       :f false}]
          {:keys [T_pd T_pn T_ad T_an]} (tod/exposure-4 uneventful :f)]
      (is (close? 2.0 T_pd))
      (is (close? 0.0 T_pn))
      (is (close? 0.0 T_ad))
      (is (close? 2.0 T_an)))))
