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

;; ── Route-drawing state (Phase 6) ────────────────────────────────────────────

;; Leaflet object handles for the active route's waypoint markers and the
;; coloured sub-segment polylines, kept so a rebuild can clear them.
(defonce waypoint-markers (atom []))
(defonce segment-lines (atom []))

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

;; ── DOM status helpers ───────────────────────────────────────────────────────

(defn set-status! [text]
  (when-let [el (js/document.getElementById "status")]
    (set! (.-textContent el) text)))

(defn reveal-loaded! [text]
  (when-let [el (js/document.getElementById "status-loaded")]
    (set! (.-textContent el) text)
    (set! (.. el -style -display) "block")))

;; ── Data parsing ─────────────────────────────────────────────────────────────

(defn parse-incidents
  "Pull [[lat lon] …] out of reports.incident.<id>.{lat,long}."
  [report-json]
  (let [incident (get-in report-json ["reports" "incident"])]
    (->> (vals incident)
         (keep (fn [r]
                 (let [lat (get r "lat")
                       lon (get r "long")]
                   (when (and (number? lat) (number? lon))
                     [lat lon]))))
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
                 (count (:draws posterior)) " draws, "
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

(def ^:private risk-gradient
  "Green→yellow→red ramp for the live model-risk heatmap."
  #js {"0.0" "transparent" "0.2" "#2dc653" "0.5" "#ffd166"
       "0.8" "#e63946" "1.0" "#e63946"})

(defn- parse-cell-key
  "Recover the cell centre [lat lon] from a \"LI,OI\" grid key (lat=LI/10)."
  [k]
  (let [[li oi] (.split k ",")]
    [(/ (js/parseInt li 10) 10.0)
     (/ (js/parseInt oi 10) 10.0)]))

(defn- build-static-cells!
  "Sub-sample the geo grid (~every 3rd cell per axis ≈ 0.3°) and precompute the
   per-cell static logit part S once. Stores [{:lat :lon :S} …] in static-cells."
  [cfg md grid]
  (let [cells (get grid "cells")
        out   (transient [])]
    (doseq [[k v] cells]
      (let [[lat lon] (parse-cell-key k)
            ;; sub-sample on the 0.1° integer indices: keep every 3rd in each axis
            li (js/Math.round (* lat 10.0))
            oi (js/Math.round (* lon 10.0))]
        (when (and (zero? (mod li 3)) (zero? (mod oi 3)))
          (let [s (core/heatmap-static cfg md lat lon (get v "d") (get v "c"))]
            (conj! out {:lat lat :lon lon :S s})))))
    (reset! static-cells (persistent! out))
    (count @static-cells)))

(defn- risk-heat-data
  "Build the [[lat lon intensity] …] JS array for the live-risk layer from the
   precomputed statics plus a freshly-computed dynamic scalar D."
  [cfg md]
  (let [d   (core/dynamic-scalar cfg md @boat-params @passage-params)
        day (:daylight @passage-params)]
    (->> @static-cells
         (mapv (fn [{:keys [lat lon S]}]
                 #js [lat lon (core/heatmap-intensity d S day)]))
         (apply array))))

(defn refresh-risk-heat!
  "Recompute D and rebuild the live-risk heat layer's data array in place.
   Cheap: S is never recomputed (later phases call this on param change)."
  []
  (when-let [layer @risk-heat-layer]
    (let [cfg (core/derive-config @posterior-data)
          md  (core/mean-draw (:draws @posterior-data))]
      (.setLatLngs layer (risk-heat-data cfg md)))))

(defn- make-incident-heat-layer []
  (js/L.heatLayer
   (apply array (mapv (fn [[lat lon]] #js [lat lon 1.0]) @incidents))
   #js {:radius 18 :blur 22 :max 1.0 :gradient incident-gradient}))

(defn- make-risk-heat-layer [cfg md]
  (js/L.heatLayer
   (risk-heat-data cfg md)
   #js {:radius 16 :blur 18 :max 1.0 :gradient risk-gradient}))

(defn- make-incident-points-layer []
  (let [grp (js/L.layerGroup)]
    (doseq [[lat lon] @incidents]
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

(defn init-map!
  "Initialize the Leaflet map and all three layers once data is loaded. Guarded
   so it runs at most once."
  []
  (when (and (not @map-ready?)
             @posterior-data @geo-grid (js/document.getElementById "map"))
    (let [cfg (core/derive-config @posterior-data)
          md  (core/mean-draw (:draws @posterior-data))
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
      (let [n (build-static-cells! cfg md @geo-grid)]
        (js/console.log (str "orca planner: precomputed " n " static cells")))
      (reset! incident-heat-layer (make-incident-heat-layer))
      (reset! risk-heat-layer (make-risk-heat-layer cfg md))
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

(defn- draw-waypoint-markers! []
  (when-let [m @map-ref]
    (reset! waypoint-markers
            (vec
             (map-indexed
              (fn [idx [lat lon]]
                (let [mk (js/L.circleMarker
                          #js [lat lon]
                          #js {:radius 6 :color accent-colour :weight 2
                               :fillColor accent-colour :fillOpacity 0.9})]
                  (.on mk "click"
                       (fn [e]
                         (.stopPropagation (.-originalEvent e))
                         (delete-waypoint! idx)))
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
        draws (:draws @posterior-data)
        boat  @boat-params
        pass  @passage-params
        ref   (:ref-nm pass)
        segs  (if (>= (count pts) 2) (build-segments pts) [])]
    (if (and cfg (seq segs))
      (let [summaries (mapv
                       (fn [{:keys [lat lon depth-ord distance-ord nm]}]
                         (core/segment-risk cfg draws lat lon depth-ord
                                            distance-ord boat pass nm ref))
                       segs)
            route (core/route-risk cfg draws boat pass
                                   (mapv #(select-keys % [:lat :lon :depth-ord
                                                          :distance-ord :nm])
                                         segs)
                                   ref)]
        (reset! segment-summaries summaries)
        (reset! route-summary route)
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
        draws (:draws @posterior-data)
        boat  @boat-params
        pass  @passage-params
        ref   (:ref-nm pass)
        segs  (if (>= (count pts) 2) (build-segments pts) [])]
    (when (and cfg (seq segs))
      (core/route-risk cfg draws boat pass
                       (mapv #(select-keys % [:lat :lon :depth-ord
                                              :distance-ord :nm])
                             segs)
                       ref))))

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

(defn recompute!
  "Recompute the dynamic scalar D (re-tinting the heatmap from the precomputed
   static-cells, never recomputing S) and the route / per-segment risk."
  []
  (refresh-risk-heat!)
  (refresh-route!))

(defn set-param!
  "Store a boat/passage param then recompute heatmap + route (test hook +
   Phase 7). Both the bound sidebar control and the programmatic test hook flow
   through here, so the <select>/slider value and the atom stay consistent."
  [group k v]
  (let [kw (keyword k)]
    (case group
      "boat" (swap! boat-params assoc kw v)
      "passage" (swap! passage-params assoc kw v)
      nil)
    (recompute!)))

;; ── Debounced slider commits (Phase 7.2) ─────────────────────────────────────
;;
;; Range-slider drags fire many on-input events; recomputing the heatmap + whole
;; route on each is wasteful and makes dragging stutter. We store the latest
;; value into the bound atom immediately (so the control tracks the thumb), but
;; debounce the heavy recompute ~80 ms keyed per control. The final settle always
;; produces a recompute, so correctness is preserved. Selects/toggles bypass this.

(defonce ^:private debounce-timers (atom {}))

(def ^:private debounce-ms 80)

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
    (swap! debounce-timers assoc dk
           (js/setTimeout (fn [] (recompute!)) debounce-ms))))

(defn install-test-hooks!
  "Expose window.__planner so headless drivers exercise the real code paths."
  []
  (set! (.-__planner js/window)
        #js {:addWaypoint    (fn [lat lon] (add-waypoint! lat lon)
                               (count (active-points)))
             :deleteWaypoint (fn [idx] (delete-waypoint! idx)
                               (count (active-points)))
             :waypointCount  (fn [] (count (active-points)))
             :clearRoute     (fn [] (clear-route!) 0)
             :setParam       (fn [g k v] (set-param! g k v) true)
             :routeMedian    (fn [] (if @route-summary
                                      (:median @route-summary)
                                      -1))
             :addRoute       (fn [] (add-route!))
             :routeCount     (fn [] (count @routes))
             :selectRoute    (fn [i] (select-route! i)
                               @active-route)}))

;; ── View ─────────────────────────────────────────────────────────────────────

(defn data-summary []
  (let [p @posterior-data
        g @geo-grid]
    (when (and p g)
      [:div
       [:div.num (str (count (:draws p)) " posterior draws")]
       [:div.num (str (count @incidents) " incidents")]
       [:div.num (str (count (get g "cells")) " sea cells")]])))

(def ^:private layer-rows
  [[:incident-heat "Historical incidents"]
   [:risk-heat "Live risk heatmap"]
   [:incident-points "Incident points"]])

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

(defn layer-controls []
  [:div
   (for [[k label] layer-rows]
     ^{:key (name k)}
     [layer-toggle k label])])

(defn- pct-str [x]
  (str (.toFixed (* 100.0 x) 2) "%"))

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

(defn- toggle-row
  "A labelled checkbox bound to a 0/1 boat/passage key."
  [label group k current]
  (let [id (str "ctrl-" (name group) "-" (name k))]
    [:label.ctrl-row.ctrl-toggle {:for id}
     [:span.ctrl-label label]
     [:input {:type "checkbox"
              :id id
              :checked (= 1 current)
              :on-change (fn [e]
                           (set-param! group (name k)
                                       (if (.. e -target -checked) 1 0)))}]]))

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
     [toggle-row "Autopilot" "boat" :autopilot (:autopilot b)]
     [int-slider-row "Speed" "boat" :speed (:speed b) 0 3 speed-bands]
     [int-slider-row "Length" "boat" :length (:length b) 0 3 length-bands]]))

(defn conditions-controls []
  (let [p @passage-params]
    [:div.panel
     [:h2 "Conditions"]
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
                   (.toFixed (* 100.0 (:hi89 summ)) 1) "% 89% CI]")
              "— draw a route")]])))]))

(defn caveat-note
  "Muted methodological caveat; interpolates the live reference passage length."
  []
  (let [ref (:ref-nm @passage-params)]
    [:div#caveat.caveat
     (str "Risk is relative to a " ref " nm reference passage (not an "
          "independently calibrated absolute probability). The spatial term "
          "shows where incidents concentrate (Strait of Gibraltar, "
          "Galician/Portuguese coast) beyond depth/distance; near those "
          "hotspots risk saturates. The heatmap colour is a posterior-mean "
          "point estimate; the route numbers carry the full 89% credible "
          "interval.")]))

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
