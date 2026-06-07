(ns orca.timeofday
  "Exposure-based time-of-day analysis (methodology.html §7), using the
   `commons-suncalc` Java library for solar times.

   Classifies each incident's moment as Day/Night/Dawn/Dusk by solar position at
   the orca zone (37N, 8W), and integrates each uneventful passage's duration
   into day vs night yacht-hours. Then fits a Poisson incident-rate model and
   reports the night/day rate ratio."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [orca.stan :as stan]
   [orca.util :as util])
  (:import
   (java.time Instant LocalDate LocalDateTime LocalTime ZoneOffset)
   (java.time.format DateTimeFormatter)
   (org.shredzone.commons.suncalc SunTimes SunTimes$Twilight)))

(def lat (config/cfg :zone :lat))
(def lon (config/cfg :zone :lon))

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

(defn- inst ^Instant [zdt] (when zdt (.toInstant zdt)))

(defn solar-period
  "Classify a UTC `LocalDateTime` as :night/:dawn/:day/:dusk using civil-twilight
   (dawn/dusk) and visual (sunrise/sunset) boundaries."
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
  "Passages longer than this (hours) are data-entry errors (the raw data contains
   multi-month 'passages' up to ~3000 h) and are excluded from the exposure
   integration. The 200 h (~8-day) cut reproduces the published exposure totals
   (night 4,270 / day 7,944 ≈ 12 200 yacht-hours, 11.7 / 20.9 per 1,000 h) while
   preserving the load-bearing night/day rate ratio. From config.edn
   :max-passage-hours."
  (config/cfg :max-passage-hours))

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

(defn incident-counts-4
  "Classify each incident by solar period (night = Night|Dusk) and by whether
   `present?` holds for the report; return {:pd :pn :ad :an} counts (p = factor
   present, a = absent; d = day, n = night)."
  [incidents present?]
  (reduce (fn [acc r]
            (if-let [dt (parse-dt (:date_of_interaction r) (:time_of_interaction r))]
              (let [night? (night-periods (solar-period dt))
                    p?     (boolean (present? r))
                    k      (cond (and p? night?)             :pn
                                 p?                          :pd
                                 night?                      :an
                                 :else                       :ad)]
                (update acc k inc))
              acc))
          {:pd 0 :pn 0 :ad 0 :an 0}
          incidents))

(defn exposure-4
  "Total uneventful yacht-hours split into the four cells {:T_pd :T_pn :T_ad
   :T_an} by day/night and by `present?`. Passages whose duration exceeds
   `max-hours` are dropped as data errors (same rule as `exposure`)."
  ([uneventful present?] (exposure-4 uneventful present? default-max-passage-hours))
  ([uneventful present? max-hours]
   (reduce (fn [acc r]
             (if-let [start (parse-dt (:date_passage_commenced r)
                                      (:time_passage_commenced r))]
               (if-let [end (parse-dt (:date_passage_ended r)
                                      (:time_passage_ended r))]
                 (if (<= (passage-duration-hours start end) max-hours)
                   (let [[dh nh] (passage-hours start end)
                         [dk nk] (if (present? r) [:T_pd :T_pn] [:T_ad :T_an])]
                     (-> acc (update dk + dh) (update nk + nh)))
                   acc)
                 acc)
               acc))
           {:T_pd 0.0 :T_pn 0.0 :T_ad 0.0 :T_an 0.0}
           uneventful)))

(defn interaction-model-data
  "Assemble the 8-value 4-rate model data from raw reports for factor `present?`.
   Yacht-hours are rounded like `rate-model-data`."
  [raw present?]
  (let [incidents  (filter #(= "incident" (:report_type %)) raw)
        uneventful (filter #(= "uneventful" (:report_type %)) raw)
        {:keys [pd pn ad an]} (incident-counts-4 incidents present?)
        {:keys [T_pd T_pn T_ad T_an]} (exposure-4 uneventful present?)
        rnd #(/ (Math/round (* (double %) 1.0)) 1.0)]
    {:y_pd pd :y_pn pn :y_ad ad :y_an an
     :T_pd (rnd T_pd) :T_pn (rnd T_pn) :T_ad (rnd T_ad) :T_an (rnd T_an)}))

(def black-antifoul?
  "Factor predicate: the report's antifoul colour is Black."
  (fn [r] (= "Black" (:antifoul_colour r))))

(def motoring?
  "Factor predicate: the report's propulsion is Motoring."
  (fn [r] (= "Motoring" (:motoring_or_sailing r))))

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
    :or {raw-path (config/cfg :paths :raw)
         n-chains (config/cfg :mcmc :n-chains)
         seed (config/cfg :mcmc :seed)}}]
  (let [raw  (util/read-json raw-path)
        data (rate-model-data raw)
        draws (stan/sample "stan/rate.stan" data
                           {:n-chains n-chains :seed seed
                            :num-warmup (config/cfg :mcmc :num-warmup)
                            :num-samples (config/cfg :mcmc :num-samples)
                            :out-dir (config/cfg :paths :rate-out-dir)})
        rr (vec (draws "rate_ratio"))]
    {:data data
     :rate-ratio {:median (util/quantile rr 0.5)
                  :mean (util/mean rr)
                  :ci89 [(util/quantile rr 0.055) (util/quantile rr 0.945)]
                  :p-night-lower (/ (double (count (filter #(< % 1.0) rr))) (count rr))}}))

(defn- summary
  "Posterior summary of a draws vector: median, mean, 89% ETI."
  [draws]
  {:median (util/quantile draws 0.5)
   :mean (util/mean draws)
   :ci89 [(util/quantile draws 0.055) (util/quantile draws 0.945)]})

(defn fit-interaction
  "Fit the 4-rate time-of-day interaction model for factor `present?` (a
   predicate on a raw report). Returns the data plus posterior summaries of the
   day rate ratio, night rate ratio, and their interaction (night_rr/day_rr).
   The interaction summary also carries :p-ratio-above-1, the fraction of draws
   above 1.0 (an interaction > 1 means the factor's effect is stronger at
   night)."
  [present? {:keys [raw-path n-chains seed]
             :or {raw-path (config/cfg :paths :raw)
                  n-chains (config/cfg :mcmc :n-chains)
                  seed (config/cfg :mcmc :seed)}}]
  (let [raw   (util/read-json raw-path)
        data  (interaction-model-data raw present?)
        draws (stan/sample "stan/rate4.stan" data
                           {:n-chains n-chains :seed seed
                            :num-warmup (config/cfg :mcmc :num-warmup)
                            :num-samples (config/cfg :mcmc :num-samples)
                            :out-dir (config/cfg :paths :rate-out-dir)})
        inter (vec (draws "interaction"))]
    {:data data
     :day-rr (summary (vec (draws "day_rr")))
     :night-rr (summary (vec (draws "night_rr")))
     :interaction (assoc (summary inter)
                         :p-ratio-above-1
                         (/ (double (count (filter #(> % 1.0) inter))) (count inter)))}))

(defn- print-interaction
  "Print one factor's day_rr, night_rr, and interaction (mean + 89% CI)."
  [label {:keys [data day-rr night-rr interaction]}]
  (println (format "  %s" label))
  (println "    cells:" data)
  (println (format "    day_rr     mean %.2f  89%% CI [%.2f, %.2f]"
                   (:mean day-rr) (first (:ci89 day-rr)) (second (:ci89 day-rr))))
  (println (format "    night_rr   mean %.2f  89%% CI [%.2f, %.2f]"
                   (:mean night-rr) (first (:ci89 night-rr)) (second (:ci89 night-rr))))
  (let [[lo hi] (:ci89 interaction)]
    (println (format "    interaction mean %.2f  89%% CI [%.2f, %.2f]  P(>1)=%.2f  CI spans 1.0: %s"
                     (:mean interaction) lo hi (:p-ratio-above-1 interaction)
                     (and (< lo 1.0) (> hi 1.0))))))

(defn interaction-report
  "Fit the 4-rate interaction model for black antifoul and motoring and print
   day_rr, night_rr, and the day/night interaction ratio for each. Returns a map
   {:black ... :motoring ...} of the fit results."
  ([] (interaction-report {}))
  ([opts]
   (let [black    (fit-interaction black-antifoul? opts)
         motoring (fit-interaction motoring? opts)]
     (println "Time-of-day interaction tests (4-rate Poisson, methodology.html §7)")
     (print-interaction "Black antifoul" black)
     (print-interaction "Motoring" motoring)
     {:black black :motoring motoring})))
