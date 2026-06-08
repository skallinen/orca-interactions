#!/usr/bin/env bb
;; Phase 2 route-planner: build a static raster of CONTINUOUS seabed-depth and
;; distance-to-coast ordinal bins per 0.1 deg sea cell, emitted as JSON.
;;
;; Each stored sea cell now carries:
;;   - "m": CONTINUOUS depth in metres (+down), bilinearly sampled from the
;;          high-res ETOPO_2022_v1_15s bathymetry. The runtime depth covariate
;;          (b_d1*z + b_d2*z2, z = standardize(log10(max(m,1)))) reads this.
;;   - "c": distance-to-coast ordinal (0:<=2nm 1:<=5nm 2:<=10nm 3:>10nm).
;; The old "d" (4-bin depth ordinal) has been DROPPED: continuous "m" supersedes
;; it, and the depth-ord beta was already excluded from the attr multiplier as a
;; location proxy (see POSTERIOR_SCHEMA.md). The land/sea mask still uses the
;; coarse `tmp_geo/bathy.csv` average (depth>0) so the set of stored cells is
;; unchanged; only the per-cell depth VALUE comes from the finer ETOPO grid.
;;
;; Inputs (plain text / JSON only):
;;   - tmp_geo/bathy.csv     coarse ETOPO altitude CSV (land/sea mask only)
;;   - tmp_sim/bathy.json    high-res ETOPO_2022_v1_15s depth grid (continuous m).
;;       Built by route-planner/tmp_sim/depth_probe.clj from five 5-deg ERDDAP
;;       griddap bands (stride 12, ~0.05deg), concatenated over 25-50N, 20W-5E:
;;         https://coastwatch.pfeg.noaa.gov/erddap/griddap/ETOPO_2022_v1_15s.csv?z[(25.0):12:(30.0)][(-20.0):12:(5.0)]
;;         https://coastwatch.pfeg.noaa.gov/erddap/griddap/ETOPO_2022_v1_15s.csv?z[(30.0):12:(35.0)][(-20.0):12:(5.0)]
;;         https://coastwatch.pfeg.noaa.gov/erddap/griddap/ETOPO_2022_v1_15s.csv?z[(35.0):12:(40.0)][(-20.0):12:(5.0)]
;;         https://coastwatch.pfeg.noaa.gov/erddap/griddap/ETOPO_2022_v1_15s.csv?z[(40.0):12:(45.0)][(-20.0):12:(5.0)]
;;         https://coastwatch.pfeg.noaa.gov/erddap/griddap/ETOPO_2022_v1_15s.csv?z[(45.0):12:(50.0)][(-20.0):12:(5.0)]
;;       (z = altitude +up; depth = -z.)
;;   - tmp_geo/ne_50m_coastline.geojson   Natural Earth coastline (distance-to-coast)
;; Output:
;;   - route-planner/geo_grid.json

(ns gen-geo-grid
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------
(def lat-min 25.0)
(def lat-max 50.0)
(def lon-min -20.0)
(def lon-max 5.0)
(def res 0.1)

(def repo "/Users/samikallinen/common/projects/orca-interactions")
(def bathy-csv (str repo "/tmp_geo/bathy.csv"))
(def hires-bathy-json (str repo "/route-planner/tmp_sim/bathy.json"))
(def coast-geojson (str repo "/tmp_geo/ne_50m_coastline.geojson"))
(def out-path (str repo "/route-planner/geo_grid.json"))

;; ---------------------------------------------------------------------------
;; High-res ETOPO depth grid (bilinear sampling of CONTINUOUS depth, +down m)
;; ---------------------------------------------------------------------------
(defn load-hires-bathy
  "Load tmp_sim/bathy.json {:lats :lons :nlat :nlon :depth_row_major} for
   bilinear sampling of continuous depth (m, +down; negative over land)."
  []
  (let [b (json/parse-string (slurp hires-bathy-json))]
    {:lats (double-array (get b "lats"))
     :lons (double-array (get b "lons"))
     :nlat (long (get b "nlat"))
     :nlon (long (get b "nlon"))
     :z (double-array (get b "depth_row_major"))}))

(defn bsearch-cell ^long [^doubles axis ^long n ^double v]
  (let [n1 (dec n)]
    (cond
      (<= v (aget axis 0)) 0
      (>= v (aget axis n1)) (dec n1)
      :else
      (loop [lo 0 hi n1]
        (if (<= (- hi lo) 1)
          lo
          (let [mid (quot (+ lo hi) 2)]
            (if (<= (aget axis mid) v) (recur mid hi) (recur lo mid))))))))

(defn sample-depth-m
  "Bilinear continuous depth (m, +down) at (lat,lon) from the hi-res grid."
  ^double [{:keys [^doubles lats ^doubles lons ^long nlat ^long nlon ^doubles z]}
           ^double lat ^double lon]
  (let [i (bsearch-cell lats nlat lat)
        j (bsearch-cell lons nlon lon)
        la0 (aget lats i) la1 (aget lats (inc i))
        lo0 (aget lons j) lo1 (aget lons (inc j))
        ty (if (== la1 la0) 0.0 (/ (- lat la0) (- la1 la0)))
        tx (if (== lo1 lo0) 0.0 (/ (- lon lo0) (- lo1 lo0)))
        idx (fn ^double [^long ii ^long jj] (aget z (+ (* ii nlon) jj)))
        z00 (idx i j) z01 (idx i (inc j))
        z10 (idx (inc i) j) z11 (idx (inc i) (inc j))]
    (+ (* (- 1.0 ty) (+ (* (- 1.0 tx) z00) (* tx z01)))
       (* ty (+ (* (- 1.0 tx) z10) (* tx z11))))))

;; ---------------------------------------------------------------------------
;; Binning
;; ---------------------------------------------------------------------------
(defn distance-ord
  "distance-ord from distance in nautical miles.
   0-2 ->0, 2-5 ->1, 5-10 ->2, >10 ->3."
  [nm]
  (cond
    (<= nm 2.0)  0
    (<= nm 5.0)  1
    (<= nm 10.0) 2
    :else        3))

(defn ikey
  "Integer-tenths cell key: round(lat*10),round(lon*10)."
  [lat lon]
  (str (Math/round (* lat 10.0)) "," (Math/round (* lon 10.0))))

;; ---------------------------------------------------------------------------
;; Haversine distance (nm)
;; ---------------------------------------------------------------------------
(def earth-r-km 6371.0088)
(def km-per-nm 1.852)
(def deg->km 111.195)               ;; km per degree of latitude (R * pi/180)

(defn deg->rad [d] (* d (/ Math/PI 180.0)))

(defn haversine-nm
  [lat1 lon1 lat2 lon2]
  (let [p1 (deg->rad lat1)
        p2 (deg->rad lat2)
        dp (deg->rad (- lat2 lat1))
        dl (deg->rad (- lon2 lon1))
        a  (+ (* (Math/sin (/ dp 2)) (Math/sin (/ dp 2)))
              (* (Math/cos p1) (Math/cos p2)
                 (Math/sin (/ dl 2)) (Math/sin (/ dl 2))))
        c  (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (/ (* earth-r-km c) km-per-nm)))

;; ---------------------------------------------------------------------------
;; Load bathymetry -> map of int-key -> [sum count] then averaged depth
;; ---------------------------------------------------------------------------
(defn load-bathy
  "Returns map int-key -> mean altitude (m, +up) over points in that 0.1 cell."
  []
  (with-open [r (io/reader bathy-csv)]
    (let [lines (line-seq r)
          ;; drop the 2 header rows (column names + units)
          data  (drop 2 lines)]
      (loop [ls data
             acc (transient {})]
        (if-let [ln (first ls)]
          (let [[la lo al] (str/split ln #",")]
            (if (and al (seq al))
              (let [lat (Double/parseDouble la)
                    lon (Double/parseDouble lo)
                    alt (Double/parseDouble al)
                    k   (ikey lat lon)
                    [s n] (get acc k [0.0 0])]
                (recur (rest ls) (assoc! acc k [(+ s alt) (inc n)])))
              (recur (rest ls) acc)))
          (persistent! acc))))))

;; ---------------------------------------------------------------------------
;; Load coastline vertices within padded bbox
;; ---------------------------------------------------------------------------
(def pad 2.0)

(defn in-padded? [lon lat]
  (and (>= lon (- lon-min pad)) (<= lon (+ lon-max pad))
       (>= lat (- lat-min pad)) (<= lat (+ lat-max pad))))

(defn collect-coords
  "Flatten LineString/MultiLineString coords into [lon lat] pairs, filtered."
  [geom]
  (let [t (get geom "type")
        c (get geom "coordinates")]
    (cond
      (= t "LineString")
      (filterv (fn [[lon lat]] (in-padded? lon lat)) c)

      (= t "MultiLineString")
      (into [] (comp cat (filter (fn [[lon lat]] (in-padded? lon lat)))) c)

      :else [])))

(defn load-coast-vertices []
  (let [gj (json/parse-string (slurp coast-geojson))
        feats (get gj "features")]
    (into []
          (mapcat (fn [f] (collect-coords (get f "geometry"))))
          feats)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------
;; Equirectangular distance in nm between two nearby points (accurate for the
;; sub-degree separations we care about; only used to pick a 4-way bin).
(defn equirect-nm [lat1 lon1 lat2 lon2 coslat]
  (let [dy (* (- lat2 lat1) deg->km)
        dx (* (- lon2 lon1) deg->km coslat)
        km (Math/sqrt (+ (* dy dy) (* dx dx)))]
    (/ km km-per-nm)))

;; Spatial bucket key for a coast vertex at given index resolution.
;; Buckets are 0.25 deg wide (~15 nm), so a 3x3 block spans ~0.75 deg which
;; comfortably covers the >10 nm (~0.17 deg lat) saturation boundary; cells with
;; no coast vertex anywhere in that block get distance_ord = 3 immediately.
(def bucket-deg 0.25)
(defn bidx [v] (long (Math/floor (/ v bucket-deg))))
(defn bkey
  "Single long key from bucket lat/lon indices (offset to keep both non-negative)."
  ^long [^long bi ^long bj]
  (+ (* (+ bi 100000) 1000000) (+ bj 100000)))

(defn build-coast-grid
  "Spatial hash: bucket key -> array of [lat lon] coast vertices.
   Stores plain Java arrays for fast iteration in the hot loop."
  [coast]
  (let [m (persistent!
           (reduce (fn [m [lon lat]]
                     (let [k (bkey (bidx lat) (bidx lon))]
                       (assoc! m k (conj (get m k []) (double-array [lat lon])))))
                   (transient {})
                   coast))]
    (persistent!
     (reduce-kv (fn [mm k vs] (assoc! mm k (object-array vs)))
                (transient {}) m))))

(defn nearest-coast-nm
  "Minimum nm from (lat,lon) to any coast vertex in the 3x3 bucket block.
   Returns +Inf when no vertex is nearby (=> saturates to distance_ord 3)."
  ^double [coast-grid ^double lat ^double lon ^double coslat]
  (let [bi (bidx lat)
        bj (bidx lon)]
    (loop [di -1, best Double/POSITIVE_INFINITY]
      (if (> di 1)
        best
        (recur (inc di)
               (loop [dj -1, best best]
                 (if (> dj 1)
                   best
                   (let [vs (get coast-grid (bkey (+ bi di) (+ bj dj)))]
                     (recur (inc dj)
                            (if (nil? vs)
                              best
                              (let [n (alength ^objects vs)]
                                (loop [i 0, best best]
                                  (if (>= i n)
                                    best
                                    (let [^doubles p (aget ^objects vs i)
                                          d (equirect-nm lat lon (aget p 0) (aget p 1) coslat)]
                                      (recur (inc i) (if (< d best) d best))))))))))))))))

;; ---------------------------------------------------------------------------
;; Cell raster build.
;;
;; NOTE: this hot loop is deliberately a dedicated top-level fn rather than a
;; deeply-nested loop inside -main. Babashka's interpreter (SCI) gets
;; pathologically slow when a multi-level loop/recur with several bound locals
;; runs inside one large enclosing closure (the old -main stalled for many
;; minutes on the very first latitude row). Hoisting it to its own fn keeps the
;; same logic running in well under a second.
(defn build-cells
  "Walk every 0.1 deg cell, returning {:cells m :stored n :land n :nobathy n}.
   Land/sea is masked by the coarse `bathy` average (depth>0); each stored sea
   cell carries {:m continuous-depth-m :c distance-ord}, with `m` bilinearly
   sampled from the hi-res ETOPO grid. Land/no-bathy cells are omitted."
  [bathy hires coast-grid]
  (let [ilat-min (Math/round (* lat-min 10.0))
        ilat-max (Math/round (* lat-max 10.0))
        ilon-min (Math/round (* lon-min 10.0))
        ilon-max (Math/round (* lon-max 10.0))]
    (loop [ilat ilat-min, cells (transient {}), stored 0, land 0, nobathy 0]
      (if (> ilat ilat-max)
        {:cells (persistent! cells) :stored stored :land land :nobathy nobathy}
        (let [lat (/ ilat 10.0)
              coslat (Math/cos (deg->rad lat))
              [cells stored land nobathy]
              (loop [ilon ilon-min, cells cells, stored stored, land land, nobathy nobathy]
                (if (> ilon ilon-max)
                  [cells stored land nobathy]
                  (let [lon (/ ilon 10.0)
                        k   (str ilat "," ilon)
                        bv  (get bathy k)]
                    (if (nil? bv)
                      (recur (inc ilon) cells stored land (inc nobathy))
                      (let [[s n] bv
                            depth (- (/ s n))]   ;; positive = below sea level
                        (if (<= depth 0.0)
                          (recur (inc ilon) cells stored (inc land) nobathy)
                          (let [best (nearest-coast-nm coast-grid lat lon coslat)
                                m (Math/round (sample-depth-m hires lat lon))]
                            (recur (inc ilon)
                                   (assoc! cells k {:m m
                                                    :c (distance-ord best)})
                                   (inc stored) land nobathy))))))))]
          (recur (inc ilat) cells stored land nobathy))))))

(defn -main []
  (println "Loading bathymetry from" bathy-csv)
  (let [t0 (System/currentTimeMillis)
        bathy (load-bathy)
        _ (println "  bathy cells:" (count bathy))
        hires (load-hires-bathy)
        _ (println "  hi-res ETOPO grid:" (:nlat hires) "x" (:nlon hires))
        coast (load-coast-vertices)
        _ (println "Loaded coastline vertices (padded bbox):" (count coast))
        coast-grid (build-coast-grid coast)
        _ (println "  coast buckets:" (count coast-grid))
        {:keys [cells stored land nobathy]} (build-cells bathy hires coast-grid)]
    (println "  stored sea cells:" stored " land dropped:" land
             " no-bathy:" nobathy
             " elapsed-ms:" (- (System/currentTimeMillis) t0))
    (let [result {:bounds {:lat_min lat-min :lat_max lat-max
                           :lon_min lon-min :lon_max lon-max}
                  :resolution res
                  :key "round(lat*10),round(lon*10)"
                  :cells cells}]
      (io/make-parents out-path)
      (spit out-path (json/generate-string result))
      (println "Wrote" out-path))))

(-main)
