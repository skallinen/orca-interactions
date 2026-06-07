(ns orca.eda
  "Exploratory data analysis: distribution comparison.

   The \"look at the data before modeling\" step (McElreath): compare predictor
   distributions between the incident and uneventful groups and show the
   interaction rate within each category. This is what the logistic regression is
   actually detecting, before it disentangles the confounded raw differences.

   Produces stratified frequency tables + interaction rates for ordinal,
   categorical, and binary predictors (printed to console), and saves three
   figures via orca.plot (content parity with the committed reference figures, not
   pixel parity):
     - distribution_ordinal.png    — interaction rate per ordinal level;
     - distribution_categorical.png — interaction rate per category;
     - distribution_binary.png     — interaction rate per binary level."
  (:require
   [orca.config :as config]
   [orca.plot :as plot]
   [orca.prepare :as prep]
   [orca.util :as util]
   [tablecloth.api :as tc]))

;; ── predictor groupings ───────────────────────────────────────────────────────

(def ordinal-vars
  "[col label {level→bin-label}] for the ordinal predictors."
  [[:boat_length_ord "Boat Length" {0 "<10m" 1 "10-12.5m" 2 "12.5-15m" 3 ">15m"}]
   [:depth_ord "Depth" {0 "<20m" 1 "20-40m" 2 "40-200m" 3 ">200m"}]
   [:distance_ord "Distance (nm)" {0 "0-2" 1 "2-5" 2 "5-10" 3 ">10"}]
   [:speed_ord "Speed (kts)" {0 "0-2" 1 "3-4" 2 "5-7" 3 "8-11"}]
   [:wind_ord "Wind (Beaufort)" {0 "F0-2" 1 "F3-4" 2 "F5-6" 3 "F7+"}]
   [:sea_state_ord "Sea State" {0 "Calm" 1 "Moderate" 2 "Rough"}]
   [:cloud_cover_ord "Cloud Cover" {0 "0-25%" 1 "25-50%" 2 "50-75%" 3 "75-100%"}]])

(def categorical-vars
  "[col label cat-key] for the index-coded categorical predictors."
  [[:antifoul_idx "Antifoul Colour" :antifoul]
   [:sailing_mode_idx "Sailing Mode" :sailing_mode]
   [:rudder_idx "Rudder Type" :rudder]
   [:hull_colour_idx "Hull Colour" :hull_colour]])

(def binary-vars
  "[col label] for the binary predictors."
  [[:is_daytime "Day vs Night"]
   [:autopilot_on "Autopilot"]
   [:is_spring_tide "Spring Tide"]
   [:is_towing "Towing"]])

;; ── rate computations ─────────────────────────────────────────────────────────
;;
;; All three breakdowns delegate to orca.util/group-rates (group-by the column,
;; aggregate to {:n :incidents :uneventful :rate}); they differ only in how the
;; group values are keyed/labelled and which groups are kept.

(defn level-rates
  "For ordinal/numeric column `col`, a row per present level (sorted):
   {:level :n :incidents :rate}. nil values dropped."
  [data col]
  (let [by (util/group-rates data col)]
    (mapv (fn [lv]
            (assoc (select-keys (by lv) [:n :incidents :rate]) :level lv))
          (sort (map long (keys by))))))

(defn category-rates
  "For categorical index column `col` with `cats`, a row per non-Unknown category
   {:category :n :incidents :rate}, dropping empty categories."
  [data col cats]
  (let [by (util/group-rates data col)]
    (->> (map-indexed vector cats)
         (keep (fn [[i category]]
                 (when-let [r (and (not= category "Unknown") (by (long i)))]
                   (assoc (select-keys r [:n :incidents :rate]) :category category))))
         vec)))

(defn binary-rates
  "For binary column `col`, rows for level 0 then 1 {:level :n :incidents :rate}.
   Levels absent from the data are dropped."
  [data col]
  (let [by (util/group-rates data col)]
    (->> [0 1]
         (keep (fn [lv]
                 (when-let [r (by lv)]
                   (assoc (select-keys r [:n :incidents :rate]) :level lv))))
         vec)))

(defn overall-rate
  "Overall interaction rate = incidents / total rows."
  [data]
  (util/mean (data :interaction)))

;; ── printing ──────────────────────────────────────────────────────────────────

(defn- print-rate-line [label n rate]
  (println (format "    %-12s n=%4d  rate=%5.1f%%" label n (* 100 rate))))

;; ── plots ──────────────────────────────────────────────────────────────────────

(defn ordinal-plot
  "Save a bar chart of interaction rate per ordinal level, one bar per
   (variable, level), labelled 'var:bin'. Content parity with the ordinal panel."
  [data path]
  (let [pairs (for [[col label vmap] ordinal-vars
                    {:keys [level rate]} (level-rates data col)]
                [(str label "/" (get vmap level (str level))) rate])]
    (plot/bar path (mapv first pairs) (mapv second pairs)
              {:title "Interaction rate by ordinal level"
               :y-label "interaction rate"})))

(defn categorical-plot
  "Save a bar chart of interaction rate per category (across all categorical
   predictors), sorted by rate descending."
  [data md path]
  (let [rows  (for [[col label cat-key] categorical-vars
                    r (category-rates data col (get-in md [:categories cat-key]))]
                [(str label "/" (:category r) " (n=" (:n r) ")") (:rate r)])
        rows  (sort-by (comp - second) rows)]
    (plot/bar path (mapv first rows) (mapv second rows)
              {:title "Interaction rate by category" :y-label "interaction rate"})))

(defn binary-plot
  "Save a bar chart of interaction rate per binary level."
  [data path]
  (let [rows (for [[col label] binary-vars
                   {:keys [level rate]} (binary-rates data col)]
               [(str label "=" level) rate])]
    (plot/bar path (mapv first rows) (mapv second rows)
              {:title "Interaction rate by binary level"
               :y-label "interaction rate"})))

;; ── orchestration ──────────────────────────────────────────────────────────────

(defn run
  "Print the stratified distribution tables (ordinal / categorical / binary
   interaction rates) and, unless `:plots?` false, save distribution_ordinal.png /
   distribution_categorical.png / distribution_binary.png. Returns a summary map."
  [& [{:keys [plots?] :or {plots? true}}]]
  (let [{:keys [data metadata]} (prep/prepare (util/read-json (config/cfg :paths :raw)))
        md   metadata
        n-inc (tc/row-count (tc/select-rows data #(= 1 (:interaction %))))
        n-une (- (tc/row-count data) n-inc)
        base (overall-rate data)]
    (println "══ EDA: distribution comparison ══")
    (println (format "  Incidents=%d  Uneventful=%d  overall rate=%.1f%%"
                     n-inc n-une (* 100 base)))

    (println "\n█ ORDINAL — interaction rate by level:")
    (doseq [[col label vmap] ordinal-vars]
      (println (str "  " label ":"))
      (doseq [{:keys [level n rate]} (level-rates data col)]
        (print-rate-line (get vmap level (str level)) n rate)))

    (println "\n█ CATEGORICAL — interaction rate by category:")
    (doseq [[col label cat-key] categorical-vars]
      (println (str "  " label ":"))
      (doseq [{:keys [category n rate]}
              (sort-by (comp - :rate) (category-rates data col (get-in md [:categories cat-key])))]
        (print-rate-line category n rate)))

    (println "\n█ BINARY — interaction rate by level:")
    (doseq [[col label] binary-vars]
      (println (str "  " label ":"))
      (doseq [{:keys [level n rate]} (binary-rates data col)]
        (print-rate-line (str "=" level) n rate)))

    (when plots?
      (println "\n█ Saving plots…")
      (ordinal-plot data (config/results-path "distribution_ordinal.png"))
      (categorical-plot data md (config/results-path "distribution_categorical.png"))
      (binary-plot data (config/results-path "distribution_binary.png"))
      (println "  saved distribution_ordinal / distribution_categorical / distribution_binary"))

    {:incidents n-inc :uneventful n-une :overall-rate base
     :ordinal (into {} (for [[col _ _] ordinal-vars] [col (level-rates data col)]))
     :categorical (into {} (for [[col _ ck] categorical-vars]
                             [col (category-rates data col (get-in md [:categories ck]))]))
     :binary (into {} (for [[col _] binary-vars] [col (binary-rates data col)]))}))
