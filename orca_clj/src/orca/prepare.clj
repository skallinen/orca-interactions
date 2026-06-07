(ns orca.prepare
  "Port of bayesian_orca/prepare_data.py to tablecloth.

   Produces the modeling-ready dataset + metadata (category lists and
   standardization params) from the raw scraped reports. Row order matches the
   Python pipeline (`incidents + uneventful`, JSON order within each) so the
   output can be validated row-for-row against modeling_data.csv."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [tablecloth.api :as tc]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.functional :as dfn]))

;; --- Ordinal encoding maps (config.edn :ordinal-maps, from prepare_data.py) ---
(def boat-length-map (config/cfg :ordinal-maps :boat-length))
(def depth-map       (config/cfg :ordinal-maps :depth))
(def distance-map    (config/cfg :ordinal-maps :distance))
(def speed-map       (config/cfg :ordinal-maps :speed))
(def wind-map        (config/cfg :ordinal-maps :wind))
(def sea-state-map   (config/cfg :ordinal-maps :sea-state))

(defn- s [v] (when (some? v) (str/trim (str v))))

(defn encode-ordinal
  "Map raw value through `m` after trimming; nil/unknown -> nil (NaN)."
  [m v]
  (when-let [k (s v)] (get m k)))

(defn autopilot-on
  "1 if value contains 'on', 0 if 'off', else nil (matches prepare_data.py)."
  [v]
  (when-let [t (some-> (s v) str/lower-case)]
    (cond (str/includes? t "on") 1
          (str/includes? t "off") 0
          :else nil)))

(defn index-column
  "Index-code a categorical: fill nil -> 'Unknown', trim, sorted-unique ->
   0-based codes. Returns [codes categories] where codes aligns with `vals`."
  [xs]
  (let [clean (mapv #(or (s %) "Unknown") xs)
        cats  (vec (sort (distinct clean)))
        idx   (zipmap cats (range))]
    [(mapv idx clean) cats]))

(defn standardize
  "z-score with sample sd (ddof=1, matching pandas .std()) over non-nil values.
   Returns [standardized-vals mean sd]. Stats run on a primitive double[] via
   dtype-next; the final pass keeps nil in its original positions (-> NaN)."
  [xs]
  (let [present (-> (filterv some? xs) dtype/->double-array)
        n       (alength present)
        m       (dfn/mean present)
        sd      (-> present (dfn/- m) dfn/sq dfn/sum (/ (dec n)) Math/sqrt)]
    [(mapv #(when (some? %) (/ (- % m) sd)) xs) m sd]))

(defn prepare
  "Build {:data <tablecloth ds> :metadata {...}} from raw reports (seq of maps)."
  [raw]
  (let [incidents  (filter #(= "incident" (:report_type %)) raw)
        uneventful (filter #(= "uneventful" (:report_type %)) raw)
        rows       (vec (concat incidents uneventful))
        col        (fn [k] (mapv k rows))
        ;; raw ordinals
        depth-ord    (mapv #(encode-ordinal depth-map (:depth %)) rows)
        dist-ord     (mapv #(encode-ordinal distance-map (:distance_off_land %)) rows)
        speed-ord    (mapv #(encode-ordinal speed-map (:speed %)) rows)
        len-ord      (mapv #(encode-ordinal boat-length-map (:boat_length %)) rows)
        wind-ord     (mapv #(encode-ordinal wind-map (:wind_speed %)) rows)
        sea-ord      (mapv #(encode-ordinal sea-state-map (:sea_state %)) rows)
        [depth-std dm dsd] (standardize depth-ord)
        [dist-std  dim disd] (standardize dist-ord)
        [speed-std sm ssd] (standardize speed-ord)
        [len-std   lm lsd] (standardize len-ord)
        [wind-std  wm wsd] (standardize wind-ord)
        [sea-std   sem sesd] (standardize sea-ord)
        ;; index categoricals
        [boat-idx boat-cats]     (index-column (col :boat_type))
        [rudder-idx rudder-cats] (index-column (col :rudder))
        [anti-idx anti-cats]     (index-column (col :antifoul_colour))
        [sail-idx sail-cats]     (index-column (col :motoring_or_sailing))
        [hull-idx hull-cats]     (index-column (col :hull_topsides_colour))
        ds (tc/dataset
             {:interaction        (mapv #(if (= "incident" (:report_type %)) 1 0) rows)
              :report_id          (col :report_id)
              :report_type        (col :report_type)
              :boat_length_ord_std len-std
              :depth_ord_std       depth-std
              :distance_ord_std    dist-std
              :speed_ord_std       speed-std
              :wind_ord_std        wind-std
              :sea_state_ord_std   sea-std
              :boat_type_idx       boat-idx
              :rudder_idx          rudder-idx
              :antifoul_idx        anti-idx
              :sailing_mode_idx    sail-idx
              :hull_colour_idx     hull-idx
              :autopilot_on        (mapv #(autopilot-on (:autopilot %)) rows)})]
    {:data ds
     :metadata
     {:categories {:boat_type boat-cats :rudder rudder-cats :antifoul anti-cats
                   :sailing_mode sail-cats :hull_colour hull-cats}
      :standardization {:boat_length_ord {:mean lm :sd lsd}
                        :depth_ord {:mean dm :sd dsd}
                        :distance_ord {:mean dim :sd disd}
                        :speed_ord {:mean sm :sd ssd}
                        :wind_ord {:mean wm :sd wsd}
                        :sea_state_ord {:mean sem :sd sesd}}
      :n_incidents (count incidents)
      :n_uneventful (count uneventful)}}))
