(ns orca.planner.app
  "Reagent shell for the standalone orca route planner.

   Phase 4 scope: load the three data files (posterior draws, incident reports,
   the sea-cell geo grid), populate the app-state atoms, mount a minimal Reagent
   root into the sidebar, and report Ready.

   Phase 5 scope: a Leaflet map (CartoDB Dark Matter) with three toggleable
   layers — a historical-incident heatmap (default on), a live model-risk
   heatmap derived from the posterior mean draw (default on), and incident
   points (default off). The live-risk layer precomputes the per-cell static
   logit part S once and rebuilds only the cheap dynamic scalar D on refresh.

   Scittle loads this by direct <script src> after planner_core.cljs, so the
   core namespace is already available."
  (:require
   [orca.planner.core :as core]
   [reagent.core :as r]
   [reagent.dom :as rdom]))

;; ── App state ────────────────────────────────────────────────────────────────

(defonce posterior-data (r/atom nil))
(defonce incidents (r/atom []))
(defonce geo-grid (r/atom nil))
(defonce coastline? (r/atom false))

(defonce boat-params
  (r/atom {:antifoul "Black"
           :hull "White/light"
           :rudder "Spade"
           :sailing "Motoring"
           :autopilot 0
           :speed 2
           :length 1}))

(defonce passage-params
  (r/atom {:wind 1
           :sea 0
           :daylight "Average"
           :doy 232
           :base-rate 0.025
           :ref-nm 100
           :seg-step-nm 25}))

(defonce routes (r/atom [{:points [] :result nil}]))
(defonce active-route (r/atom 0))
(defonce drawing? (r/atom false))

;; ── Map / layer state (Phase 5) ──────────────────────────────────────────────

;; Plain atoms: these hold mutable Leaflet objects, not reactive UI state.
(defonce map-ref (atom nil))
(defonce map-ready? (atom false))

;; Layer object handles, kept so toggles can add/remove them from the map.
(defonce incident-heat-layer (atom nil))
(defonce risk-heat-layer (atom nil))
(defonce incident-points-layer (atom nil))

;; Precomputed per-cell statics {:lat :lon :S}; S never changes on param edits.
(defonce static-cells (atom []))

;; Current per-cell intensities, recomputed (cheap) on every re-tint and read by
;; the GridLayer's createTile painter. A flat JS array parallel to static-cells.
(defonce cell-intensities (atom #js []))

;; ── Route-drawing state (Phase 6) ────────────────────────────────────────────

;; Leaflet object handles for the active route's waypoint markers and the
;; coloured sub-segment polylines, kept so a rebuild can clear them.
(defonce waypoint-markers (atom []))
(defonce segment-lines (atom []))

;; Per-control debounce timers + window for the Phase-7 slider debounce, also
;; reused by the I2.2 waypoint-drag handler. Declared here (ahead of their main
;; users in Phase 7) so the marker drag handler can share them.
(defonce ^:private debounce-timers (atom {}))

(def ^:private debounce-ms 80)

;; Reactive results so the sidebar re-renders on every route change.
(defonce segment-summaries (r/atom []))     ;; per sub-segment {:median :lo89 :hi89 …}
(defonce route-summary (r/atom nil))        ;; whole-route {:median :lo89 :hi89}

;; Per-route whole-route summaries for the comparison panel, keyed by route
;; index → {:median :lo89 :hi89} (or nil for a route with <2 waypoints).
(defonce route-comparison (r/atom []))

;; Reactive layer-visibility flags driving the sidebar checkboxes.
(defonce layer-visible
  (r/atom {:incident-heat true
           :risk-heat true
           :incident-points false}))

;; Reactive opacity (0..1) of the live-risk GridLayer, driven by its slider.
(defonce risk-opacity (r/atom 1.0))

;; ── DOM status helpers ───────────────────────────────────────────────────────

(defn set-status! [text]
  (when-let [el (js/document.getElementById "status")]
    (set! (.-textContent el) text)))

(defn reveal-loaded! [text]
  (when-let [el (js/document.getElementById "status-loaded")]
    (set! (.-textContent el) text)
    (set! (.. el -style -display) "block")))

;; ── Data parsing ─────────────────────────────────────────────────────────────

(defn- date->doy
  "Day-of-year (1-366) from a leading \"YYYY-MM-DD…\" date string, or nil."
  [s]
  (when (and (string? s) (>= (count s) 10))
    (let [y  (js/parseInt (subs s 0 4) 10)
          mo (js/parseInt (subs s 5 7) 10)
          d  (js/parseInt (subs s 8 10) 10)]
      (when-not (or (js/isNaN y) (js/isNaN mo) (js/isNaN d))
        (inc (js/Math.floor (/ (- (js/Date.UTC y (dec mo) d) (js/Date.UTC y 0 1))
                               86400000)))))))

(defn parse-incidents
  "Pull [[lat lon doy] …] out of reports.incident.<id>.{lat,long,time}; doy is
   the day-of-year of the interaction (used to window the layers by month)."
  [report-json]
  (let [incident (get-in report-json ["reports" "incident"])]
    (->> (vals incident)
         (keep (fn [r]
                 (let [lat (get r "lat")
                       lon (get r "long")
                       doy (date->doy (get r "time"))]
                   (when (and (number? lat) (number? lon))
                     [lat lon doy]))))
         vec)))

;; ── Data load ────────────────────────────────────────────────────────────────

;; init-map! is defined below (it needs the layer constructors) but is called
;; from the load-data! .then; forward-declare so both linter and Scittle agree.
(declare init-map!)
(declare add-waypoint! delete-waypoint! refresh-route! install-test-hooks!
         select-route! refresh-comparison!)

(defn fetch-json
  "Fetch `url` and resolve its parsed JS object (no clj conversion here)."
  [url]
  (-> (js/fetch url)
      (.then (fn [resp] (.json resp)))))

(defn load-data!
  "Fetch all three data files; populate the atoms and report Ready only after
   every fetch has resolved. Errors are logged to the console (Scittle prints
   there, not to the page)."
  []
  (set-status! "Loading data…")
  (-> (js/Promise.all
        #js [(fetch-json "posterior_planner.json")
             (fetch-json "../orca_reportlist.json")
             (fetch-json "geo_grid.json")])
      (.then
        (fn [results]
          (let [posterior (js->clj (aget results 0) :keywordize-keys true)
                reports   (js->clj (aget results 1) :keywordize-keys false)
                ;; geo-grid cells must keep STRING keys ("361,-59") for lookup,
                ;; so do NOT keywordize this one.
                grid      (js->clj (aget results 2) :keywordize-keys false)]
            (reset! posterior-data posterior)
            (reset! incidents (parse-incidents reports))
            (reset! geo-grid grid)
            (set-status! "Ready")
            (reveal-loaded! "Ready")
            (js/console.log
              (str "orca planner: loaded "
                   (:n_draws posterior) " draws, "
                   (count @incidents) " incidents, "
                   (count (get grid "cells")) " sea cells"))
            (init-map!))))
      (.catch
        (fn [e]
          (js/console.error (str "orca planner: data load failed: " e))
          (set-status! (str "Load failed: " e))))))

;; ── Leaflet map + layers (Phase 5) ───────────────────────────────────────────

(def ^:private incident-gradient
  "Warm yellow→orange→red ramp for the historical-incident heatmap."
  #js {"0.4" "#ffd166" "0.7" "#ff9f1c" "1.0" "#e63946"})

;; Render lattice stride (over the 0.1° grid) and coastal-land fill radius.
(def ^:private render-stride 2)
(def ^:private fill-radius 3)

(defn- nearest-sea-cell
  "The {d,c} of the nearest sea cell to integer-tenths (li,oi) within fill-radius
   rings, or nil. Used to fill coastal-land lattice points (so the field covers
   the coast); deeper inland finds nothing and is left unpainted."
  [cells li oi]
  (loop [r 1]
    (when (<= r fill-radius)
      (or (some (fn [[di dj]]
                  (when (= r (max (js/Math.abs di) (js/Math.abs dj)))
                    (get cells (str (+ li di) "," (+ oi dj)))))
                (for [di (range (- r) (inc r))
                      dj (range (- r) (inc r))]
                  [di dj]))
          (recur (inc r))))))

(defn- build-static-cells!
  "Render the field on a `render-stride`×0.1° lattice over the grid bbox, with
   coastal-land fill, and precompute the per-cell location+season static part once.
   Each lattice point uses its own sea cell's depth/distance, or — for coastal land
   within fill-radius of the sea — the nearest sea cell's depth/distance, so the
   field tiles continuously up to the coast. Each entry's :S is the
   {:rr :static-mult :scale} map from core/heatmap-static (depends on lat/lon, the
   cell depth/distance, the current doy + base-rate + ref-nm). Rebuild when
   doy/base-rate/ref-nm change. Stores [{:lat :lon :S} …]."
  [cfg grid]
  (let [mean-sd   (core/mean-spatial-draw cfg)
        mean-attr (core/mean-attr-draw cfg)
        pass      @passage-params
        doy       (:doy pass)
        base-rate (:base-rate pass)
        ref-nm    (:ref-nm pass)
        cells     (get grid "cells")
        bounds    (get grid "bounds")
        li-min    (js/Math.round (* (get bounds "lat_min") 10.0))
        li-max    (js/Math.round (* (get bounds "lat_max") 10.0))
        oi-min    (js/Math.round (* (get bounds "lon_min") 10.0))
        oi-max    (js/Math.round (* (get bounds "lon_max") 10.0))
        out       (transient [])]
    (doseq [li (range li-min (inc li-max) render-stride)
            oi (range oi-min (inc oi-max) render-stride)]
      (when-let [v (or (get cells (str li "," oi))
                       (nearest-sea-cell cells li oi))]
        (let [lat (/ li 10.0)
              lon (/ oi 10.0)
              s   (core/heatmap-static cfg mean-sd mean-attr lat lon doy
                                       (get v "d") (get v "c") base-rate ref-nm)]
          (conj! out {:lat lat :lon lon :S s}))))
    (reset! static-cells (persistent! out))
    (count @static-cells)))

;; ── Live-risk field renderer: a custom Canvas L.GridLayer (I2.3) ──────────────
;;
;; The risk field is a continuous scalar sampled on the ~0.3° sub-sampled lattice.
;; Drawing it with Leaflet.heat (a point-density renderer) produced moiré stripes,
;; an all-green wash (per-frame max-normalization) and disappearance at low zoom.
;; Instead we paint it as a real raster: an L.GridLayer whose createTile returns a
;; <canvas> and fills each cell as a rectangle coloured through a FIXED green→
;; yellow→red ramp, so a hotspot is always red and quiet water always green at any
;; zoom, with no stripes.

;; The field is rendered on a `render-stride`×0.1° lattice with coastal-land fill,
;; so paint a cell-deg square centred on each cell so the field tiles seamlessly
;; right up to (and slightly over) the coastline.
(def ^:private cell-deg (* render-stride 0.1))
(def ^:private cell-half-deg (/ cell-deg 2.0))

;; FIXED display domain for the colour ramp (intensity = sigmoid(D+S)·daylight).
;; Quiet offshore water sits ≈0.02–0.05 and hotspots reach ≈0.15+, so we clamp the
;; intensity to [risk-lo, risk-hi] and map it across the full green→yellow→red ramp.
;; This is a fixed domain (NOT per-frame normalized) so the field reads as a field.
(def ^:private risk-lo 0.02)
(def ^:private risk-hi 0.16)
(def ^:private field-alpha 0.55)

(defn- lerp [a b t] (+ a (* (- b a) t)))

(defn- ramp-rgb
  "Map a normalized t∈[0,1] across green→yellow→red, returning [r g b] ints."
  [t]
  (if (< t 0.5)
    ;; green (45,198,83) → yellow (255,209,102)
    (let [u (/ t 0.5)]
      [(js/Math.round (lerp 45.0 255.0 u))
       (js/Math.round (lerp 198.0 209.0 u))
       (js/Math.round (lerp 83.0 102.0 u))])
    ;; yellow (255,209,102) → red (230,57,70)
    (let [u (/ (- t 0.5) 0.5)]
      [(js/Math.round (lerp 255.0 230.0 u))
       (js/Math.round (lerp 209.0 57.0 u))
       (js/Math.round (lerp 102.0 70.0 u))])))

(defn- intensity->rgba
  "CSS rgba() fill string for a raw intensity on the fixed [risk-lo,risk-hi] domain."
  [intensity]
  (let [t (-> (/ (- intensity risk-lo) (- risk-hi risk-lo))
              (max 0.0) (min 1.0))
        [r g b] (ramp-rgb t)]
    (str "rgba(" r "," g "," b "," field-alpha ")")))

(defn- recompute-cell-intensities!
  "Recompute the per-cell intensity array from the precomputed statics plus a
   freshly-computed dynamic vessel scalar. Cheap: the per-cell location+season
   static part is NEVER recomputed here — only the one dynamic scalar (the rest
   of attr_mult + daylight) and one combine per cell (§2.5)."
  []
  (when (and @posterior-data (seq @static-cells))
    (let [cfg       (core/derive-config @posterior-data)
          mean-attr (core/mean-attr-draw cfg)
          d         (core/dynamic-scalar cfg mean-attr @boat-params
                                         @passage-params)
          out       (js/Array. (count @static-cells))]
      (reduce (fn [i {:keys [S]}]
                (aset out i (core/heatmap-intensity d S))
                (inc i))
              0 @static-cells)
      (reset! cell-intensities out))))

(defn refresh-risk-heat!
  "Recompute D + per-cell intensities (S never recomputed) and redraw the field
   GridLayer's tiles in place. Later phases call this on param change."
  []
  (recompute-cell-intensities!)
  (when-let [layer @risk-heat-layer]
    (.redraw layer)))

(defn- paint-risk-tile!
  "Paint the sub-sampled risk cells onto one map tile's canvas. `coords` carries
   the tile's z/x/y; we ask the map to project each cell's lat/lon corners to
   layer pixels, subtract the tile's pixel origin, and fill the rectangle."
  [m canvas coords]
  ;; Leaflet's default tile size is 256 px square (we don't override it), so the
  ;; CRS pixel square covered by tile (x,y) at zoom z is [x*256,(x+1)*256] ×
  ;; [y*256,(y+1)*256].
  (let [ctx  (.getContext canvas "2d")
        z    (.-z coords)
        tw   256
        th   256
        ox   (* (.-x coords) tw)
        oy   (* (.-y coords) th)
        cells @static-cells
        cell-ints @cell-intensities]
    (set! (.-width canvas) tw)
    (set! (.-height canvas) th)
    (dotimes [i (count cells)]
      (let [c (nth cells i)
            lat (:lat c)
            lon (:lon c)
            p1 (.project m (js/L.latLng (+ lat cell-half-deg) (- lon cell-half-deg)) z)
            p2 (.project m (js/L.latLng (- lat cell-half-deg) (+ lon cell-half-deg)) z)
            x1 (- (.-x p1) ox)
            y1 (- (.-y p1) oy)
            w  (js/Math.ceil (- (.-x p2) (.-x p1)))
            h  (js/Math.ceil (- (.-y p2) (.-y p1)))]
        ;; only draw cells whose rectangle intersects this tile
        (when (and (< x1 tw) (> (+ x1 w) 0)
                   (< y1 th) (> (+ y1 h) 0))
          (set! (.-fillStyle ctx) (intensity->rgba (aget cell-ints i)))
          (.fillRect ctx x1 y1 w h))))))

(defn- make-risk-field-layer
  "A custom Canvas L.GridLayer that rasterizes the risk field per map tile."
  [m]
  ;; createTile uses the `m` captured in this closure rather than the GridLayer's
  ;; own `this._map` — Scittle's SCI build does not provide `this-as`, and the map
  ;; is the same instance the layer is added to.
  (let [klass (.extend js/L.GridLayer
                       #js {:createTile
                            (fn [coords]
                              (let [canvas (.createElement js/document "canvas")]
                                (paint-risk-tile! m canvas coords)
                                canvas))})]
    (new klass #js {:opacity @risk-opacity :pane "overlayPane"})))

;; Historical incidents are shown for the selected month +/- this many days, so
;; the empirical layer tracks the season the route is planned for (the modelled
;; risk field drifts with day-of-year; this keeps the dots in step). Wrap-around
;; at the year boundary is handled by `circular-doy-dist`.
(def ^:private incident-window-days 30)

(defn- circular-doy-dist
  "Smallest day-of-year separation between a and b on the 365-day circle."
  [a b]
  (let [d (js/Math.abs (- a b))]
    (min d (- 365 d))))

(defn- incidents-in-window
  "@incidents whose day-of-year falls within incident-window-days of the selected
   month's doy. Incidents with no parseable date (doy nil) are dropped."
  []
  (let [sel (:doy @passage-params)]
    (filterv (fn [[_ _ doy]]
               (and doy (<= (circular-doy-dist doy sel) incident-window-days)))
             @incidents)))

(defn- make-incident-heat-layer []
  (js/L.heatLayer
    (apply array (mapv (fn [[lat lon]] #js [lat lon 1.0]) (incidents-in-window)))
    #js {:radius 18 :blur 22 :max 1.0 :gradient incident-gradient}))

(defn- make-incident-points-layer []
  (let [grp (js/L.layerGroup)]
    (doseq [[lat lon] (incidents-in-window)]
      (.addTo (js/L.circleMarker
                #js [lat lon]
                #js {:radius 3 :color "#00a8cc" :weight 1
                     :fillColor "#00a8cc" :fillOpacity 0.6})
              grp))
    grp))

(defn set-layer-visible!
  "Add/remove the layer keyed by `k` from the map according to `on?`."
  [k on?]
  (let [m     @map-ref
        layer (case k
                :incident-heat @incident-heat-layer
                :risk-heat @risk-heat-layer
                :incident-points @incident-points-layer)]
    (when (and m layer)
      (if on?
        (when-not (.hasLayer m layer) (.addTo layer m))
        (when (.hasLayer m layer) (.removeLayer m layer))))))

(defn set-risk-opacity!
  "Set the live-risk GridLayer opacity (0..1), applied to the layer immediately
   and remembered so a later rebuild keeps it."
  [o]
  (reset! risk-opacity o)
  (when-let [l @risk-heat-layer]
    (.setOpacity l o)))

(defn- rebuild-incident-layers!
  "Rebuild the historical-incident heatmap + points from the current month
   window (the doy changed). Each layer is replaced in place, preserving its
   current visibility toggle."
  []
  (when-let [m @map-ref]
    (doseq [[k layer-atom maker] [[:incident-heat incident-heat-layer
                                   make-incident-heat-layer]
                                  [:incident-points incident-points-layer
                                   make-incident-points-layer]]]
      (when-let [old @layer-atom]
        (when (.hasLayer m old) (.removeLayer m old)))
      (reset! layer-atom (maker))
      (when (get @layer-visible k) (.addTo @layer-atom m)))))

(defn init-map!
  "Initialize the Leaflet map and all three layers once data is loaded. Guarded
   so it runs at most once."
  []
  (when (and (not @map-ready?)
             @posterior-data @geo-grid (js/document.getElementById "map"))
    (let [cfg (core/derive-config @posterior-data)
          m   (js/L.map "map" #js {:center #js [37.5 -7.5] :zoom 5})]
      (-> (js/L.tileLayer
            "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
            #js {:attribution
                 (str "&copy; <a href=\"https://www.openstreetmap.org/copyright\">"
                      "OpenStreetMap</a> contributors &copy; "
                      "<a href=\"https://carto.com/attributions\">CARTO</a>")
                 :subdomains "abcd" :maxZoom 19})
          (.addTo m))
      (reset! map-ref m)
      (let [n (build-static-cells! cfg @geo-grid)]
        (js/console.log (str "orca planner: precomputed " n " static cells")))
      (recompute-cell-intensities!)
      (reset! incident-heat-layer (make-incident-heat-layer))
      (reset! risk-heat-layer (make-risk-field-layer m))
      (reset! incident-points-layer (make-incident-points-layer))
      (reset! map-ready? true)
      ;; Apply default visibility (incident-heat on, risk-heat on, points off).
      (doseq [[k on?] @layer-visible]
        (set-layer-visible! k on?))
      ;; Phase 6: click the map to drop a waypoint onto the active route.
      (.on m "click"
           (fn [e]
             (let [ll (.-latlng e)]
               (add-waypoint! (.-lat ll) (.-lng ll)))))
      (install-test-hooks!))))

;; ── Route drawing + per-segment risk (Phase 6) ───────────────────────────────

(def ^:private accent-colour "#00a8cc")

;; Risk → hue colour map: green (120°) at p=0 → red (0°) at p ≥ p-max.
(def ^:private p-max 0.15)

(defn- risk-colour
  "CSS hsl string for a median risk p, green→red over [0, p-max]."
  [p]
  (let [hue (max 0.0 (* 120.0 (- 1.0 (/ p p-max))))]
    (str "hsl(" hue ",80%,50%)")))

(defn active-points
  "Waypoints [[lat lon] …] of the currently active route."
  []
  (get-in @routes [@active-route :points] []))

(defn- set-active-points! [pts]
  (swap! routes assoc-in [@active-route :points] (vec pts)))

(defn geo-lookup
  "Look up {:depth_ord :distance_ord} for (lat,lon) in the sea-cell grid.
   Falls back to the nearest neighbour within ±3 cells, else {3 3}."
  [lat lon]
  (let [cells (get @geo-grid "cells")
        li    (js/Math.round (* lat 10.0))
        oi    (js/Math.round (* lon 10.0))
        cell-at (fn [a b] (get cells (str a "," b)))]
    (if-let [c (cell-at li oi)]
      {:depth_ord (int (get c "d")) :distance_ord (int (get c "c"))}
      (loop [rad 1]
        (if (> rad 3)
          {:depth_ord 3 :distance_ord 3}
          (let [hit (first
                      (for [da (range (- rad) (inc rad))
                            db (range (- rad) (inc rad))
                            :let [c (cell-at (+ li da) (+ oi db))]
                            :when c]
                        c))]
            (if hit
              {:depth_ord (int (get hit "d")) :distance_ord (int (get hit "c"))}
              (recur (inc rad)))))))))

(defn build-segments
  "Subdivide each consecutive waypoint pair of `pts` into sub-segments of about
   `seg-step-nm`, returning a vector of {:lat :lon :depth-ord :distance-ord :nm
   :a :b} where [:a :b] are the polyline endpoints for drawing."
  [pts]
  (let [step (:seg-step-nm @passage-params)]
    (vec
      (mapcat
        (fn [[[lat1 lon1] [lat2 lon2]]]
          (let [leg-nm (/ (core/haversine-km lat1 lon1 lat2 lon2) 1.852)
                n      (max 1 (js/Math.ceil (/ leg-nm step)))
                per-nm (/ leg-nm n)]
            (for [i (range n)]
              (let [t0   (/ i n)
                    t1   (/ (inc i) n)
                    tm   (/ (+ t0 t1) 2.0)
                    alat (+ lat1 (* (- lat2 lat1) t0))
                    alon (+ lon1 (* (- lon2 lon1) t0))
                    blat (+ lat1 (* (- lat2 lat1) t1))
                    blon (+ lon1 (* (- lon2 lon1) t1))
                    mlat (+ lat1 (* (- lat2 lat1) tm))
                    mlon (+ lon1 (* (- lon2 lon1) tm))
                    {:keys [depth_ord distance_ord]} (geo-lookup mlat mlon)]
                {:lat mlat :lon mlon
                 :depth-ord depth_ord :distance-ord distance_ord
                 :nm per-nm
                 :a [alat alon] :b [blat blon]}))))
        (partition 2 1 pts)))))

(defn- clear-route-graphics!
  "Remove the active route's waypoint markers and segment polylines."
  []
  (when-let [m @map-ref]
    (doseq [mk @waypoint-markers] (.removeLayer m mk))
    (doseq [ln @segment-lines] (.removeLayer m ln)))
  (reset! waypoint-markers [])
  (reset! segment-lines []))

;; Waypoint markers are draggable L.markers (circleMarkers can't drag in
;; Leaflet 1.9), using an inline-styled divIcon so no extra CSS is needed. The
;; 18×18 icon gives a comfortable click/drag hit target; click deletes, drag
;; moves. The drag recompute is debounced so it stays smooth (Phase-7 debounce).
(def ^:private waypoint-drag-dk "waypoint/drag")

(defn- waypoint-icon []
  (js/L.divIcon
    #js {:className "waypoint-marker"
         :iconSize #js [18 18]
         :iconAnchor #js [9 9]
         :html (str "<div style=\"width:18px;height:18px;border-radius:50%;"
                    "background:" accent-colour ";border:2px solid #0d1117;"
                    "box-shadow:0 0 4px rgba(0,0,0,0.6);cursor:pointer;\"></div>")}))

(defn- move-waypoint!
  "Write [lat lon] into the active route's waypoint `idx` and recompute. Shared
   by the marker dragend handler and the moveWaypoint test hook."
  [idx lat lon]
  (let [pts (active-points)]
    (when (< idx (count pts))
      (set-active-points! (assoc (vec pts) idx [lat lon]))
      (refresh-route!))))

(defn- draw-waypoint-markers! []
  (when-let [m @map-ref]
    (reset! waypoint-markers
            (vec
              (map-indexed
                (fn [idx [lat lon]]
                  (let [mk (js/L.marker
                             #js [lat lon]
                             #js {:icon (waypoint-icon) :draggable true})]
                    ;; Left/ctrl-click deletes; stopPropagation keeps the map
                    ;; click handler from also appending a new waypoint.
                    (.on mk "click"
                         (fn [e]
                           (js/L.DomEvent.stopPropagation e)
                           (delete-waypoint! idx)))
                    ;; Drag: update the marker's own latlng live, but debounce the
                    ;; heavy route recompute. Final position recomputes on dragend.
                    (.on mk "drag"
                         (fn [e]
                           (let [ll (.. e -target getLatLng)]
                             (when-let [t (get @debounce-timers waypoint-drag-dk)]
                               (js/clearTimeout t))
                             (swap! debounce-timers assoc waypoint-drag-dk
                                    (js/setTimeout
                                      (fn [] (move-waypoint! idx (.-lat ll) (.-lng ll)))
                                      debounce-ms)))))
                    (.on mk "dragend"
                         (fn [e]
                           (when-let [t (get @debounce-timers waypoint-drag-dk)]
                             (js/clearTimeout t))
                           (let [ll (.. e -target getLatLng)]
                             (move-waypoint! idx (.-lat ll) (.-lng ll)))))
                    (.addTo mk m)
                    mk))
                (active-points))))))

(defn- seg-tooltip [idx {:keys [depth-ord distance-ord]} {:keys [median lo89 hi89]}]
  (str "Seg " (inc idx) " — "
       (.toFixed (* 100.0 median) 1) "% ["
       (.toFixed (* 100.0 lo89) 1) "–"
       (.toFixed (* 100.0 hi89) 1) "% 89%CI]  "
       "Depth " depth-ord "  Dist " distance-ord))

(defn- draw-segments! [segs summaries]
  (when-let [m @map-ref]
    (reset! segment-lines
            (vec
              (map-indexed
                (fn [idx seg]
                  (let [{:keys [median] :as summ} (nth summaries idx)
                        {:keys [a b]} seg
                        ln (js/L.polyline
                             #js [#js [(first a) (second a)]
                                  #js [(first b) (second b)]]
                             #js {:color (risk-colour median) :weight 5
                                  :opacity 0.85})]
                    (.bindTooltip ln (seg-tooltip idx seg summ))
                    (.addTo ln m)
                    ln))
                segs)))))

(defn refresh-route!
  "Recompute the active route's sub-segments, per-segment risk, colours and the
   whole-route summary, then redraw. Phase 7 calls this on param change too."
  []
  (clear-route-graphics!)
  (draw-waypoint-markers!)
  (let [pts   (active-points)
        cfg   (when @posterior-data (core/derive-config @posterior-data))
        boat  @boat-params
        pass  @passage-params
        doy   (:doy pass)
        base  (:base-rate pass)
        ref   (:ref-nm pass)
        segs  (if (>= (count pts) 2) (build-segments pts) [])]
    (if (and cfg (seq segs))
      (let [summaries (mapv
                        (fn [{:keys [lat lon depth-ord distance-ord nm]}]
                          (core/segment-risk cfg lat lon doy boat
                                             (assoc pass
                                                    :depth-ord depth-ord
                                                    :distance-ord distance-ord)
                                             nm base ref))
                        segs)
            route (core/route-risk cfg boat pass
                                   (mapv #(select-keys % [:lat :lon :depth-ord
                                                          :distance-ord :nm])
                                         segs)
                                   doy base ref)]
        (reset! segment-summaries summaries)
        (reset! route-summary (assoc route :nm (reduce + 0.0 (map :nm segs))))
        (draw-segments! segs summaries))
      (do (reset! segment-summaries [])
          (reset! route-summary nil))))
  (refresh-comparison!))

(defn add-waypoint!
  "Append [lat lon] to the active route and rebuild segments."
  [lat lon]
  (set-active-points! (conj (active-points) [lat lon]))
  (refresh-route!))

(defn delete-waypoint!
  "Remove the waypoint at index `idx` from the active route and rebuild."
  [idx]
  (let [pts (active-points)]
    (set-active-points! (vec (concat (subvec (vec pts) 0 idx)
                                     (subvec (vec pts) (inc idx)))))
    (refresh-route!)))

(defn clear-route!
  "Drop every waypoint from the active route."
  []
  (set-active-points! [])
  (refresh-route!))

;; ── Multiple routes: tabs, switching, comparison (Phase 8) ───────────────────

(defn route-letter
  "Map a 0-based route index to its display letter (0→A, 1→B, …)."
  [idx]
  (str (char (+ 65 idx))))

(defn- route-summary-for
  "Whole-route {:median :lo89 :hi89} for the waypoints `pts`, or nil if <2."
  [pts]
  (let [cfg   (when @posterior-data (core/derive-config @posterior-data))
        boat  @boat-params
        pass  @passage-params
        doy   (:doy pass)
        base  (:base-rate pass)
        ref   (:ref-nm pass)
        segs  (if (>= (count pts) 2) (build-segments pts) [])]
    (when (and cfg (seq segs))
      (assoc (core/route-risk cfg boat pass
                              (mapv #(select-keys % [:lat :lon :depth-ord
                                                     :distance-ord :nm])
                                    segs)
                              doy base ref)
             :nm (reduce + 0.0 (map :nm segs))))))

(defn refresh-comparison!
  "Recompute every route's whole-route summary for the comparison panel."
  []
  (reset! route-comparison
          (mapv (fn [route] (route-summary-for (:points route))) @routes)))

(defn select-route!
  "Make route `idx` active: clear the current route's Leaflet graphics and
   redraw the now-active route's waypoints, segments and Total Risk panel."
  [idx]
  (when (and (>= idx 0) (< idx (count @routes)) (not= idx @active-route))
    (clear-route-graphics!)
    (reset! active-route idx)
    (refresh-route!)))

(defn add-route!
  "Append a new empty route and make it active. Returns the new route count."
  []
  (clear-route-graphics!)
  (swap! routes conj {:points [] :result nil})
  (reset! active-route (dec (count @routes)))
  (refresh-route!)
  (count @routes))

(defn delete-route!
  "Remove route `idx`, keeping at least one route. If the active route is
   removed, select a neighbour; redraw the resulting active route."
  [idx]
  (when (> (count @routes) 1)
    (clear-route-graphics!)
    (let [old-active @active-route
          new-routes (vec (concat (subvec @routes 0 idx)
                                  (subvec @routes (inc idx))))
          new-active (cond
                       (< old-active idx) old-active
                       (> old-active idx) (dec old-active)
                       :else (min idx (dec (count new-routes))))]
      (reset! routes new-routes)
      (reset! active-route new-active)
      (refresh-route!))))

(defn rebuild-static-cells!
  "Rebuild the per-cell location+season static part (the field drifts with doy and
   is scaled by base-rate/ref-nm), then re-tint and redraw the field GridLayer.
   Called when doy / base-rate / ref-nm change."
  []
  (when (and @posterior-data @geo-grid @map-ready?)
    (build-static-cells! (core/derive-config @posterior-data) @geo-grid))
  (refresh-risk-heat!))

;; Passage keys whose change drifts/rescales the field, so static-cells must be
;; rebuilt (not just the cheap dynamic-scalar re-tint).
(def ^:private static-rebuild-keys #{:doy :base-rate :ref-nm})

(defn recompute!
  "Recompute the dynamic vessel scalar (re-tinting the heatmap from the
   precomputed static-cells) and the route / per-segment risk."
  []
  (refresh-risk-heat!)
  (refresh-route!))

(defn recompute-static!
  "Rebuild the static-cells (doy/base-rate/ref-nm changed) then recompute route."
  []
  (rebuild-static-cells!)
  (refresh-route!))

(defn set-param!
  "Store a boat/passage param then recompute heatmap + route (test hook +
   Phase 7). Both the bound sidebar control and the programmatic test hook flow
   through here, so the <select>/slider value and the atom stay consistent.
   Changes to doy/base-rate/ref-nm rebuild the season-static cells."
  [group k v]
  (let [kw (keyword k)]
    (case group
      "boat" (swap! boat-params assoc kw v)
      "passage" (swap! passage-params assoc kw v)
      nil)
    (if (and (= group "passage") (contains? static-rebuild-keys kw))
      (do (recompute-static!)
          ;; the month also re-windows the historical-incident layers.
          (when (= kw :doy) (rebuild-incident-layers!)))
      (recompute!))))

;; ── Debounced slider commits (Phase 7.2) ─────────────────────────────────────
;;
;; Range-slider drags fire many on-input events; recomputing the heatmap + whole
;; route on each is wasteful and makes dragging stutter. We store the latest
;; value into the bound atom immediately (so the control tracks the thumb), but
;; debounce the heavy recompute ~80 ms keyed per control. The final settle always
;; produces a recompute, so correctness is preserved. Selects/toggles bypass this.
;; (debounce-timers / debounce-ms are declared up in the route-drawing state
;; block so the waypoint-drag handler can share them.)

(defn set-param-debounced!
  "Store a slider value immediately, but debounce the heavy recompute per `dk`."
  [group k v dk]
  (let [kw (keyword k)]
    (case group
      "boat" (swap! boat-params assoc kw v)
      "passage" (swap! passage-params assoc kw v)
      nil)
    (when-let [t (get @debounce-timers dk)]
      (js/clearTimeout t))
    (let [recompute-fn (if (and (= group "passage")
                                (contains? static-rebuild-keys kw))
                         recompute-static!
                         recompute!)]
      (swap! debounce-timers assoc dk
             (js/setTimeout (fn [] (recompute-fn)) debounce-ms)))))

(defn install-test-hooks!
  "Expose window.__planner so headless drivers exercise the real code paths."
  []
  (set! (.-__planner js/window)
        #js {:addWaypoint    (fn [lat lon] (add-waypoint! lat lon)
                               (count (active-points)))
             :deleteWaypoint (fn [idx] (delete-waypoint! idx)
                               (count (active-points)))
             :moveWaypoint   (fn [idx lat lon] (move-waypoint! idx lat lon)
                               (count (active-points)))
             :waypointCount  (fn [] (count (active-points)))
             :clearRoute     (fn [] (clear-route!) 0)
             :setParam       (fn [g k v] (set-param! g k v) true)
             :setDoy         (fn [doy] (set-param! "passage" "doy" doy)
                               (:doy @passage-params))
             :incidentCount  (fn [] (count (incidents-in-window)))
             :staticCellCount (fn [] (count @static-cells))
             :routeDistance  (fn [] (if @route-summary (:nm @route-summary) -1))
             :setRiskOpacity (fn [o] (set-risk-opacity! o)
                               (when-let [l @risk-heat-layer]
                                 (.. l -options -opacity)))
             :routeMedian    (fn [] (if @route-summary
                                      (:median @route-summary)
                                      -1))
             :routeCI        (fn [] (if @route-summary
                                      #js {:lo (:lo89 @route-summary)
                                           :hi (:hi89 @route-summary)}
                                      nil))
             :addRoute       (fn [] (add-route!))
             :routeCount     (fn [] (count @routes))
             :selectRoute    (fn [i] (select-route! i)
                               @active-route)
             ;; :animate false so the zoom applies synchronously — the test reads
             ;; getZoom right after and needs the new value, not a deferred one.
             :setZoom        (fn [z] (when-let [m @map-ref]
                                       (.setZoom m z #js {:animate false}))
                               (when @map-ref (.getZoom @map-ref)))}))

;; ── View ─────────────────────────────────────────────────────────────────────

(defn data-summary []
  (let [p @posterior-data
        g @geo-grid]
    (when (and p g)
      [:div
       [:div.num (str (:n_draws p) " posterior draws")]
       [:div.num (str (count @incidents) " incidents")]
       [:div.num (str (count (get g "cells")) " sea cells")]])))

(def ^:private layer-rows
  [[:incident-heat "Historical incidents (this month)"]
   [:risk-heat "Live risk heatmap"]
   [:incident-points "Incident points (this month)"]])

(defn- layer-toggle [k label]
  (let [id (str "layer-" (name k))
        on? (get @layer-visible k)]
    [:label.layer-row {:for id}
     [:input {:type "checkbox"
              :id id
              :checked on?
              :on-change (fn [_]
                           (let [next-on? (not (get @layer-visible k))]
                             (swap! layer-visible assoc k next-on?)
                             (set-layer-visible! k next-on?)))}]
     [:span label]]))

(defn- risk-opacity-row
  "Opacity slider (0..1) for the live-risk heatmap; applied immediately."
  []
  (let [o @risk-opacity]
    [:div.ctrl-row.ctrl-slider
     [:div.ctrl-slider-head
      [:span.ctrl-label "Risk heatmap opacity"]
      [:span.ctrl-value.mono (str (.toFixed (* 100.0 o) 0) "%")]]
     [:input {:type "range" :min 0 :max 1 :step 0.05 :value o
              :id "risk-opacity"
              :on-change (fn [e]
                           (set-risk-opacity!
                             (js/parseFloat (.. e -target -value))))}]]))

(defn layer-controls []
  [:div
   (for [[k label] layer-rows]
     ^{:key (name k)}
     [layer-toggle k label])
   [risk-opacity-row]])

(defn- pct-str [x]
  (str (.toFixed (* 100.0 x) 2) "%"))

(defn- nm-str
  "Format a nautical-mile distance: 1 decimal under 100 nm, whole nm above."
  [nm]
  (str (.toFixed nm (if (< nm 100) 1 0)) " nm"))

;; ── Vessel / conditions / model controls (Phase 7.1) ─────────────────────────

;; Fallback option lists, used until posterior-data (with its :categories) loads.
(def ^:private fallback-categories
  {:antifoul ["Black" "Blue" "Coppercoat" "Green" "Other" "Red" "Unknown" "White"]
   :hull_colour ["Dark colour" "Unknown" "White/light"]
   :rudder ["Full skeg" "Keel hung" "Semi skeg" "Spade" "Twin rudder" "Unknown"]
   :sailing_mode ["Hove-to" "Motoring" "Motorsailing" "Sailing" "Unknown"]})

(defn- category-options
  "Option list for category `cat-key`, from loaded posterior-data when present."
  [cat-key]
  (or (get-in @posterior-data [:categories cat-key])
      (get fallback-categories cat-key)))

(def ^:private speed-bands
  ["Slow (0)" "Cruising (1)" "Fast (2)" "Planing (3)"])

(def ^:private length-bands
  ["<8 m (0)" "8–12 m (1)" "12–18 m (2)" ">18 m (3)"])

(def ^:private wind-bands
  ["Calm (0)" "Light (1)" "Moderate (2)" "Strong (3)"])

(def ^:private sea-bands
  ["Smooth (0)" "Moderate (1)" "Rough (2)"])

(defn- select-row
  "A labelled <select> bound to `group`/`k`; commits via set-param! immediately."
  [label group k current options]
  [:label.ctrl-row
   [:span.ctrl-label label]
   [:select.ctrl-select
    {:value (str current)
     :on-change (fn [e] (set-param! group (name k) (.. e -target -value)))}
    (for [opt options]
      ^{:key opt} [:option {:value opt} opt])]])

(defn- int-slider-row
  "An integer range slider bound to `group`/`k`, with an ordinal band caption.
   Stores immediately and debounces the heavy recompute per control."
  [label group k current mn mx bands]
  (let [dk (str (name group) "/" (name k))]
    [:div.ctrl-row.ctrl-slider
     [:div.ctrl-slider-head
      [:span.ctrl-label label]
      [:span.ctrl-value.mono (nth bands current (str current))]]
     [:input {:type "range" :min mn :max mx :step 1 :value current
              :on-change (fn [e]
                           (set-param-debounced!
                             group (name k)
                             (js/parseInt (.. e -target -value) 10) dk))}]]))

(defn- pct-slider-row
  "Base-rate slider: shown as a percentage, stored as a fraction. min/max in %."
  [current]
  (let [dk "passage/base-rate"
        pct (* 100.0 current)]
    [:div.ctrl-row.ctrl-slider
     [:div.ctrl-slider-head
      [:span.ctrl-label "Base rate"]
      [:span.ctrl-value.mono (str (.toFixed pct 1) "%")]]
     [:input {:type "range" :min 1.0 :max 10.0 :step 0.5 :value pct
              :on-change (fn [e]
                           (set-param-debounced!
                             "passage" "base-rate"
                             (/ (js/parseFloat (.. e -target -value)) 100.0) dk))}]]))

(defn- ref-nm-slider-row
  "Reference passage-length slider (nm); affects absolute numbers only."
  [current]
  (let [dk "passage/ref-nm"]
    [:div.ctrl-row.ctrl-slider
     [:div.ctrl-slider-head
      [:span.ctrl-label "Reference length"]
      [:span.ctrl-value.mono (str current " nm")]]
     [:input {:type "range" :min 25 :max 300 :step 25 :value current
              :on-change (fn [e]
                           (set-param-debounced!
                             "passage" "ref-nm"
                             (js/parseInt (.. e -target -value) 10) dk))}]]))

(defn vessel-controls []
  (let [b @boat-params]
    [:div.panel
     [:h2 "Vessel"]
     [select-row "Antifoul" "boat" :antifoul (:antifoul b)
      (category-options :antifoul)]
     [select-row "Hull" "boat" :hull (:hull b)
      (category-options :hull_colour)]
     [select-row "Rudder" "boat" :rudder (:rudder b)
      (category-options :rudder)]
     [select-row "Mode" "boat" :sailing (:sailing b)
      (category-options :sailing_mode)]
     ;; Autopilot kept visible but disabled: the rebuilt presence-effort model has
     ;; no autopilot predictor (the incident reports lack the field), so toggling
     ;; it would not change risk. Shown disabled with a note rather than removed.
     [:label.ctrl-row.ctrl-toggle {:title "Not a predictor in the rebuilt model"}
      [:span.ctrl-label "Autopilot"]
      [:input {:type "checkbox" :disabled true :checked false}]
      [:span.subtitle {:style {:margin-left "6px"}}
       "(not a predictor in the rebuilt model)"]]
     [int-slider-row "Speed" "boat" :speed (:speed b) 0 3 speed-bands]
     [int-slider-row "Length" "boat" :length (:length b) 0 3 length-bands]]))

;; Month -> representative day-of-year (mid-month). The spatial field drifts with
;; doy, so choosing a month re-seasons the heatmap and the route numbers.
(def ^:private month-doy
  [["January" 15] ["February" 46] ["March" 74] ["April" 105]
   ["May" 135] ["June" 166] ["July" 196] ["August" 232]
   ["September" 258] ["October" 288] ["November" 319] ["December" 349]])

(defn- doy->month-name
  "Nearest month label for a day-of-year (for showing the current selection)."
  [doy]
  (->> month-doy
       (apply min-key (fn [[_ d]] (js/Math.abs (- d doy))))
       first))

(defn- month-row
  "Month <select> bound to passage :doy; commits the mid-month doy via set-param!."
  [current-doy]
  [:label.ctrl-row
   [:span.ctrl-label "Month"]
   [:select.ctrl-select
    {:value (doy->month-name current-doy)
     :on-change (fn [e]
                  (let [nm (.. e -target -value)
                        doy (second (first (filter #(= nm (first %)) month-doy)))]
                    (set-param! "passage" "doy" doy)))}
    (for [[nm _] month-doy]
      ^{:key nm} [:option {:value nm} nm])]])

(defn conditions-controls []
  (let [p @passage-params]
    [:div.panel
     [:h2 "Conditions"]
     [month-row (:doy p)]
     [select-row "Time of day" "passage" :daylight (:daylight p)
      ["Day" "Night" "Average"]]
     [int-slider-row "Wind" "passage" :wind (:wind p) 0 3 wind-bands]
     [int-slider-row "Sea" "passage" :sea (:sea p) 0 2 sea-bands]]))

(defn model-controls []
  (let [p @passage-params]
    [:div.panel
     [:h2 "Model"]
     [pct-slider-row (:base-rate p)]
     [ref-nm-slider-row (:ref-nm p)]]))

(defn route-panel
  "Reactive Total-Risk panel; also hosts the #id nodes the headless gates read."
  []
  (let [route @route-summary
        summaries @segment-summaries]
    [:div.panel
     [:h2 "Route"]
     [:div.num "Waypoints: " [:span#waypoint-count (count (active-points))]]
     [:div.subtitle "Click the map to add waypoints; click a marker to delete."]
     [:div.num "First segment: "
      [:span#segment-risk-0
       (if (seq summaries) (pct-str (:median (first summaries))) "")]]
     [:div.num.mono "Distance: "
      [:span#route-distance (if route (nm-str (:nm route)) "—")]]
     [:h2 {:style {:margin-top "12px"}} "Total risk"]
     [:div.num.mono "Median: "
      [:span#route-total-risk (if route (pct-str (:median route)) "—")]]
     [:div.num.mono {:style {:display "none"}}
      [:span#risk-median (if route (pct-str (:median route)) "—")]]
     [:div.num.mono "89% CI: "
      [:span#ci-lo (if route (pct-str (:lo89 route)) "—")]
      " – "
      [:span#ci-hi (if route (pct-str (:hi89 route)) "—")]]]))

(defn route-tabs
  "Route Variants tab strip: one tab per route with a delete control, plus an
   Add button. Tabs switch the active route; deletes keep at least one route."
  []
  (let [rs     @routes
        active @active-route
        many?  (> (count rs) 1)]
    [:div.panel
     [:h2 "Route variants"]
     [:div.route-tabs
      (doall
        (for [idx (range (count rs))]
          ^{:key idx}
          [:div.route-tab {:class (when (= idx active) "active")
                           :on-click (fn [_] (select-route! idx))}
           [:span.route-tab-label (str "Route " (route-letter idx))]
           (when many?
             [:span.route-tab-close
              {:title "Delete route"
               :on-click (fn [e]
                           (.stopPropagation e)
                           (delete-route! idx))}
              "×"])]))
      [:button.route-add
       {:id "add-route-btn"
        :on-click (fn [_] (add-route!))}
       "+ Add"]]]))

(defn comparison-panel
  "Side-by-side whole-route risk for every route, reactive on waypoints/params."
  []
  (let [cmp @route-comparison]
    [:div.panel
     [:h2 "Comparison"]
     (doall
       (for [idx (range (count @routes))]
         (let [summ (nth cmp idx nil)]
           ^{:key idx}
           [:div.cmp-row {:class (when (= idx @active-route) "active")}
            [:span.cmp-label (str "Route " (route-letter idx))]
            [:span.cmp-value.mono
             (if summ
               (str (.toFixed (* 100.0 (:median summ)) 1) "%"
                    " [" (.toFixed (* 100.0 (:lo89 summ)) 1) "–"
                    (.toFixed (* 100.0 (:hi89 summ)) 1) "% 89% CI]"
                    " · " (nm-str (:nm summ)))
               "— draw a route")]])))]))

(defn caveat-note
  "Muted methodological caveat for the presence-effort-seasonal model;
   interpolates the live reference passage length."
  []
  (let [ref (:ref-nm @passage-params)]
    [:div#caveat.caveat
     (str "Risk is the probability of at least one interaction over the whole "
          "route, accumulated as a Poisson hazard along its length. The spatial "
          "term is a bounded, season-drifting occupancy field — a relative risk "
          "with mean about 1 over sailed waters, so hotspots are elevated but "
          "never run away. The absolute level is anchored to a base-rate over a "
          ref " nm reference passage (not an independently calibrated absolute "
          "probability). The heatmap colour is a posterior-mean point estimate, "
          "while the route numbers carry the full 89% credible interval. The "
          "seasonal hotspot follows the pod's known north-south tuna-following "
          "cycle (Strait of Gibraltar in winter, Galician/Portuguese coast in "
          "summer).")]))

(defn sidebar-root []
  [:div
   [:h1 "🌊 Orca Route Planner"]
   [:p.subtitle "Bayesian transit-risk planner for the Iberian orca zone."]
   [:div.panel
    [:h2 "Data"]
    (if @posterior-data
      [data-summary]
      [:div.num "Loading…"])]
   [route-tabs]
   [:div.panel
    [:h2 "Map layers"]
    [layer-controls]]
   [vessel-controls]
   [conditions-controls]
   [model-controls]
   [route-panel]
   [comparison-panel]
   [caveat-note]])

;; ── Mount ────────────────────────────────────────────────────────────────────

(defn mount! []
  (when-let [el (js/document.getElementById "sidebar-root")]
    (rdom/render [sidebar-root] el)))

(defn init! []
  (mount!)
  (load-data!))

;; A guard so a sanity-check of `core` cannot be tree-shaken; also confirms the
;; core namespace resolved (catches script-order regressions early).
(assert (fn? core/sigmoid))

(init!)
