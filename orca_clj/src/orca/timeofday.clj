(ns orca.timeofday
  "Exposure-based time-of-day analysis (methodology.html §7), replacing Python's
   `astral` with the `commons-suncalc` Java library.

   Classifies each incident's moment as Day/Night/Dawn/Dusk by solar position at
   the orca zone (37N, 8W), and integrates each uneventful passage's duration
   into day vs night yacht-hours. Then fits a Poisson incident-rate model and
   reports the night/day rate ratio."
  (:require
   [clojure.string :as str]
   [orca.stan :as stan]
   [orca.util :as util])
  (:import
   (java.time Instant LocalDate LocalDateTime LocalTime ZoneOffset)
   (java.time.format DateTimeFormatter)
   (org.shredzone.commons.suncalc SunTimes SunTimes$Twilight)))

(def lat 37.0)
(def lon -8.0)

(defn- suntimes
  "Sun rise/set ZonedDateTimes at the orca zone for `date` and `twilight`."
  [^LocalDate date twilight]
  (-> (SunTimes/compute)
      (.on (.getYear date) (.getMonthValue date) (.getDayOfMonth date))
      (.timezone "UTC")
      (.at lat lon)
      (.twilight twilight)
      (.execute)))

(def ^:private suntimes* (memoize suntimes))

(defn- ^Instant inst [zdt] (when zdt (.toInstant zdt)))

(defn solar-period
  "Classify a UTC `LocalDateTime` as :night/:dawn/:day/:dusk using civil-twilight
   (dawn/dusk) and visual (sunrise/sunset) boundaries — mirrors solar_encoding.py."
  [^LocalDateTime dt]
  (let [date (.toLocalDate dt)
        civ  (suntimes* date SunTimes$Twilight/CIVIL)
        vis  (suntimes* date SunTimes$Twilight/VISUAL)
        t    (.toInstant dt ZoneOffset/UTC)
        dawn (inst (.getRise civ))
        dusk (inst (.getSet civ))
        sr   (inst (.getRise vis))
        ss   (inst (.getSet vis))]
    (cond
      (and dawn (.isBefore t dawn)) :night
      (and sr (.isBefore t sr))     :dawn
      (and ss (.isBefore t ss))     :day
      (and dusk (.isBefore t dusk)) :dusk
      :else :night)))

(def night-periods #{:night :dusk})

(def ^:private date-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
(def ^:private time-fmt (DateTimeFormatter/ofPattern "HH:mm:ss"))

(defn parse-dt
  "Combine a date string and time string into a LocalDateTime, or nil."
  [d t]
  (when (and d t (not (str/blank? (str d))) (not (str/blank? (str t))))
    (LocalDateTime/of (LocalDate/parse (str/trim (str d)) date-fmt)
                      (LocalTime/parse (str/trim (str t)) time-fmt))))

(defn incident-counts
  "Classify each incident moment; return {:night n :day n} (night = Night|Dusk)."
  [incidents]
  (reduce (fn [acc r]
            (if-let [dt (parse-dt (:date_of_interaction r) (:time_of_interaction r))]
              (if (night-periods (solar-period dt))
                (update acc :night inc)
                (update acc :day inc))
              acc))
          {:night 0 :day 0}
          incidents))

(defn passage-duration-hours
  "Hours between start and end, rolling overnight (end<=start) to the next day."
  [^LocalDateTime start ^LocalDateTime end]
  (let [start-i (.toInstant start ZoneOffset/UTC)
        end-i0  (.toInstant end ZoneOffset/UTC)
        end-i   (if (.isAfter end-i0 start-i) end-i0 (.plusSeconds end-i0 86400))]
    (/ (- (.getEpochSecond end-i) (.getEpochSecond start-i)) 3600.0)))

(defn passage-hours
  "For one passage, integrate duration into [day-hours night-hours] by sampling
   `solar-period` at regular steps (steps = max(24, 2*duration_hrs))."
  [^LocalDateTime start ^LocalDateTime end]
  (let [start-i (.toInstant start ZoneOffset/UTC)
        end-i0  (.toInstant end ZoneOffset/UTC)
        ;; overnight passages where end <= start roll to next day
        end-i   (if (.isAfter end-i0 start-i) end-i0 (.plusSeconds end-i0 86400))
        dur-s   (- (.getEpochSecond end-i) (.getEpochSecond start-i))
        dur-h   (/ dur-s 3600.0)]
    (if (<= dur-s 0)
      [0.0 0.0]
      (let [steps   (max 24 (int (* dur-h 2)))
            step-s  (/ (double dur-s) steps)
            night-n (reduce (fn [n i]
                              (let [t (.plusSeconds start-i (long (* i step-s)))
                                    dt (LocalDateTime/ofInstant t ZoneOffset/UTC)]
                                (if (night-periods (solar-period dt)) (inc n) n)))
                            0 (range steps))
            night-frac (/ (double night-n) steps)]
        [(* (- 1.0 night-frac) dur-h) (* night-frac dur-h)]))))

(def default-max-passage-hours
  "Passages longer than one week are data-entry errors (the raw data contains
   multi-month 'passages' up to ~3000 h) and are excluded from the exposure
   integration, consistent with the published ~12 200 yacht-hour total."
  168.0)

(defn exposure
  "Total uneventful yacht-hours split into {:day T :night T}. Passages whose
   duration exceeds `max-hours` are dropped as data errors."
  ([uneventful] (exposure uneventful default-max-passage-hours))
  ([uneventful max-hours]
   (reduce (fn [acc r]
             (if-let [start (parse-dt (:date_passage_commenced r)
                                      (:time_passage_commenced r))]
               (if-let [end (parse-dt (:date_passage_ended r)
                                      (:time_passage_ended r))]
                 (if (<= (passage-duration-hours start end) max-hours)
                   (let [[dh nh] (passage-hours start end)]
                     (-> acc (update :day + dh) (update :night + nh)))
                   acc)
                 acc)
               acc))
           {:day 0.0 :night 0.0}
           uneventful)))

(defn rate-model-data
  "Assemble Poisson model data from raw reports."
  [raw]
  (let [incidents (filter #(= "incident" (:report_type %)) raw)
        uneventful (filter #(= "uneventful" (:report_type %)) raw)
        {yn :night yd :day} (incident-counts incidents)
        {tn :night td :day} (exposure uneventful)]
    {:y_night yn :y_day yd
     :T_night (/ (Math/round (* tn 1.0)) 1.0)
     :T_day (/ (Math/round (* td 1.0)) 1.0)}))

(defn fit
  "Fit the exposure rate model; return data + posterior summary of rate_ratio."
  [{:keys [raw-path n-chains seed]
    :or {raw-path "../orca_data/all_reports_detailed.json"
         n-chains 4 seed 42}}]
  (let [raw  (util/read-json raw-path)
        data (rate-model-data raw)
        draws (stan/sample "stan/rate.stan" data
                           {:n-chains n-chains :seed seed
                            :num-warmup 1000 :num-samples 2000
                            :out-dir "out/rate"})
        rr (vec (draws "rate_ratio"))]
    {:data data
     :rate-ratio {:median (util/quantile rr 0.5)
                  :mean (util/mean rr)
                  :ci89 [(util/quantile rr 0.055) (util/quantile rr 0.945)]
                  :p-night-lower (/ (double (count (filter #(< % 1.0) rr))) (count rr))}}))
