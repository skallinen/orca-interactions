#!/usr/bin/env bb
;; Route planner: build a compact set of LAND polygons clipped to the orca-zone
;; bbox, for clipping the risk heatmap to the coastline in the browser.
;;
;; Input : tmp_geo/ne_10m_land.geojson (Natural Earth 1:10m land polygons;
;;         download once with, e.g.
;;         curl -o tmp_geo/ne_10m_land.geojson \
;;           https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_land.geojson)
;; Output: route-planner/coastline.json
;;           {"bbox":[lat_min lon_min lat_max lon_max],
;;            "polygons":[ [[lat lon] [lat lon] …]  … ]}   ; outer land rings only
;;
;; The polygons are Sutherland-Hodgman-clipped to a slightly padded bbox so each
;; is just the local coastal land (Iberia, NW Africa, France, islands), small
;; enough to project per map tile. Holes (inland lakes) are dropped: there is no
;; sea heatmap inside them, so erasing them as land is harmless.

(require '[cheshire.core :as json]
         '[clojure.java.io :as io])

(def land-geojson "tmp_geo/ne_10m_land.geojson")
(def out-path "route-planner/coastline.json")

;; Padded bbox (the grid bbox is 25–50 N, 20 W–5 E); pad 1° so coastlines that
;; run just outside the grid still clip cleanly.
(def lon-min -21.0)
(def lon-max 6.0)
(def lat-min 24.0)
(def lat-max 51.0)

;; ── Sutherland-Hodgman polygon clip against the bbox rectangle ────────────────
;; Points are [lon lat]. `inside?` and `intersect` are per clip edge.

(defn- clip-edge
  "Clip ring (vector of [lon lat]) against one half-plane, given inside? and a
   line intersector for that edge."
  [ring inside? intersect]
  (if (empty? ring)
    []
    (loop [pts ring
           prev (peek (vec ring))
           out []]
      (if (empty? pts)
        out
        (let [cur (first pts)
              ci (inside? cur)
              pi (inside? prev)
              out (cond
                    (and ci pi)       (conj out cur)
                    (and ci (not pi)) (conj out (intersect prev cur) cur)
                    (and (not ci) pi) (conj out (intersect prev cur))
                    :else             out)]
          (recur (rest pts) cur out))))))

(defn- lerp [a b t] (+ a (* (- b a) t)))

(defn- isect-x
  "Intersection of segment p->q with the vertical line x=xc."
  [xc [px py] [qx qy]]
  (let [t (/ (- xc px) (- qx px))]
    [xc (lerp py qy t)]))

(defn- isect-y
  "Intersection of segment p->q with the horizontal line y=yc."
  [yc [px py] [qx qy]]
  (let [t (/ (- yc py) (- qy py))]
    [(lerp px qx t) yc]))

(defn- clip-ring
  "Clip a ring to the bbox rectangle via four half-plane clips."
  [ring]
  (-> ring
      (clip-edge (fn [[x _]] (>= x lon-min)) (partial isect-x lon-min))
      (clip-edge (fn [[x _]] (<= x lon-max)) (partial isect-x lon-max))
      (clip-edge (fn [[_ y]] (>= y lat-min)) (partial isect-y lat-min))
      (clip-edge (fn [[_ y]] (<= y lat-max)) (partial isect-y lat-max))))

(defn- ring-bbox-overlaps?
  "Cheap reject: does the ring's bbox overlap the clip bbox at all?"
  [ring]
  (let [xs (map first ring) ys (map second ring)]
    (and (>= (apply max xs) lon-min) (<= (apply min xs) lon-max)
         (>= (apply max ys) lat-min) (<= (apply min ys) lat-max))))

(defn- outer-rings
  "Outer ring(s) of a GeoJSON geometry (Polygon or MultiPolygon); ring index 0."
  [geom]
  (case (get geom "type")
    "Polygon"      [(first (get geom "coordinates"))]
    "MultiPolygon" (map first (get geom "coordinates"))
    []))

(defn- round4 [x] (/ (Math/round (* x 10000.0)) 10000.0))

(defn -main []
  (let [fc      (json/parse-string (slurp land-geojson))
        rings   (mapcat (fn [f] (outer-rings (get f "geometry")))
                        (get fc "features"))
        clipped (->> rings
                     (filter ring-bbox-overlaps?)
                     (map clip-ring)
                     (filter #(>= (count %) 3))
                     ;; store as [lat lon] (Leaflet order), rounded
                     (mapv (fn [ring]
                             (mapv (fn [[lon lat]] [(round4 lat) (round4 lon)])
                                   ring))))
        pts     (reduce + (map count clipped))]
    (with-open [w (io/writer out-path)]
      (json/generate-stream
       {"bbox" [lat-min lon-min lat-max lon-max] "polygons" clipped}
       w))
    (binding [*out* *err*]
      (println "coastline: kept" (count clipped) "land rings,"
               pts "vertices ->" out-path))))

(-main)
