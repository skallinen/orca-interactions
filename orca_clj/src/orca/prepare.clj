(ns orca.prepare
  "Data preparation with tablecloth.

   Produces the modeling-ready dataset + metadata (category lists and
   standardization params) from the raw scraped reports. Row order is
   `incidents + uneventful` (JSON order within each) so the output can be
   validated row-for-row against modeling_data.csv.

   The emitted columns cover the full 34-column `modeling_data.csv`: the binary
   outcome, date/month/year, raw + standardized ordinals (incl. cloud cover),
   index-coded categoricals, the binary predictors (daylight, autopilot,
   towing, spring tide, moon phase), moon illumination (raw + std), and the
   incident lat/long."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [tablecloth.api :as tc]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.functional :as dfn]))

;; --- Ordinal encoding maps (config.edn :ordinal-maps) ---
(def boat-length-map (config/cfg :ordinal-maps :boat-length))
(def depth-map       (config/cfg :ordinal-maps :depth))
(def distance-map    (config/cfg :ordinal-maps :distance))
(def speed-map       (config/cfg :ordinal-maps :speed))
(def wind-map        (config/cfg :ordinal-maps :wind))
(def sea-state-map   (config/cfg :ordinal-maps :sea-state))
(def cloud-cover-map (config/cfg :ordinal-maps :cloud-cover))

(defn- s
  "Trimmed string of a non-nil value; nil -> nil."
  [v]
  (when (some? v) (str/trim (str v))))

(defn- lower
  "Lower-cased trimmed string, or nil."
  [v]
  (some-> (s v) str/lower-case))

(defn encode-ordinal
  "Map raw value through `m` after trimming; nil/unknown -> nil (NaN)."
  [m v]
  (when-let [k (s v)] (get m k)))

(defn autopilot-on
  "1 if value contains 'on', 0 if 'off', else nil."
  [v]
  (when-let [t (lower v)]
    (cond (str/includes? t "on") 1
          (str/includes? t "off") 0
          :else nil)))

(defn harmonize-daylight
  "Collapse `darkness_or_daylight` to binary daytime: contains 'day' -> 1,
   present-but-not -> 0, missing -> nil. (Uneventful passages are multi-select,
   e.g. 'Dawn , Day , Dusk , Night'; any 'day' component counts as daytime.)"
  [v]
  (when-let [t (lower v)]
    (if (str/includes? t "day") 1 0)))

(defn parse-moon
  "Parse a moon string like 'waning<br>80% illuminated<br>within 3 days of full'
   into `[illumination waxing]`: illumination is the integer before '%' (nil if
   absent), waxing is 1 for waxing / 0 for waning / nil otherwise."
  [v]
  (if-let [t (lower v)]
    [(some-> (re-find #"(\d+)%" t) second parse-long)
     (cond (str/includes? t "waxing") 1
           (str/includes? t "waning") 0
           :else nil)]
    [nil nil]))

(defn spring-tide
  "Binary spring tide from the `tide` field: 'within 3 days' and not negated
   -> 1; any 'not' -> 0; missing -> nil."
  [v]
  (when-let [t (lower v)]
    (cond (and (str/includes? t "within 3 days") (not (str/includes? t "not"))) 1
          (str/includes? t "not") 0
          :else nil)))

(defn harmonize-towing
  "Unify incident `towing` and uneventful `trailing` into a binary `is_towing`.
   Rule: explicit 'not towing'/'no' -> 0, 'yes' or affirmative 'towing' -> 1,
   both blank -> nil, else 0."
  [row]
  (let [towing   (or (lower (:towing row)) "")
        trailing (or (lower (:trailing row)) "")]
    (cond
      (or (str/includes? towing "not towing")
          (= trailing "no") (= towing "no")) 0
      (or (= trailing "yes")
          (and (str/includes? towing "towing")
               (not (str/includes? towing "not")))) 1
      (and (contains? #{"" "nan"} towing)
           (contains? #{"" "nan"} trailing)) nil
      :else 0)))

(defn extract-date
  "First non-blank date string from `date_of_interaction` then
   `date_passage_commenced`."
  [row]
  (some (fn [k]
          (let [v (s (get row k))]
            (when (and v (not= "nan" v) (seq v)) v)))
        [:date_of_interaction :date_passage_commenced]))

(defn parse-date
  "Parse `extract-date` as an ISO `LocalDate`; returns nil on miss/parse error."
  [row]
  (when-let [d (extract-date row)]
    (try (java.time.LocalDate/parse d) (catch Exception _ nil))))

(defn index-column
  "Index-code a categorical: fill nil -> 'Unknown', trim, sorted-unique ->
   0-based codes. Returns [codes categories] where codes aligns with `vals`."
  [xs]
  (let [clean (mapv #(or (s %) "Unknown") xs)
        cats  (vec (sort (distinct clean)))
        idx   (zipmap cats (range))]
    [(mapv idx clean) cats]))

(defn standardize
  "z-score with sample sd (ddof=1) over non-nil values.
   Returns [standardized-vals mean sd]. Stats run on a primitive double[] via
   dtype-next; the standardized pass keeps nil in its original positions.

   Guards two degenerate inputs that would otherwise propagate NaN/Inf silently
   into the Stan data:
   - n ≤ 1 non-nil values: ddof=1 sd is undefined → asserts (a predictor column
     with at most one observed value is a data/encoding error here).
   - zero variance (constant column): every standardized value is 0.0 (the
     constant carries no signal) rather than 0/0 = NaN; sd is returned as 0.0."
  [xs]
  (let [present (-> (filterv some? xs) dtype/->double-array)
        n       (alength present)
        _       (assert (> n 1) "standardize needs >1 non-nil value (ddof=1)")
        m       (dfn/mean present)
        sd      (-> present (dfn/- m) dfn/sq dfn/sum (/ (dec n)) Math/sqrt)]
    (if (zero? sd)
      [(mapv #(when (some? %) 0.0) xs) m 0.0]
      [(mapv #(when (some? %) (/ (- % m) sd)) xs) m sd])))

(defn prepare
  "Build {:data <tablecloth ds> :metadata {...}} from raw reports (seq of maps)."
  [raw]
  (let [incidents  (filter #(= "incident" (:report_type %)) raw)
        uneventful (filter #(= "uneventful" (:report_type %)) raw)
        rows       (vec (concat incidents uneventful))
        col        (fn [k] (mapv k rows))
        ;; raw ordinals
        len-ord      (mapv #(encode-ordinal boat-length-map (:boat_length %)) rows)
        depth-ord    (mapv #(encode-ordinal depth-map (:depth %)) rows)
        dist-ord     (mapv #(encode-ordinal distance-map (:distance_off_land %)) rows)
        speed-ord    (mapv #(encode-ordinal speed-map (:speed %)) rows)
        wind-ord     (mapv #(encode-ordinal wind-map (:wind_speed %)) rows)
        sea-ord      (mapv #(encode-ordinal sea-state-map (:sea_state %)) rows)
        cloud-ord    (mapv #(encode-ordinal cloud-cover-map (:cloud_cover %)) rows)
        ;; moon: [illumination waxing]
        moon         (mapv #(parse-moon (:moon %)) rows)
        moon-illum   (mapv first moon)
        moon-waxing  (mapv second moon)
        ;; dates
        dates        (mapv parse-date rows)
        [len-std   lm lsd]   (standardize len-ord)
        [depth-std dm dsd]   (standardize depth-ord)
        [dist-std  dim disd] (standardize dist-ord)
        [speed-std sm ssd]   (standardize speed-ord)
        [wind-std  wm wsd]   (standardize wind-ord)
        [sea-std   sem sesd] (standardize sea-ord)
        [cloud-std cm csd]   (standardize cloud-ord)
        [moon-std  mm msd]   (standardize moon-illum)
        ;; index categoricals
        [boat-idx boat-cats]     (index-column (col :boat_type))
        [rudder-idx rudder-cats] (index-column (col :rudder))
        [anti-idx anti-cats]     (index-column (col :antifoul_colour))
        [sail-idx sail-cats]     (index-column (col :motoring_or_sailing))
        [hull-idx hull-cats]     (index-column (col :hull_topsides_colour))
        ds (tc/dataset
             {:interaction         (mapv #(if (= "incident" (:report_type %)) 1 0) rows)
              :report_id           (col :report_id)
              :report_type         (col :report_type)
              :date                (mapv #(some-> % str) dates)
              :month               (mapv #(some-> % .getMonthValue) dates)
              :year                (mapv #(some-> % .getYear) dates)
              :boat_length_ord     len-ord
              :depth_ord           depth-ord
              :distance_ord        dist-ord
              :speed_ord           speed-ord
              :wind_ord            wind-ord
              :sea_state_ord       sea-ord
              :cloud_cover_ord     cloud-ord
              :boat_length_ord_std len-std
              :depth_ord_std       depth-std
              :distance_ord_std    dist-std
              :speed_ord_std       speed-std
              :wind_ord_std        wind-std
              :sea_state_ord_std   sea-std
              :cloud_cover_ord_std cloud-std
              :boat_type_idx       boat-idx
              :rudder_idx          rudder-idx
              :antifoul_idx        anti-idx
              :sailing_mode_idx    sail-idx
              :hull_colour_idx     hull-idx
              :is_daytime          (mapv #(harmonize-daylight (:darkness_or_daylight %)) rows)
              :autopilot_on        (mapv #(autopilot-on (:autopilot %)) rows)
              :is_towing           (mapv harmonize-towing rows)
              :is_spring_tide      (mapv #(spring-tide (:tide %)) rows)
              :moon_waxing         moon-waxing
              :moon_illumination   moon-illum
              :moon_illumination_std moon-std
              :summary_lat         (col :summary_lat)
              :summary_long        (col :summary_long)})]
    {:data ds
     :metadata
     {:categories {:boat_type boat-cats :rudder rudder-cats :antifoul anti-cats
                   :sailing_mode sail-cats :hull_colour hull-cats}
      :standardization {:boat_length_ord {:mean lm :sd lsd}
                        :depth_ord {:mean dm :sd dsd}
                        :distance_ord {:mean dim :sd disd}
                        :speed_ord {:mean sm :sd ssd}
                        :wind_ord {:mean wm :sd wsd}
                        :sea_state_ord {:mean sem :sd sesd}
                        :cloud_cover_ord {:mean cm :sd csd}
                        :moon_illumination {:mean mm :sd msd}}
      :n_incidents (count incidents)
      :n_uneventful (count uneventful)}}))
