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
;; Land rings ([[ [lat lon] … ] …]) for clipping the heatmap to the coastline,
;; plus a per-zoom cache of those rings projected to CRS pixels (see paint).
(defonce coastline (r/atom nil))
(defonce coastline-px (atom {:zoom nil :rings nil}))

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
           :base-rate 0.00315
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

;; "li,oi" → index-into-static-cells JS object, so the tile painter can look up
;; only the cells whose 0.1° grid coordinates fall inside the tile it is drawing.
(defonce cell-index (atom #js {}))

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
  "Fetch all data files (posterior, reports, geo grid, coastline); populate the
   atoms and report Ready only after every fetch has resolved. Errors are logged
   to the console (Scittle prints there, not to the page)."
  []
  (set-status! "Loading data…")
  (-> (js/Promise.all
       #js [(fetch-json "posterior_planner.json")
            (fetch-json "../orca_reportlist.json")
            (fetch-json "geo_grid.json")
            (fetch-json "coastline.json")])
      (.then
       (fn [results]
         (let [posterior (js->clj (aget results 0) :keywordize-keys true)
               reports   (js->clj (aget results 1) :keywordize-keys false)
                ;; geo-grid cells must keep STRING keys ("361,-59") for lookup,
                ;; so do NOT keywordize this one.
               grid      (js->clj (aget results 2) :keywordize-keys false)
               coast     (js->clj (aget results 3) :keywordize-keys false)]
           (reset! posterior-data posterior)
           (reset! incidents (parse-incidents reports))
           (reset! geo-grid grid)
           (reset! coastline (get coast "polygons"))
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

;; The model field (the costly 84-term RBF) is smooth, so we evaluate it only on a
;; coarse `model-stride`×0.1° sub-grid, but RENDER every 0.1° sea cell (each borrows
;; the nearest coarse sample). That way the painted region is exactly the sea — land
;; is masked at the grid's own 0.1° resolution, with no bleed past the coastline —
;; while a month change only recomputes the coarse samples.
(def ^:private model-stride 3)

(defn- key->ints
  "Parse an integer-tenths grid key \"LI,OI\" into [li oi]."
  [k]
  (let [c (.split k ",")]
    [(js/parseInt (aget c 0) 10) (js/parseInt (aget c 1) 10)]))

(defn- nearest-coarse
  "The coarse sample S nearest integer-tenths (li,oi), searching outward rings up
   to model-stride+1 cells (every sea cell has a coarse sample that close)."
  [coarse li oi]
  (loop [r 0]
    (when (<= r (inc model-stride))
      (or (some (fn [[di dj]]
                  (when (= r (max (js/Math.abs di) (js/Math.abs dj)))
                    (get coarse (str (+ li di) "," (+ oi dj)))))
                (for [di (range (- r) (inc r))
                      dj (range (- r) (inc r))]
                  [di dj]))
          (recur (inc r))))))

(defn- bilinear-coarse
  "BILINEARLY interpolate the coarse-sample S ({:rr :static-mult :scale}) at the
   0.1° cell (li,oi) from the four surrounding model-stride samples (the ones that
   are sea), so the field blends smoothly over the model tile instead of stepping
   in coarse blocks. Falls back to the nearest sample if none of the four exist."
  [coarse li oi]
  (let [s  model-stride
        l0 (* s (js/Math.floor (/ li s)))
        o0 (* s (js/Math.floor (/ oi s)))
        u  (/ (- li l0) s)
        v  (/ (- oi o0) s)]
    (loop [cs [[l0 o0 (* (- 1.0 u) (- 1.0 v))]
               [(+ l0 s) o0 (* u (- 1.0 v))]
               [l0 (+ o0 s) (* (- 1.0 u) v)]
               [(+ l0 s) (+ o0 s) (* u v)]]
           rr 0.0
           sm 0.0
           sc nil
           w  0.0]
      (if (empty? cs)
        (if (pos? w)
          {:rr (/ rr w) :static-mult (/ sm w) :scale sc}
          (nearest-coarse coarse li oi))
        (let [[cl co cw] (first cs)
              cell-s (get coarse (str cl "," co))]
          (if (and cell-s (pos? cw))
            (recur (rest cs)
                   (+ rr (* cw (:rr cell-s)))
                   (+ sm (* cw (:static-mult cell-s)))
                   (:scale cell-s)
                   (+ w cw))
            (recur (rest cs) rr sm sc w)))))))

(defn- build-static-cells!
  "Precompute the per-cell location+season static part once. The costly model
   field is sampled on a coarse model-stride sub-grid; every 0.1° SEA cell is then
   a render cell whose :S is BILINEARLY interpolated from the four surrounding
   coarse samples (so the field is smooth across the model tiles, not blocky),
   while the 0.1° mask keeps the painted region exactly the sea. Each :S is a
   {:rr :static-mult :scale} map. Rebuild when doy/base-rate/ref-nm change.
   Stores [{:li :oi :lat :lon :S} …] in static-cells and a \"li,oi\"→index JS
   object in cell-index (so the painter can look up only the cells in a tile)."
  [cfg grid]
  (let [mean-sd   (core/mean-spatial-draw cfg)
        mean-attr (core/mean-attr-draw cfg)
        pass      @passage-params
        doy       (:doy pass)
        base-rate (:base-rate pass)
        ref-nm    (:ref-nm pass)
        cells     (get grid "cells")
        ;; 1. coarse model samples on the model-stride sub-grid (sea cells only).
        coarse    (persistent!
                   (reduce
                    (fn [m [k v]]
                      (let [[li oi] (key->ints k)]
                        (if (and (zero? (mod li model-stride))
                                 (zero? (mod oi model-stride)))
                          (assoc! m k (core/heatmap-static
                                       cfg mean-sd mean-attr
                                       (/ li 10.0) (/ oi 10.0) doy
                                       (get v "m") (get v "c") base-rate ref-nm))
                          m)))
                    (transient {})
                    cells))
        ;; 2. render every sea cell, bilinearly interpolating the coarse samples.
        idx       #js {}
        out       (reduce
                   (fn [[acc i] [k _v]]
                     (let [[li oi] (key->ints k)
                           s (bilinear-coarse coarse li oi)]
                       (if s
                         (do (aset idx (str li "," oi) i)
                             [(conj! acc {:li li :oi oi :lat (/ li 10.0)
                                          :lon (/ oi 10.0) :S s})
                              (inc i)])
                         [acc i])))
                   [(transient []) 0]
                   cells)]
    (reset! static-cells (persistent! (first out)))
    (reset! cell-index idx)
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

;; The 0.1° intensity lattice (static-cells / cell-index) is the model's mask and
;; sampling grid. For display we paint a finer pixel sub-grid (`sub-step` px, ≈ a
;; third of a 0.1° cell at mid zoom) and BILINEARLY sample the lattice, so the field
;; is smooth between cells and ~3× the cell resolution. The painted field is then
;; clipped to the real coastline (Natural Earth 10m land) by erasing land pixels.
(def ^:private sub-step 6)

;; FIXED, GLOBAL display domain for the colour ramp. The per-cell intensity is
;; count->prob(scale·RR·static-mult·d-scalar) (~0.1145 ceiling). After the model
;; recalibration the field tops out ~8× lower than before, so the domain is sized
;; from the MEASURED field distribution (tmp_sim/heatmap_domain.js, over all sea
;; cells at the default/reference vessel + default month):
;;   p50≈0.0014  p80≈0.006  p90≈0.017  p95≈0.030  p98≈0.049  p99≈0.064  max≈0.091.
;; risk-lo = a small floor just above the quiet-water background (p50–p70 sit at
;; ~0.001–0.005) so open water reads green; risk-hi ≈ the default field's ~p98 with
;; a little headroom (set at 0.06, above p98≈0.049) so the default hotspot reads
;; clearly orange→red yet a higher-risk vessel / peak season (which push hotspots
;; toward ~0.09–0.11, near the count->prob ceiling) can still drive further into the
;; red without the default view instantly clipping. This domain is INTENTIONALLY
;; FIXED and GLOBAL — NOT per-frame/per-selection normalized — so a given intensity
;; always maps to the same colour regardless of vessel/month/depth/base_rate.
;;
;; FIXED NONLINEAR (gamma) RAMP — why: the field is heavily RIGHT-SKEWED, so a
;; LINEAR normalize t=(I-lo)/(hi-lo) leaves nearly everything green (p90≈0.017 maps
;; to only t≈0.22) and the field reads faint, with just a pinprick hotspot coloured.
;; We instead apply a FIXED gamma lift t' = t^risk-gamma (risk-gamma<1, like a sqrt)
;; BEFORE the colour ramp. This is still a fixed, selection-INDEPENDENT function of
;; the per-cell intensity — the SAME intensity always yields the SAME colour — it
;; just re-curves the *display* so low-but-nonzero cells gain colour. With
;; risk-gamma=0.5 (sqrt): p90 (t≈0.22) → t'≈0.47 (yellow), p95 (t≈0.45) →
;; t'≈0.67 (yellow→orange), p98 (t≈0.80) → t'≈0.89 (red), while the p50 background
;; still sits at/below the floor and reads green. field-alpha is raised so the colours
;; sit strongly over the dark basemap instead of washing out.
(def ^:private risk-lo 0.004)
(def ^:private risk-hi 0.06)
(def ^:private risk-gamma 0.5)
(def ^:private field-alpha 0.72)

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
  "CSS rgba() fill string for a raw intensity on the fixed [risk-lo,risk-hi] domain.
   t is normalized on the fixed global domain then gamma-lifted (t^risk-gamma,
   risk-gamma<1) to counter the field's right-skew — a fixed, selection-independent
   transform, so the SAME intensity always maps to the SAME colour."
  [intensity]
  (let [t  (-> (/ (- intensity risk-lo) (- risk-hi risk-lo))
               (max 0.0) (min 1.0))
        t' (js/Math.pow t risk-gamma)
        [r g b] (ramp-rgb t')]
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

(defn- sample-intensity
  "Bilinearly sample the 0.1° intensity lattice at fractional integer-tenths
   (fli,foi), using whichever of the 4 surrounding cells exist (sea). Returns nil
   when no surrounding cell is sea, so land/void sub-cells are left unpainted."
  [idx cell-ints fli foi]
  (let [li0 (js/Math.floor fli)
        oi0 (js/Math.floor foi)
        u   (- fli li0)
        v   (- foi oi0)]
    (loop [cs [[li0 oi0 (* (- 1.0 u) (- 1.0 v))]
               [(inc li0) oi0 (* u (- 1.0 v))]
               [li0 (inc oi0) (* (- 1.0 u) v)]
               [(inc li0) (inc oi0) (* u v)]]
           sum 0.0
           wsum 0.0]
      (if (empty? cs)
        (when (pos? wsum) (/ sum wsum))
        (let [[li oi w] (first cs)
              i (aget idx (str li "," oi))]
          (if (and i (pos? w))
            (recur (rest cs) (+ sum (* w (aget cell-ints i))) (+ wsum w))
            (recur (rest cs) sum wsum)))))))

(defn- ensure-coastline-px!
  "Project every coastline land ring to CRS pixels at zoom z (with a pixel bbox per
   ring for tile culling), cached so a same-zoom re-tint reuses it."
  [m z]
  (when (not= z (:zoom @coastline-px))
    (let [rings (mapv
                 (fn [ring]
                    ;; pts is a JS array of #js [x y] so the painter can aget it.
                   (let [pts (to-array
                              (map (fn [[lat lon]]
                                     (let [p (.project m (js/L.latLng lat lon) z)]
                                       #js [(.-x p) (.-y p)]))
                                   ring))
                         xs  (map #(aget % 0) pts)
                         ys  (map #(aget % 1) pts)]
                     {:pts pts
                      :minx (apply min xs) :maxx (apply max xs)
                      :miny (apply min ys) :maxy (apply max ys)}))
                 @coastline)]
      (reset! coastline-px {:zoom z :rings rings}))))

(defn- erase-land!
  "Clip the painted field to the sea by erasing the land rings from this tile
   (destination-out makes the filled land pixels transparent)."
  [ctx m z ox oy]
  (when (seq @coastline)
    (ensure-coastline-px! m z)
    (set! (.-globalCompositeOperation ctx) "destination-out")
    (set! (.-fillStyle ctx) "rgba(0,0,0,1)")
    (doseq [{:keys [pts minx maxx miny maxy]} (:rings @coastline-px)]
      ;; cull rings whose pixel bbox does not overlap this tile's pixel rect.
      (when (and (< minx (+ ox 256)) (> maxx ox)
                 (< miny (+ oy 256)) (> maxy oy))
        (.beginPath ctx)
        (let [p0 (aget pts 0)]
          (.moveTo ctx (- (aget p0 0) ox) (- (aget p0 1) oy)))
        (dotimes [k (dec (alength pts))]
          (let [p (aget pts (inc k))]
            (.lineTo ctx (- (aget p 0) ox) (- (aget p 1) oy))))
        (.closePath ctx)
        (.fill ctx)))
    (set! (.-globalCompositeOperation ctx) "source-over")))

(defn- paint-risk-tile!
  "Paint one map tile: a smooth bilinear raster of the risk field on a pixel
   sub-grid, then clipped to the coastline by erasing land. `coords` carries the
   tile's z/x/y; Leaflet tiles are 256 px square."
  [m canvas coords]
  (let [ctx  (.getContext canvas "2d")
        z    (.-z coords)
        tw   256
        th   256
        ox   (* (.-x coords) tw)
        oy   (* (.-y coords) th)
        idx  @cell-index
        cell-ints @cell-intensities
        ;; tile geographic bounds (nw = top-left = max lat / min lon).
        nw   (.unproject m (js/L.point ox oy) z)
        se   (.unproject m (js/L.point (+ ox tw) (+ oy th)) z)
        nw-lat (.-lat nw) se-lat (.-lat se)
        nw-lng (.-lng nw) se-lng (.-lng se)
        half (/ sub-step 2.0)]
    (set! (.-width canvas) tw)
    (set! (.-height canvas) th)
    ;; 1. smooth field: bilinearly sample the lattice on a pixel sub-grid.
    (loop [py 0]
      (when (< py th)
        (loop [px 0]
          (when (< px tw)
            (let [lon (lerp nw-lng se-lng (/ (+ px half) tw))
                  lat (lerp nw-lat se-lat (/ (+ py half) th))
                  iv  (sample-intensity idx cell-ints (* lat 10.0) (* lon 10.0))]
              (when iv
                (set! (.-fillStyle ctx) (intensity->rgba iv))
                (.fillRect ctx px py sub-step sub-step)))
            (recur (+ px sub-step))))
        (recur (+ py sub-step))))
    ;; 2. clip to the coastline.
    (erase-land! ctx m z ox oy)))

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
  "Look up {:m :distance_ord} for (lat,lon) in the sea-cell grid: continuous
   seafloor depth `m` (metres, +down) and the distance-from-coast ordinal `c`.
   Falls back to the nearest neighbour within ±3 cells; off-grid (open ocean far
   from any cell) defaults to a deep abyssal depth (4000 m) and far distance (3)."
  [lat lon]
  (let [cells (get @geo-grid "cells")
        li    (js/Math.round (* lat 10.0))
        oi    (js/Math.round (* lon 10.0))
        cell-at (fn [a b] (get cells (str a "," b)))]
    (if-let [c (cell-at li oi)]
      {:m (get c "m") :distance_ord (int (get c "c"))}
      (loop [rad 1]
        (if (> rad 3)
          {:m 4000 :distance_ord 3}
          (let [hit (first
                     (for [da (range (- rad) (inc rad))
                           db (range (- rad) (inc rad))
                           :let [c (cell-at (+ li da) (+ oi db))]
                           :when c]
                       c))]
            (if hit
              {:m (get hit "m") :distance_ord (int (get hit "c"))}
              (recur (inc rad)))))))))

(defn build-segments
  "Subdivide each consecutive waypoint pair of `pts` into sub-segments of about
   `seg-step-nm`, returning a vector of {:lat :lon :depth-m :distance-ord :nm
   :a :b} where [:a :b] are the polyline endpoints for drawing. :depth-m is the
   continuous seafloor depth at the sub-segment midpoint (feeds the spatial RR)."
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
                  {:keys [m distance_ord]} (geo-lookup mlat mlon)]
              {:lat mlat :lon mlon
               :depth-m m :distance-ord distance_ord
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

(defn- seg-tooltip [idx {:keys [depth-m distance-ord]} {:keys [median lo89 hi89]}]
  (str "Seg " (inc idx) " — "
       (.toFixed (* 100.0 median) 1) "% ["
       (.toFixed (* 100.0 lo89) 1) "–"
       (.toFixed (* 100.0 hi89) 1) "% 89%CI]  "
       "Depth " (when depth-m (js/Math.round depth-m)) "m  Dist " distance-ord))

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
                       (fn [{:keys [lat lon depth-m distance-ord nm]}]
                         (core/segment-risk cfg lat lon doy boat
                                            (assoc pass
                                                   :depth-m depth-m
                                                   :distance-ord distance-ord)
                                            nm base ref))
                       segs)
            route (core/route-risk cfg boat pass
                                   (mapv #(select-keys % [:lat :lon :depth-m
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
                              (mapv #(select-keys % [:lat :lon :depth-m
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
             :coastlineRings (fn [] (count @coastline))
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
                               (when @map-ref (.getZoom @map-ref)))
             :setView        (fn [lat lon z]
                               (when-let [m @map-ref]
                                 (.setView m #js [lat lon] z
                                           #js {:animate false}))
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
     ;; Range widened for the recalibrated reference-anchored base_rate: the new
     ;; default is ~0.8%, so the slider spans 0.2%..5% (step 0.1%) and the default
     ;; sits comfortably in the lower-middle.
     [:input {:type "range" :min 0.2 :max 5.0 :step 0.1 :value pct
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
