(ns orca.heatmap-shot
  "Render the route-planner live-risk heatmap headlessly, screenshot two views
   (Galicia/Gibraltar hotspot + zoomed-out Iberian Atlantic), and analyse the
   risk-field GridLayer canvas pixels into a green/yellow/orange/red histogram.

   Run:  clojure -X:heatmap-shot :tag '\"before\"'
   Saves PNGs + prints histograms to route-planner/tmp_sim/."
  (:require
   [clojure.string :as str])
  (:import
   (com.microsoft.playwright BrowserType$LaunchOptions Page$NavigateOptions
                             Page$ScreenshotOptions)
   (com.microsoft.playwright.options WaitUntilState)
   (com.sun.net.httpserver SimpleFileServer SimpleFileServer$OutputLevel)
   (java.net InetSocketAddress)
   (java.nio.file Path Paths)
   (java.util.function Consumer)))

(defn- consumer ^Consumer [f] (reify Consumer (accept [_ x] (f x))))

(defn- start-server [dir port]
  (let [root (-> (Path/of dir (into-array String [])) .toAbsolutePath .normalize)
        srv  (SimpleFileServer/createFileServer
              (InetSocketAddress. "127.0.0.1" (int port))
              root SimpleFileServer$OutputLevel/NONE)]
    (.start srv) srv))

;; Classify the field GridLayer canvas pixels. The ramp is
;; green(45,198,83) -> yellow(255,209,102) -> red(230,57,70). We bucket each
;; painted (alpha>0) pixel by where it sits on that ramp using simple hue rules.
(def ^:private histo-js
  (str
   "() => {"
   " const cs = Array.from(document.querySelectorAll('.leaflet-pane canvas'))"
   "   .filter(c => c.tagName==='CANVAS' && c.width>0 && c.height>0);"
   " let painted=0, green=0, yellow=0, orange=0, red=0, other=0;"
   " for (const c of cs) {"
   "   let d; try { d = c.getContext('2d').getImageData(0,0,c.width,c.height).data; }"
   "   catch(e){ return {tainted:true}; }"
   "   for (let i=0;i<d.length;i+=4){"
   "     const r=d[i],g=d[i+1],b=d[i+2],a=d[i+3];"
   "     if (a<8) continue;"             ;; unpainted / fully transparent
   "     painted++;"
   ;; green: g high, r noticeably below g
   "     if (g>=160 && r < g-40) { green++; }"
   ;; red: r high, g low
   "     else if (r>=180 && g<120) { red++; }"
   ;; orange: r high, g mid
   "     else if (r>=200 && g>=120 && g<185) { orange++; }"
   ;; yellow: r high and g high (both bright)
   "     else if (r>=200 && g>=185) { yellow++; }"
   "     else { other++; }"
   "   }"
   " }"
   " return {painted, green, yellow, orange, red, other, ncanvas:cs.length};"
   "}"))

(defn- pct [n tot] (if (pos? tot) (format "%.1f%%" (* 100.0 (/ (double n) tot))) "0%"))

(defn- report-histo [label m]
  (let [tot (long (or (get m "painted") 0))]
    (println (str "  [" label "] painted=" tot
                  "  green=" (pct (get m "green") tot)
                  " yellow=" (pct (get m "yellow") tot)
                  " orange=" (pct (get m "orange") tot)
                  " red=" (pct (get m "red") tot)
                  " other=" (pct (get m "other") tot)
                  (when (get m "tainted") " TAINTED")))))

(defn- shot! [page out-path]
  (.screenshot page (-> (Page$ScreenshotOptions.)
                        (.setPath (Paths/get out-path (into-array String []))))))

(defn run
  [{:keys [dir port tag] :or {dir ".." port 8155 tag "before"}}]
  (let [url    (str "http://127.0.0.1:" port "/route-planner/index.html")
        outdir "../route-planner/tmp_sim"
        server (start-server dir port)]
    (try
      (with-open [pw (com.microsoft.playwright.Playwright/create)]
        (let [browser (.launch (.chromium pw)
                               (.setHeadless (BrowserType$LaunchOptions.) true))
              page    (.newPage browser
                                (-> (com.microsoft.playwright.Browser$NewPageOptions.)
                                    (.setViewportSize 1100 850)))]
          (.navigate page url (.setWaitUntil (Page$NavigateOptions.)
                                             WaitUntilState/NETWORKIDLE))
          (.waitForTimeout page 3500)
          ;; ensure the live-risk field layer is ON (it is by default, but be sure)
          (.evaluate page "() => { window.__planner.setRiskOpacity(1.0); }")
          ;; ---- View 1: Galicia/Gibraltar shelf hotspot (NW Iberia) ----
          (.evaluate page "() => window.__planner.setView(42.0, -9.0, 7)")
          (.waitForTimeout page 1800)
          (let [hotspot (.evaluate page histo-js)]
            (shot! page (str outdir "/heatmap_" tag "_hotspot.png"))
            (report-histo (str tag " hotspot z7 (42,-9)") hotspot))
          ;; ---- View 2: whole Iberian Atlantic, zoomed out ----
          (.evaluate page "() => window.__planner.setView(40.0, -10.0, 5)")
          (.waitForTimeout page 1800)
          (let [wide (.evaluate page histo-js)]
            (shot! page (str outdir "/heatmap_" tag "_wide.png"))
            (report-histo (str tag " wide z5 (40,-10)") wide))
          (.close browser)
          (println "RESULT: screenshots written to route-planner/tmp_sim/ tag=" tag)
          {:pass? true}))
      (finally (.stop server 0)))))
