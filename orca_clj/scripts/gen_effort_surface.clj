#!/usr/bin/env bb
;; Phase M2 route-planner: build the sailing-EFFORT surface.
;;
;; Why: the old model sampled background (pseudo-absence) points uniformly over
;; ALL sea cells, including the empty open Atlantic nobody sails, which made the
;; incident hotspots look infinitely more dangerous than open water (runaway
;; 100% risk). Fix (locked decision I2.5/I2.6 in ROUTE_PLANNER_PLAN.md): build
;; an effort surface = where boats actually sail, by reconstructing the
;; uneventful reporters' harbor-to-harbor great-circle passages, and sample the
;; background PROPORTIONAL to effort.
;;
;; Inputs (plain text / EDN / JSON only):
;;   - route-planner/data/planner_dataset.edn   {:incidents [...] :uneventful [...]}
;;   - route-planner/data/harbor_coords.edn      {<harbor> {:lat :lon ...}}
;;   - route-planner/geo_grid.json               sea cells only (land omitted)
;; Outputs:
;;   - route-planner/data/effort_grid.json       per-cell passage-miles
;;   - route-planner/data/background_sample.edn  N=3000 effort-weighted points
;;
;; NOTE on Babashka/SCI: keep hot loops in dedicated top-level fns. A
;; multi-level loop/recur with several bound locals inside a big enclosing
;; closure once stalled SCI for 67 minutes. Same precaution as gen_geo_grid.clj.

(ns gen-effort-surface
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------
(def lat-min 25.0)
(def lat-max 50.0)
(def lon-min -20.0)
(def lon-max 5.0)
(def res 0.1)

(def repo "/Users/samikallinen/common/projects/orca-interactions")
(def dataset-path (str repo "/route-planner/data/planner_dataset.edn"))
(def harbor-path (str repo "/route-planner/data/harbor_coords.edn"))
(def geo-grid-path (str repo "/route-planner/geo_grid.json"))
(def effort-out (str repo "/route-planner/data/effort_grid.json"))
(def bg-out (str repo "/route-planner/data/background_sample.edn"))

(def step-nm 5.0)                 ;; great-circle interpolation step
(def snap-radius-cells 5)         ;; up to ~0.5 deg search for nearest sea cell
(def max-plausible-nm 3000.0)     ;; reject implausible :length-nm
(def n-background 3000)
(def rng-seed 42)

;; ---------------------------------------------------------------------------
;; Geometry
;; ---------------------------------------------------------------------------
(def earth-r-km 6371.0088)
(def km-per-nm 1.852)

(defn deg->rad ^double [^double d] (* d (/ Math/PI 180.0)))
(defn rad->deg ^double [^double r] (* r (/ 180.0 Math/PI)))

(defn haversine-nm
  ^double [^double lat1 ^double lon1 ^double lat2 ^double lon2]
  (let [p1 (deg->rad lat1)
        p2 (deg->rad lat2)
        dp (deg->rad (- lat2 lat1))
        dl (deg->rad (- lon2 lon1))
        a  (+ (* (Math/sin (/ dp 2)) (Math/sin (/ dp 2)))
              (* (Math/cos p1) (Math/cos p2)
                 (Math/sin (/ dl 2)) (Math/sin (/ dl 2))))
        c  (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (/ (* earth-r-km c) km-per-nm)))

(defn cell-centre
  "Centre lat/lon (deg) of the integer-tenths cell key."
  [k]
  (let [[ilat ilon] (str/split k #",")]
    [(/ (Long/parseLong ilat) 10.0) (/ (Long/parseLong ilon) 10.0)]))

;; ---------------------------------------------------------------------------
;; Great-circle interpolation (slerp on the unit sphere)
;; ---------------------------------------------------------------------------
(defn gc-points
  "Vector of [lat lon] points sampled along the great circle from
   (lat1,lon1) to (lat2,lon2) at ~step-nm spacing (inclusive of both ends).
   Uses spherical linear interpolation so it stays on the great circle."
  [^double lat1 ^double lon1 ^double lat2 ^double lon2]
  (let [d-nm (haversine-nm lat1 lon1 lat2 lon2)]
    (if (< d-nm 1.0e-6)
      [[lat1 lon1]]
      (let [n (max 1 (long (Math/ceil (/ d-nm step-nm))))
            ph1 (deg->rad lat1) th1 (deg->rad lon1)
            ph2 (deg->rad lat2) th2 (deg->rad lon2)
            x1 (* (Math/cos ph1) (Math/cos th1))
            y1 (* (Math/cos ph1) (Math/sin th1))
            z1 (Math/sin ph1)
            x2 (* (Math/cos ph2) (Math/cos th2))
            y2 (* (Math/cos ph2) (Math/sin th2))
            z2 (Math/sin ph2)
            dot (max -1.0 (min 1.0 (+ (* x1 x2) (* y1 y2) (* z1 z2))))
            ang (Math/acos dot)
            sin-ang (Math/sin ang)]
        (loop [i 0, acc (transient [])]
          (if (> i n)
            (persistent! acc)
            (let [f (/ (double i) (double n))
                  [a b] (if (< sin-ang 1.0e-9)
                          [(- 1.0 f) f]
                          [(/ (Math/sin (* (- 1.0 f) ang)) sin-ang)
                           (/ (Math/sin (* f ang)) sin-ang)])
                  x (+ (* a x1) (* b x2))
                  y (+ (* a y1) (* b y2))
                  z (+ (* a z1) (* b z2))
                  lat (rad->deg (Math/atan2 z (Math/sqrt (+ (* x x) (* y y)))))
                  lon (rad->deg (Math/atan2 y x))]
              (recur (inc i) (conj! acc [lat lon])))))))))

;; ---------------------------------------------------------------------------
;; Snap a point to the nearest sea cell
;; ---------------------------------------------------------------------------
(defn snap-to-sea
  "If the point's own 0.1 cell is sea, return its key. Otherwise search outward
   ring by ring (up to snap-radius-cells) and return the nearest sea cell key by
   great-circle distance to the cell centre. Returns nil if none found."
  [sea-cells ^double lat ^double lon]
  (let [ilat (Math/round (* lat 10.0))
        ilon (Math/round (* lon 10.0))
        own (str ilat "," ilon)]
    (if (contains? sea-cells own)
      own
      (loop [r 1]
        (if (> r snap-radius-cells)
          nil
          (let [best (loop [di (- r), bk nil, bd Double/POSITIVE_INFINITY]
                       (if (> di r)
                         bk
                         (let [[bk bd]
                               (loop [dj (- r), bk bk, bd bd]
                                 (if (> dj r)
                                   [bk bd]
                                   ;; only the ring at Chebyshev radius r
                                   (if (and (< (Math/abs (long di)) r)
                                            (< (Math/abs (long dj)) r))
                                     (recur (inc dj) bk bd)
                                     (let [k (str (+ ilat di) "," (+ ilon dj))]
                                       (if (contains? sea-cells k)
                                         (let [clat (/ (+ ilat di) 10.0)
                                               clon (/ (+ ilon dj) 10.0)
                                               d (haversine-nm lat lon clat clon)]
                                           (if (< d bd)
                                             (recur (inc dj) k d)
                                             (recur (inc dj) bk bd)))
                                         (recur (inc dj) bk bd))))))]
                           (recur (inc di) bk bd))))]
            (if best best (recur (inc r)))))))))

;; ---------------------------------------------------------------------------
;; Route every passage: accumulate effort raster + track-point pool
;; ---------------------------------------------------------------------------
(defn route-passages
  "Returns {:effort {cell-key miles} :pool [{:lat :lon :doy :miles}]
            :routed n :skipped n :dropped n :total-points n}.
   effort = per-cell summed passage-miles; pool = one entry per retained,
   snapped track point (cell-centre coords + doy + its miles share)."
  [uneventful harbor sea-cells]
  (loop [ps uneventful
         effort (transient {})
         pool (transient [])
         routed 0
         skipped 0
         dropped 0
         total-pts 0]
    (if-let [p (first ps)]
      (let [s (get harbor (:start p))
            e (get harbor (:end p))]
        (if (or (nil? s) (nil? e))
          (recur (rest ps) effort pool routed (inc skipped) dropped total-pts)
          (let [lat1 (double (:lat s)) lon1 (double (:lon s))
                lat2 (double (:lat e)) lon2 (double (:lon e))
                d-gc (haversine-nm lat1 lon1 lat2 lon2)
                len (:length-nm p)
                l (if (and (number? len) (> (double len) 0.0)
                           (< (double len) max-plausible-nm))
                    (double len)
                    d-gc)
                pts (gc-points lat1 lon1 lat2 lon2)
                ;; snap each point; keep snapped keys (nil dropped)
                snapped (loop [pp pts, acc (transient []), drop-n 0]
                          (if-let [[plat plon] (first pp)]
                            (let [k (snap-to-sea sea-cells plat plon)]
                              (if k
                                (recur (rest pp) (conj! acc k) drop-n)
                                (recur (rest pp) acc (inc drop-n))))
                            [(persistent! acc) drop-n]))
                kept (first snapped)
                drop-n (second snapped)
                nk (count kept)]
            (if (zero? nk)
              (recur (rest ps) effort pool routed (inc skipped)
                     (+ dropped drop-n) total-pts)
              (let [share (/ l (double nk))
                    doy (:doy p)
                    [effort pool]
                    (loop [ks kept, ef effort, pl pool]
                      (if-let [k (first ks)]
                        (let [[clat clon] (cell-centre k)]
                          (recur (rest ks)
                                 (assoc! ef k (+ (get ef k 0.0) share))
                                 (conj! pl {:lat clat :lon clon
                                            :doy doy :miles share})))
                        [ef pl]))]
                (recur (rest ps) effort pool (inc routed) skipped
                       (+ dropped drop-n) (+ total-pts nk)))))))
      {:effort (persistent! effort)
       :pool (persistent! pool)
       :routed routed
       :skipped skipped
       :dropped dropped
       :total-points total-pts})))

;; ---------------------------------------------------------------------------
;; Effort-weighted background sampling (deterministic, java.util.Random)
;; ---------------------------------------------------------------------------
(defn sample-background
  "Sample n points from pool with probability proportional to :miles, using a
   fixed-seed java.util.Random. Sampling with replacement. Returns vector of
   {:lat :lon :doy}. Uses a cumulative-weight array + binary search."
  [pool n seed]
  (let [v (vec pool)
        m (count v)
        cum (double-array m)
        total (loop [i 0, acc 0.0]
                (if (>= i m)
                  acc
                  (let [acc' (+ acc (double (:miles (nth v i))))]
                    (aset cum i acc')
                    (recur (inc i) acc'))))
        rng (java.util.Random. seed)]
    (loop [i 0, acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (let [target (* (.nextDouble rng) total)
              ;; binary search for first cum >= target
              idx (loop [lo 0, hi (dec m)]
                    (if (>= lo hi)
                      lo
                      (let [mid (quot (+ lo hi) 2)]
                        (if (< (aget cum mid) target)
                          (recur (inc mid) hi)
                          (recur lo mid)))))
              p (nth v idx)]
          (recur (inc i) (conj! acc {:lat (:lat p) :lon (:lon p)
                                     :doy (:doy p)})))))))

;; ---------------------------------------------------------------------------
;; Output formatting
;; ---------------------------------------------------------------------------
(defn round1 ^double [^double x] (/ (Math/round (* x 10.0)) 10.0))

(defn doy->month
  "Month 1..12 from day-of-year (approximate, non-leap boundaries)."
  [doy]
  (let [cum [31 59 90 120 151 181 212 243 273 304 334 365]]
    (loop [m 0]
      (if (or (>= m 11) (<= doy (nth cum m)))
        (inc m)
        (recur (inc m))))))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------
(defn -main []
  (let [t0 (System/currentTimeMillis)
        dataset (read-string (slurp dataset-path))
        harbor (read-string (slurp harbor-path))
        uneventful (:uneventful dataset)
        geo (json/parse-string (slurp geo-grid-path))
        sea-cells (set (keys (get geo "cells")))
        _ (binding [*out* *err*]
            (println "uneventful passages:" (count uneventful)
                     " harbors:" (count harbor)
                     " sea cells:" (count sea-cells)))
        {:keys [effort pool routed skipped dropped total-points]}
        (route-passages uneventful harbor sea-cells)
        total-effort (reduce + 0.0 (vals effort))
        top10 (->> effort
                   (sort-by val >)
                   (take 10)
                   (mapv (fn [[k v]]
                           (let [[clat clon] (cell-centre k)]
                             {:cell k :lat clat :lon clon
                              :miles (round1 v)}))))
        bg (sample-background pool n-background rng-seed)
        month-hist (->> bg
                        (keep :doy)
                        (map doy->month)
                        frequencies
                        (into (sorted-map)))
        nil-doy (count (filter (comp nil? :doy) bg))]
    ;; ---- write effort_grid.json ----
    (let [cells (persistent!
                  (reduce-kv (fn [m k v] (assoc! m k (round1 v)))
                             (transient {}) effort))
          out {:bounds {:lat_min lat-min :lat_max lat-max
                        :lon_min lon-min :lon_max lon-max}
               :resolution res
               :key "round(lat*10),round(lon*10)"
               :cells cells}]
      (io/make-parents effort-out)
      (spit effort-out (json/generate-string out)))
    ;; ---- write background_sample.edn (deterministic, stable ordering) ----
    (spit bg-out (with-out-str (prn (vec bg))))
    ;; ---- summary to stderr ----
    (binding [*out* *err*]
      (println "passages routed:" routed
               " skipped (harbor missing/empty track):" skipped)
      (println "track points retained:" total-points
               " dropped (no nearby sea):" dropped)
      (println "total effort (passage-miles):" (round1 total-effort))
      (println "effort cells (>0):" (count effort))
      (println "top-10 highest-effort cells:")
      (doseq [t top10]
        (println (format "  %-10s lat %.2f lon %.2f  miles %.1f"
                         (:cell t) (:lat t) (:lon t) (:miles t))))
      (println "background sample:" (count bg) "points,  nil doy:" nil-doy)
      (println "background doy histogram by month:")
      (doseq [[m c] month-hist]
        (println (format "  month %2d : %4d" m c)))
      (println "elapsed-ms:" (- (System/currentTimeMillis) t0))
      (println "wrote" effort-out)
      (println "wrote" bg-out))))

(-main)
