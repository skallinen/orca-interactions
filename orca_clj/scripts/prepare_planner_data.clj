#!/usr/bin/env bb
;; prepare_planner_data.clj
;;
;; M1 data-preparation for the orca route-risk planner.
;;
;; Reads  orca_data/all_reports_detailed.json  (a JSON ARRAY of 654 report
;; objects, NOT a map) and writes two EDN files for the next modelling phase:
;;
;;   route-planner/data/planner_dataset.edn  -- {:incidents [...] :uneventful [...]}
;;   route-planner/data/harbor_strings.edn   -- sorted vector of distinct harbor names
;;
;; Run from the repo root:
;;   bb orca_clj/scripts/prepare_planner_data.clj
;;
;; Design notes:
;;  - Incidents carry coordinates in :summary_lat / :summary_long (decimal
;;    degrees, longitude negative for west). All 216 verified to have numeric
;;    summary coords. As a fallback we reconstruct from the redundant
;;    :lat_deg/:lat_min/:long_deg/:long_min strings (longitude negated, since
;;    the whole study region is west of Greenwich).
;;  - Uneventful reports have NO coordinates but carry origin/destination
;;    harbor name strings, a passage date, length in nautical miles, and an
;;    :autopilot flag. Their coordinates are resolved later via the geocoded
;;    harbor table (harbor_coords.edn).
;;  - Raw attribute strings are kept verbatim; the next phase maps them to
;;    ordinals. We only parse coordinates, day-of-year, and passage length.

(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def input-path "orca_data/all_reports_detailed.json")
(def out-dataset "route-planner/data/planner_dataset.edn")
(def out-harbors "route-planner/data/harbor_strings.edn")

(defn elog
  "Log a line to stderr."
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn blank-str?
  "True when v is nil or, as a string, trims to empty."
  [v]
  (or (nil? v) (str/blank? (str v))))

(defn parse-num
  "Parse v as a double, tolerating surrounding whitespace and a trailing unit
   suffix (e.g. \"180\", \" 42.5 \", \"2009 nm\"). Returns nil when no leading
   numeric token is present."
  [v]
  (when-not (blank-str? v)
    (let [m (re-find #"[-+]?[0-9]*\.?[0-9]+" (str v))]
      (when m
        (try (Double/parseDouble m) (catch Exception _ nil))))))

(defn doy
  "Day-of-year (1-366) for an ISO date string like \"2022-06-18\", or nil when
   the value is missing/unparseable."
  [date-str]
  (when-not (blank-str? date-str)
    (try
      (.getDayOfYear (java.time.LocalDate/parse (str/trim (str date-str))))
      (catch Exception _ nil))))

(defn nilify
  "Return nil for blank strings, otherwise the trimmed string."
  [v]
  (when-not (blank-str? v)
    (str/trim (str v))))

(defn incident-coords
  "Resolve [lat lon] for an incident. Prefers numeric :summary_lat/:summary_long;
   falls back to reconstructing from deg+min strings (longitude negated, west)."
  [r]
  (let [slat (:summary_lat r)
        slon (:summary_long r)]
    (if (and (number? slat) (number? slon))
      [(double slat) (double slon)]
      (let [lat-deg (parse-num (:lat_deg r))
            lat-min (parse-num (:lat_min r))
            lon-deg (parse-num (:long_deg r))
            lon-min (parse-num (:long_min r))]
        (when (and lat-deg lon-deg)
          [(+ lat-deg (/ (or lat-min 0.0) 60.0))
           (- (+ lon-deg (/ (or lon-min 0.0) 60.0)))])))))

(defn ->incident
  [r]
  (let [[lat lon] (incident-coords r)
        date (nilify (:date_of_interaction r))]
    {:lat lat
     :lon lon
     :doy (doy date)
     :date date
     :depth (nilify (:depth r))
     :distance (nilify (:distance_off_land r))
     :sailing (nilify (:motoring_or_sailing r))
     :antifoul (nilify (:antifoul_colour r))
     :hull (nilify (:hull_topsides_colour r))
     :rudder (nilify (:rudder r))
     :speed (nilify (:speed r))
     :length (nilify (:boat_length r))
     :wind (nilify (:wind_speed r))
     :sea (nilify (:sea_state r))
     ;; incidents do not record autopilot
     :autopilot nil}))

(defn ->uneventful
  [r]
  (let [date (nilify (:date_passage_commenced r))]
    {:start (nilify (:where_passage_commenced r))
     :end (nilify (:where_passage_ended r))
     :doy (doy date)
     :date date
     :length-nm (parse-num (:length_of_passage r))
     :depth (nilify (:depth r))
     :distance (nilify (:distance_off_land r))
     :sailing (nilify (:motoring_or_sailing r))
     :antifoul (nilify (:antifoul_colour r))
     :hull (nilify (:hull_topsides_colour r))
     :rudder (nilify (:rudder r))
     :speed (nilify (:speed r))
     :length-boat (nilify (:boat_length r))
     :wind (nilify (:wind_speed r))
     :sea (nilify (:sea_state r))
     :autopilot (nilify (:autopilot r))}))

(defn -main []
  (let [reports (json/parse-string (slurp input-path) true)
        _ (when-not (sequential? reports)
            (throw (ex-info "Top level of JSON is not a list" {})))
        incs-raw (filter #(= "incident" (:report_type %)) reports)
        uns-raw (filter #(= "uneventful" (:report_type %)) reports)
        incidents (mapv ->incident incs-raw)
        uneventful (mapv ->uneventful uns-raw)
        ;; coordinate / validity checks
        bad-coords (filterv #(or (nil? (:lat %)) (nil? (:lon %))) incidents)
        inc-no-doy (count (filter #(nil? (:doy %)) incidents))
        un-no-doy (count (filter #(nil? (:doy %)) uneventful))
        un-no-len (count (filter #(nil? (:length-nm %)) uneventful))
        un-no-start (count (filter #(nil? (:start %)) uneventful))
        un-no-end (count (filter #(nil? (:end %)) uneventful))
        un-both-harbors (count (filter #(and (:start %) (:end %)) uneventful))
        un-no-autopilot (count (filter #(nil? (:autopilot %)) uneventful))
        harbor-strings (->> uneventful
                            (mapcat (juxt :start :end))
                            (remove nil?)
                            distinct
                            sort
                            vec)]
    ;; --- stderr logging ---
    (elog "=== prepare_planner_data ===")
    (elog "total reports          :" (count reports))
    (elog "incidents              :" (count incidents))
    (elog "  with numeric coords  :" (- (count incidents) (count bad-coords)))
    (elog "  MISSING coords       :" (count bad-coords))
    (when (seq bad-coords)
      (elog "  !! incidents missing coords (date):"
            (mapv :date bad-coords)))
    (elog "  with null :doy       :" inc-no-doy)
    (elog "uneventful             :" (count uneventful))
    (elog "  with both harbors    :" un-both-harbors)
    (elog "  missing :start       :" un-no-start)
    (elog "  missing :end         :" un-no-end)
    (elog "  with null :doy       :" un-no-doy)
    (elog "  with null :length-nm :" un-no-len)
    (elog "  with null :autopilot :" un-no-autopilot)
    (elog "distinct harbor strings:" (count harbor-strings))
    ;; --- write outputs ---
    (io/make-parents out-dataset)
    (spit out-dataset
          (with-out-str
            (binding [*print-length* nil]
              (prn {:incidents incidents :uneventful uneventful}))))
    (spit out-harbors
          (with-out-str
            (binding [*print-length* nil]
              (prn harbor-strings))))
    ;; sanity round-trip of the harbor file
    (let [check (edn/read-string (slurp out-harbors))]
      (elog "wrote" out-dataset)
      (elog "wrote" out-harbors "(" (count check) "strings )"))))

(-main)
