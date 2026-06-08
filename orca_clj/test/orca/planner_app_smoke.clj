(ns orca.planner-app-smoke
  "Headless-browser runner for the standalone route-planner app shell.

   Serves the repo root with the JDK SimpleFileServer (Scittle's fetch needs
   HTTP, not file://), loads route-planner/index.html in headless Chromium,
   waits for network idle plus a settle, then runs a vector of check fns:

     Check #1: zero console error messages AND zero uncaught page errors.
     Check #2: #status-loaded exists and its text contains \"Ready\".

   Later phases append Checks #3..#10 to the `checks` vector below.

   Run:  clojure -X:app-smoke
   Browsers come from the shared Playwright cache; if missing, run once:
         clojure -X:app-smoke orca.planner-app-smoke/install

   Not a unit test — the ns name doesn't end in -test."
  (:require
   [clojure.string :as str])
  (:import
   (com.microsoft.playwright BrowserType$LaunchOptions Page$NavigateOptions)
   (com.microsoft.playwright.options WaitUntilState)
   (com.sun.net.httpserver SimpleFileServer SimpleFileServer$OutputLevel)
   (java.net InetSocketAddress)
   (java.nio.file Path)
   (java.util.function Consumer)))

(defn- consumer ^Consumer [f]
  (reify Consumer (accept [_ x] (f x))))

(defn- start-server
  "Serve `dir` over HTTP on 127.0.0.1:`port`; returns the HttpServer."
  [dir port]
  (let [root (-> (Path/of dir (into-array String [])) .toAbsolutePath .normalize)
        srv  (SimpleFileServer/createFileServer
               (InetSocketAddress. "127.0.0.1" (int port))
               root SimpleFileServer$OutputLevel/NONE)]
    (.start srv)
    srv))

(defn install
  "One-time: download the Chromium browser for JVM Playwright (System.exits)."
  [_]
  (com.microsoft.playwright.CLI/main (into-array String ["install" "chromium"])))

;; ── Checks ───────────────────────────────────────────────────────────────────
;;
;; Each check is {:label … :check-fn (fn [ctx] -> {:pass? bool :detail str})}.
;; `ctx` carries the live page plus the collected console/page-error state.
;; Later phases append more checks to `checks`.

(defn- check-no-errors
  [{:keys [console perrors]}]
  (let [errs (filterv #(= "error" (:type %)) @console)]
    {:pass? (and (empty? errs) (empty? @perrors))
     :detail (str "console-errors=" (count errs)
                  " page-errors=" (count @perrors))}))

(defn- check-status-loaded
  [{:keys [page]}]
  (let [txt (.evaluate page
                       (str "() => { const e=document.querySelector('#status-loaded');"
                            " return e ? e.textContent : null; }"))]
    {:pass? (and (some? txt) (str/includes? (str txt) "Ready"))
     :detail (str "#status-loaded text=" (pr-str txt))}))

(defn- check-heat-canvas
  "A Leaflet.heat canvas exists and has non-empty pixel data. If reading the
   pixels is blocked (tainted canvas / CORS from tile images), fall back to
   asserting a heat canvas with width·height > 0 exists."
  [{:keys [page]}]
  (let [res (.evaluate
              page
              (str "() => {"
                   " const cs = Array.from(document.querySelectorAll("
                   "   '.leaflet-heatmap-layer, .leaflet-pane canvas'));"
                   " const heat = cs.filter(c => c.tagName === 'CANVAS'"
                   "   && c.width > 0 && c.height > 0);"
                   " if (heat.length === 0) return {found:false};"
                   " for (const c of heat) {"
                   "   try {"
                   "     const d = c.getContext('2d')"
                   "       .getImageData(0,0,c.width,c.height).data;"
                   "     if (Array.prototype.some.call(d, x => x !== 0))"
                   "       return {found:true, painted:true, n:heat.length};"
                   "   } catch (e) {"
                   "     return {found:true, painted:null, tainted:true,"
                   "             n:heat.length};"
                   "   }"
                   " }"
                   " return {found:true, painted:false, n:heat.length};"
                   "}"))
        found?   (.get ^java.util.Map res "found")
        painted  (.get ^java.util.Map res "painted")
        tainted  (.get ^java.util.Map res "tainted")
        n        (.get ^java.util.Map res "n")]
    {:pass? (boolean (and found? (or (true? painted) (true? tainted))))
     :detail (str "heat-canvases=" n
                  " painted=" (pr-str painted)
                  (when tainted " (pixel read tainted; size>0 substitution)"))}))

;; ── Phase 6 checks: route drawing, per-segment / whole-route risk, parity ─────
;;
;; All Phase-6 checks drive the app through the window.__planner test hooks,
;; which call the same code paths as the on-map click UX. Each mutation is
;; followed by a short settle so Reagent flushes the #id nodes the gates read.

(defn- p-eval
  "Evaluate `js` (a JS arrow-fn source string) on the page, returning its value."
  [page js]
  (.evaluate page js))

(defn- num-of [x]
  (cond
    (number? x) (double x)
    (nil? x) nil
    :else (try (Double/parseDouble (str x)) (catch Exception _ nil))))

(defn- read-id-number
  "parseFloat of the textContent of element #id (strips a trailing %)."
  [page id]
  (num-of
    (.evaluate page
               (str "() => { const e=document.getElementById('" id "');"
                    " if(!e) return null; const v=parseFloat(e.textContent);"
                    " return isNaN(v) ? null : v; }"))))

(defn- settle [page] (.waitForTimeout page 350))

;; ── I2.3 check: risk field still painted at low zoom ──────────────────────────
;;
;; The old Leaflet.heat layer (a point-density renderer with a screen-pixel blob
;; radius) faded out as you zoomed out. The new GridLayer rasterizes the field in
;; geographic space, so it must stay painted at low zoom. Zoom out via the
;; window.__planner.setZoom hook, wait for tiles, and re-read the heat canvases.

(defn- check-low-zoom-field
  "Zoom out to z=3 and assert the live-risk field canvas still has painted pixels
   (the GridLayer renders correctly at low zoom), with no new console/page errors."
  [{:keys [page console perrors]}]
  (let [err0  (count (filterv #(= "error" (:type %)) @console))
        perr0 (count @perrors)
        z     (num-of (p-eval page "() => window.__planner.setZoom(3)"))]
    ;; tiles redraw asynchronously after a zoom; give them time to paint.
    (.waitForTimeout page 1200)
    (let [{:keys [pass? detail]} (check-heat-canvas {:page page})
          no-new-errs? (and (= err0 (count (filterv #(= "error" (:type %))
                                                    @console)))
                            (= perr0 (count @perrors)))]
      ;; restore a usable zoom for any later checks
      (p-eval page "() => window.__planner.setZoom(5)")
      (.waitForTimeout page 600)
      {:pass? (boolean (and pass? no-new-errs?))
       :detail (str "zoom=" z " field-" detail
                    " new-errors=" (not no-new-errs?))})))

(defn- check-add-waypoint
  [{:keys [page]}]
  (p-eval page "() => window.__planner.clearRoute()")
  (settle page)
  (let [c0 (num-of (p-eval page "() => window.__planner.waypointCount()"))]
    (p-eval page "() => window.__planner.addWaypoint(36.1, -5.7)")
    (settle page)
    (let [dom (read-id-number page "waypoint-count")
          c1  (num-of (p-eval page "() => window.__planner.waypointCount()"))]
      {:pass? (and (= 0.0 c0) (= 1.0 c1) (= 1.0 dom))
       :detail (str "count0=" c0 " count1=" c1 " #waypoint-count=" dom)})))

(defn- check-segment-risk
  [{:keys [page]}]
  ;; add a 2nd waypoint so there is at least one sub-segment.
  (p-eval page "() => window.__planner.addWaypoint(36.6, -6.4)")
  (settle page)
  (let [present? (p-eval page
                         (str "() => { const e=document.getElementById('segment-risk-0');"
                              " return !!e && e.textContent.trim().length>0; }"))
        v (read-id-number page "segment-risk-0")]
    {:pass? (and (true? present?) (some? v))
     :detail (str "#segment-risk-0 present=" present? " value=" v)}))

;; Moderate-risk water (empirically chosen, see report): a ~60 nm offshore leg
;; on the Iberian shelf approaches whose default-Motoring median sits ≈0.54.
(def ^:private mod-wp1 [38.0 -11.0])
(def ^:private mod-wp2 [38.0 -12.0])

(defn- build-mod-route!
  "Clear and lay the two moderate-risk waypoints, default (Motoring) params."
  [page]
  (p-eval page "() => window.__planner.clearRoute()")
  (settle page)
  (p-eval page "() => window.__planner.setParam('boat','sailing','Motoring')")
  (p-eval page (str "() => window.__planner.addWaypoint(" (first mod-wp1)
                    ", " (second mod-wp1) ")"))
  (p-eval page (str "() => window.__planner.addWaypoint(" (first mod-wp2)
                    ", " (second mod-wp2) ")"))
  (settle page))

(defn- check-ci-ordering
  [{:keys [page]}]
  (build-mod-route! page)
  (let [lo  (read-id-number page "ci-lo")
        med (read-id-number page "risk-median")
        hi  (read-id-number page "ci-hi")]
    {:pass? (boolean (and lo med hi (< lo med) (< med hi)))
     :detail (str "lo89=" lo " median=" med " hi89=" hi)}))

(defn- check-motor-gt-sail
  "Mode change moves risk in the FITTED direction. In the rebuilt model the
   sailing=Motoring beta is positive (mean ≈ +0.85; Sailing is the reference
   level at 0), so Motoring carries higher risk than Sailing. We assert that
   fitted direction (Motoring > Sailing) rather than any a-priori assumption."
  [{:keys [page]}]
  (build-mod-route! page)
  (p-eval page "() => window.__planner.setParam('boat','sailing','Motoring')")
  (settle page)
  (let [m (num-of (p-eval page "() => window.__planner.routeMedian()"))]
    (p-eval page "() => window.__planner.setParam('boat','sailing','Sailing')")
    (settle page)
    (let [s (num-of (p-eval page "() => window.__planner.routeMedian()"))]
      ;; restore default for any later checks
      (p-eval page "() => window.__planner.setParam('boat','sailing','Motoring')")
      (settle page)
      {:pass? (boolean (and m s (> m s)))
       :detail (str "Motoring=" m " Sailing=" s
                    " (fitted sailing=Motoring beta > 0 ⇒ Motoring > Sailing)")})))

;; ── Phase 7 check: predictor change re-tints heatmap + shifts route risk ──────

(def ^:private heat-canvas-sig-js
  "JS arrow-fn source: returns a numeric signature (sum of pixel byte values)
   of the live-risk heat canvas — the same Leaflet.heat canvas Check #3 reads.
   Returns {sig, tainted} so the check can fall back if pixel reads are blocked."
  (str
    "() => {"
    " const cs = Array.from(document.querySelectorAll("
    "   '.leaflet-heatmap-layer, .leaflet-pane canvas'))"
    "   .filter(c => c.tagName === 'CANVAS' && c.width > 0 && c.height > 0);"
    " if (cs.length === 0) return {sig:0, tainted:false, found:false};"
    " try {"
    "   let sig = 0;"
    "   for (const c of cs) {"
    "     const d = c.getContext('2d').getImageData(0,0,c.width,c.height).data;"
    "     for (let i = 0; i < d.length; i += 4) {"
    "       sig = (sig + d[i] + d[i+1] + d[i+2] + d[i+3]) >>> 0;"
    "     }"
    "   }"
    "   return {sig:sig, tainted:false, found:true};"
    " } catch (e) {"
    "   return {sig:0, tainted:true, found:true};"
    " }"
    "}"))

(defn- heat-sig
  "Read the live-risk heat-canvas signature; returns {:sig long :tainted bool}."
  [page]
  (let [res (.evaluate page heat-canvas-sig-js)]
    {:sig (long (num-of (.get ^java.util.Map res "sig")))
     :tainted (boolean (.get ^java.util.Map res "tainted"))}))

(defn- check-predictor-retint
  "Black → Coppercoat: total-risk median drops AND the heat canvas re-tints."
  [{:keys [page console perrors]}]
  (let [err0 (count (filterv #(= "error" (:type %)) @console))
        perr0 (count @perrors)]
    (build-mod-route! page)
    ;; Black (higher-risk antifoul)
    (p-eval page "() => window.__planner.setParam('boat','antifoul','Black')")
    (settle page)
    (let [black-risk (read-id-number page "route-total-risk")
          black-sig (heat-sig page)]
      ;; Coppercoat (lower-risk antifoul)
      (p-eval page "() => window.__planner.setParam('boat','antifoul','Coppercoat')")
      (settle page)
      (let [copper-risk (read-id-number page "route-total-risk")
            copper-sig (heat-sig page)
            tainted? (or (:tainted black-sig) (:tainted copper-sig))
            sig-changed? (not= (:sig black-sig) (:sig copper-sig))
            risk-changed? (boolean (and black-risk copper-risk
                                        (< copper-risk black-risk)))
            no-new-errs? (and (= err0 (count (filterv #(= "error" (:type %))
                                                      @console)))
                              (= perr0 (count @perrors)))
            ;; restore default antifoul for any later checks
            _ (p-eval page "() => window.__planner.setParam('boat','antifoul','Black')")
            _ (settle page)]
        {:pass? (boolean (and risk-changed? no-new-errs?
                              (or sig-changed? tainted?)))
         :detail (str "Black=" black-risk "% Coppercoat=" copper-risk "%"
                      " risk-dropped=" risk-changed?
                      " canvas-changed=" sig-changed?
                      (when tainted?
                        " (canvas pixel read tainted; route-total change alone)")
                      " new-errors=" (not no-new-errs?))}))))

;; ── Phase 8 check: adding a second route via the real #add-route-btn ──────────

(defn- count-route-tabs
  "Number of route tabs currently rendered (the .route-tab elements)."
  [page]
  (long
    (num-of
      (p-eval page
              "() => document.querySelectorAll('.route-tab').length"))))

(defn- check-add-route
  "Click #add-route-btn and assert the route-tab count grows to >=2 with no new
   console/page errors."
  [{:keys [page console perrors]}]
  (let [err0  (count (filterv #(= "error" (:type %)) @console))
        perr0 (count @perrors)
        tabs0 (count-route-tabs page)
        n0    (long (num-of (p-eval page "() => window.__planner.routeCount()")))]
    (.click page "#add-route-btn")
    (settle page)
    (let [tabs1 (count-route-tabs page)
          n1    (long (num-of (p-eval page "() => window.__planner.routeCount()")))
          no-new-errs? (and (= err0 (count (filterv #(= "error" (:type %))
                                                    @console)))
                            (= perr0 (count @perrors)))]
      (println "route count: before=" n0 " after=" n1
               " (tabs " tabs0 "->" tabs1 ")")
      {:pass? (boolean (and (>= tabs1 2) (> tabs1 tabs0)
                            (>= n1 2) (> n1 n0) no-new-errs?))
       :detail (str "tabs " tabs0 "->" tabs1 " routeCount " n0 "->" n1
                    " new-errors=" (not no-new-errs?))})))

;; ── I2.2 check: waypoint delete-then-readd + drag moves risk ──────────────────
;;
;; Drives the editing paths through the window.__planner hooks (the same code the
;; marker click/dragend handlers call): deleteWaypoint goes through the marker
;; delete path; moveWaypoint goes through the dragend path.

(defn- check-edit-delete-move
  "delete-then-readd: lay two waypoints, delete one (count drops), re-add (count
   recovers); move-changes-risk: build a route, read its median, move a waypoint
   into clearly different water and assert the median changes. No console errors."
  [{:keys [page console perrors]}]
  (let [err0  (count (filterv #(= "error" (:type %)) @console))
        perr0 (count @perrors)]
    ;; -- delete-then-readd --
    (p-eval page "() => window.__planner.clearRoute()")
    (settle page)
    (p-eval page "() => window.__planner.addWaypoint(38.0, -11.0)")
    (p-eval page "() => window.__planner.addWaypoint(38.0, -12.0)")
    (settle page)
    (let [c2  (num-of (p-eval page "() => window.__planner.waypointCount()"))
          dom2 (read-id-number page "waypoint-count")]
      (p-eval page "() => window.__planner.deleteWaypoint(1)")
      (settle page)
      (let [c1  (num-of (p-eval page "() => window.__planner.waypointCount()"))]
        (p-eval page "() => window.__planner.addWaypoint(38.0, -12.0)")
        (settle page)
        (let [c2b (num-of (p-eval page "() => window.__planner.waypointCount()"))
              ;; -- move-changes-risk: from moderate water toward a hotspot --
              med0 (num-of (p-eval page "() => window.__planner.routeMedian()"))
              _    (p-eval page "() => window.__planner.moveWaypoint(1, 36.0, -5.5)")
              _    (settle page)
              med1 (num-of (p-eval page "() => window.__planner.routeMedian()"))
              no-new-errs? (and (= err0 (count (filterv #(= "error" (:type %))
                                                        @console)))
                                (= perr0 (count @perrors)))
              del-readd-ok? (and (= 2.0 c2) (= 2.0 dom2) (= 1.0 c1) (= 2.0 c2b))
              move-ok? (boolean (and med0 med1 (not= med0 med1)))]
          ;; restore a clean moderate route for any later checks
          (build-mod-route! page)
          {:pass? (boolean (and del-readd-ok? move-ok? no-new-errs?))
           :detail (str "count 2->" c1 "->" c2b " (#waypoint-count=" dom2 ")"
                        " median " med0 "->" med1 " moved=" move-ok?
                        " new-errors=" (not no-new-errs?))})))))

;; ── #10 PRIMARY SANITY GATE: W-Portugal → Gibraltar realistic passage ─────────
;;
;; The whole motivation for the model rework (I2.4): this passage used to return
;; 100% with a degenerate [100,100] CI. The rebuilt presence-effort-seasonal model
;; must return an order-of-tens-of-percent median with a non-degenerate 89% CI.

;; W-Portugal → Strait of Gibraltar waypoints, driven via the test hooks.
(def ^:private wpg-route
  [[38.70 -9.42] [37.10 -8.67] [36.00 -6.00] [36.14 -5.35]])

(defn- lay-route!
  "Clear the active route, set doy + the reference/average vessel, then add `wps`
   waypoints. We use the model's reference vessel (Sailing, average ordinals) so
   the sanity band matches the plan's 'average vessel' criterion; Motoring is a
   riskier non-reference choice exercised separately by check #8."
  [page wps doy]
  (p-eval page "() => window.__planner.clearRoute()")
  (settle page)
  (doseq [[g k v] [["boat" "sailing" "Sailing"] ["boat" "antifoul" "Black"]
                   ["boat" "hull" "White/light"] ["boat" "rudder" "Spade"]
                   ["boat" "speed" 2] ["boat" "length" 1]
                   ["passage" "wind" 1] ["passage" "sea" 0]
                   ["passage" "daylight" "Average"]]]
    (p-eval page (str "() => window.__planner.setParam('" g "','" k "',"
                      (if (string? v) (str "'" v "'") v) ")")))
  (p-eval page (str "() => window.__planner.setDoy(" doy ")"))
  (settle page)
  (doseq [[la lo] wps]
    (p-eval page (str "() => window.__planner.addWaypoint(" la ", " lo ")")))
  (settle page))

(defn- check-sanity-route
  "Primary sanity gate: the W-Portugal→Gibraltar route at summer doy=232 with the
   reference/average vessel returns a median in (0.03, 0.35) and a non-degenerate
   89% CI (hi - lo > 0.01), with no new console/page errors. The legacy build
   returned 100% [100,100]; this asserts the rework brought it back to a sane band."
  [{:keys [page console perrors]}]
  (let [err0 (count (filterv #(= "error" (:type %)) @console))
        perr0 (count @perrors)]
    (lay-route! page wpg-route 232)
    (let [med (read-id-number page "risk-median")
          lo  (read-id-number page "ci-lo")
          hi  (read-id-number page "ci-hi")
          ;; #id text is a percentage; convert to fraction.
          medf (when med (/ med 100.0))
          lof  (when lo (/ lo 100.0))
          hif  (when hi (/ hi 100.0))
          ci-width (when (and lof hif) (- hif lof))
          no-new-errs? (and (= err0 (count (filterv #(= "error" (:type %))
                                                    @console)))
                            (= perr0 (count @perrors)))]
      {:pass? (boolean (and medf (> medf 0.03) (< medf 0.35)
                            ci-width (> ci-width 0.01) no-new-errs?))
       :detail (str "median=" med "% CI=[" lo "," hi "]%"
                    " ci-width=" (when ci-width (format "%.3f" ci-width))
                    " new-errors=" (not no-new-errs?))})))

(defn- check-open-atlantic
  "Short open-Atlantic leg (40.0,-15.0)→(40.5,-15.0) at summer doy=232 ⇒ route
   median < 0.01 (quiet open ocean, far from any hotspot)."
  [{:keys [page]}]
  (lay-route! page [[40.0 -15.0] [40.5 -15.0]] 232)
  (let [med (read-id-number page "risk-median")
        medf (when med (/ med 100.0))]
    {:pass? (boolean (and medf (< medf 0.01)))
     :detail (str "median=" med "% (fraction " medf ")")}))

(defn- check-season-shift
  "Seasonal check: the W-Portugal→Gibraltar route median in winter (doy=50)
   DIFFERS meaningfully from summer (doy=232) — the hotspot moves with the pod's
   north-south cycle. Assert |winter - summer| > 0.05 (5 percentage points), with
   no new console/page errors."
  [{:keys [page console perrors]}]
  (let [err0 (count (filterv #(= "error" (:type %)) @console))
        perr0 (count @perrors)]
    (lay-route! page wpg-route 232)
    (let [summer (read-id-number page "risk-median")]
      (lay-route! page wpg-route 50)
      (let [winter (read-id-number page "risk-median")
            diff (when (and summer winter)
                   (Math/abs (- (/ winter 100.0) (/ summer 100.0))))
            no-new-errs? (and (= err0 (count (filterv #(= "error" (:type %))
                                                      @console)))
                              (= perr0 (count @perrors)))]
        {:pass? (boolean (and diff (> diff 0.05) no-new-errs?))
         :detail (str "summer=" summer "% winter=" winter "%"
                      " diff=" (when diff (format "%.3f" diff))
                      " new-errors=" (not no-new-errs?))}))))

(defn- check-incident-window
  "The historical-incident layers are windowed to the selected month +/-30 days.
   August (doy=227, the incident peak) must show more incidents than February
   (doy=46, a trough); both must be > 0 and < the full 216, with no new errors."
  [{:keys [page console perrors]}]
  (let [err0 (count (filterv #(= "error" (:type %)) @console))
        perr0 (count @perrors)
        aug (do (p-eval page "() => window.__planner.setDoy(227)")
                (settle page)
                (num-of (p-eval page "() => window.__planner.incidentCount()")))
        feb (do (p-eval page "() => window.__planner.setDoy(46)")
                (settle page)
                (num-of (p-eval page "() => window.__planner.incidentCount()")))
        no-new-errs? (and (= err0 (count (filterv #(= "error" (:type %)) @console)))
                          (= perr0 (count @perrors)))]
    ;; restore the default month so later checks start from a known season.
    (p-eval page "() => window.__planner.setDoy(232)")
    (settle page)
    {:pass? (boolean (and aug feb (> aug feb) (> feb 0) (< aug 216) no-new-errs?))
     :detail (str "aug(doy227)=" aug " feb(doy46)=" feb
                  " new-errors=" (not no-new-errs?))}))

(def checks
  [{:label "no console/page errors" :check-fn check-no-errors}
   {:label "#status-loaded contains Ready" :check-fn check-status-loaded}
   {:label "live-risk heat canvas non-empty" :check-fn check-heat-canvas}
   {:label "#4 predictor change re-tints + drops risk" :check-fn check-predictor-retint}
   {:label "#5 add waypoint increments count" :check-fn check-add-waypoint}
   {:label "#6 segment risk appears" :check-fn check-segment-risk}
   {:label "#7 CI ordering lo<med<hi" :check-fn check-ci-ordering}
   {:label "#8 Motoring > Sailing (fitted direction)" :check-fn check-motor-gt-sail}
   {:label "#9 add second route via #add-route-btn" :check-fn check-add-route}
   {:label "#10 W-Portugal→Gibraltar sanity (median in band, non-degenerate CI)"
    :check-fn check-sanity-route}
   {:label "#11 open-Atlantic route median < 1%" :check-fn check-open-atlantic}
   {:label "#12 seasonal hotspot moves (winter ≠ summer)"
    :check-fn check-season-shift}
   {:label "#13 incident layers window by month (Aug > Feb)"
    :check-fn check-incident-window}
   {:label "I2.2 delete + move (delete-readd + drag changes risk)"
    :check-fn check-edit-delete-move}
   {:label "I2.3 low-zoom field painted" :check-fn check-low-zoom-field}])

(defn run
  "Run the route-planner app shell headless checks. Returns a result map;
   throws on failure."
  [{:keys [dir port] :or {dir ".." port 8139}}]
  (let [url     (str "http://127.0.0.1:" port "/route-planner/index.html")
        server  (start-server dir port)
        console (atom [])
        perrors (atom [])
        failed  (atom [])]
    (try
      (with-open [pw (com.microsoft.playwright.Playwright/create)]
        (let [browser (.launch (.chromium pw)
                               (.setHeadless (BrowserType$LaunchOptions.) true))
              page    (.newPage browser)]
          (.onConsoleMessage page (consumer (fn [m] (swap! console conj
                                                           {:type (.type m)
                                                            :text (.text m)}))))
          (.onPageError page (consumer (fn [e] (swap! perrors conj (str e)))))
          (.onRequestFailed page (consumer (fn [r] (swap! failed conj
                                                          (str (.method r) " " (.url r))))))
          (.navigate page url (.setWaitUntil (Page$NavigateOptions.)
                                             WaitUntilState/NETWORKIDLE))
          (.waitForTimeout page 3000)
          (let [ctx     {:page page :console console :perrors perrors}
                results (mapv (fn [{:keys [label check-fn]}]
                                (assoc (check-fn ctx) :label label))
                              checks)
                ok      (every? :pass? results)]
            (.close browser)
            (println "console messages :" (count @console))
            (doseq [m @console] (println "  [" (:type m) "]" (:text m)))
            (println "console errors   :"
                     (count (filterv #(= "error" (:type %)) @console)))
            (doseq [e (filterv #(= "error" (:type %)) @console)]
              (println "  [error]" (:text e)))
            (println "page errors      :" (count @perrors))
            (doseq [e @perrors] (println "  " (first (str/split-lines e))))
            (println "failed requests  :" (count @failed))
            (doseq [f @failed] (println "  " f))
            (println "checks           :")
            (doseq [{:keys [label pass? detail]} results]
              (println "  " (if pass? "[PASS]" "[FAIL]") label "|" detail))
            (println "RESULT:" (if ok "PASS" "FAIL"))
            (when-not ok
              (throw (ex-info "planner app smoke test failed"
                              {:results results
                               :console-errors
                               (filterv #(= "error" (:type %)) @console)
                               :page-errors @perrors})))
            {:pass? true :results results})))
      (finally
        (.stop server 0)))))
