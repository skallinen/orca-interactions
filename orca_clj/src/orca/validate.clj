(ns orca.validate
  "Validate the Clojure reproduction against the committed Python artifacts.

   Oracles (never re-run Python, just read its outputs):
     - bayesian_orca/data/modeling_data.csv + metadata.json  (data prep)
     - blogpost/posterior_draws.json                         (M3 posterior)"
  (:require
   [orca.config :as config]
   [orca.prepare :as prep]
   [orca.util :as util]
   [tablecloth.api :as tc]))

(defn- nan? [x]
  (or (nil? x) (and (number? x) (Double/isNaN (double x)))))

(defn- col-eq? [a b tol]
  (every? true?
          (map (fn [x y]
                 (cond (and (nan? x) (nan? y)) true
                       (or (nan? x) (nan? y)) false
                       :else (< (abs (- (double x) (double y))) tol)))
               a b)))

(defn validate-prep
  "Compare the tablecloth data prep against modeling_data.csv + metadata.json.
   Returns {:pass? bool :details {...}}."
  [{:keys [raw-path csv-path meta-path]
    :or {raw-path (config/cfg :paths :raw)
         csv-path (config/cfg :paths :modeling-csv)
         meta-path (config/cfg :paths :metadata-json)}}]
  (let [{:keys [data metadata]} (prep/prepare (util/read-json raw-path))
        py-csv  (tc/dataset csv-path)
        py-meta (util/read-json meta-path)
        cols [:boat_length_ord_std :depth_ord_std :distance_ord_std :speed_ord_std
              :wind_ord_std :sea_state_ord_std :boat_type_idx :rudder_idx
              :antifoul_idx :sailing_mode_idx :hull_colour_idx :autopilot_on
              :interaction]
        order-ok (= (mapv str (data :report_id))
                    (mapv #(str (long %)) (py-csv "report_id")))
        col-results (into {} (for [c cols]
                               [c (col-eq? (data c) (py-csv (name c)) 1e-6)]))
        cats-ok (= (:categories metadata) (:categories py-meta))
        std-ok (every? (fn [[k v]]
                         (let [p (get-in py-meta [:standardization k])]
                           (and (< (abs (- (:mean v) (:mean p))) 1e-9)
                                (< (abs (- (:sd v) (:sd p))) 1e-9))))
                       (:standardization metadata))]
    {:pass? (and order-ok cats-ok std-ok (every? true? (vals col-results)))
     :details {:row-order order-ok :categories cats-ok :standardization std-ok
               :columns col-results}}))

(defn summary
  "Per-layout-slot {:mean :sd} for a posterior_draws.json map."
  [draws-map]
  (let [layout (:layout draws-map)]
    (into {} (map-indexed
               (fn [j nm]
                 (let [c (mapv #(nth % j) (:draws draws-map))]
                   [nm {:mean (util/mean c) :sd (util/pstdev c)}]))
               layout))))

(defn validate-posterior
  "Compare two posterior_draws.json summaries. A slot passes if the mean
   difference is small in absolute terms AND relative to the posterior sd
   (run-to-run NUTS noise on weakly-identified offsets). Returns
   {:pass? bool :max-dmean .. :max-rel .. :rows [...]}."
  [{:keys [clj-path py-path abs-tol rel-tol]
    :or {clj-path (config/cfg :paths :out-posterior)
         py-path (config/cfg :paths :py-posterior)
         abs-tol (config/cfg :validate :abs-tol)
         rel-tol (config/cfg :validate :rel-tol)}}]
  (let [clj (summary (util/read-json clj-path))
        py  (summary (util/read-json py-path))
        rows (for [nm (keys py)]
               (let [{cm :mean cs :sd} (clj nm)
                     {pm :mean} (py nm)
                     dmean (- cm pm)
                     rel (/ (abs dmean) (max cs 1e-9))]
                 {:param nm :clj-mean cm :py-mean pm :dmean dmean :rel rel
                  :pass? (or (< (abs dmean) abs-tol) (< rel rel-tol))}))]
    {:pass? (every? :pass? rows)
     :max-dmean (apply max (map (comp abs :dmean) rows))
     :max-rel (apply max (map :rel rows))
     :rows (vec rows)}))

(defn validate-all []
  (let [p (validate-prep {})
        q (validate-posterior {})]
    {:data-prep p
     :posterior (dissoc q :rows)
     :pass? (and (:pass? p) (:pass? q))}))
