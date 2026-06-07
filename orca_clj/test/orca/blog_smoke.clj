(ns orca.blog-smoke
  "Headless-browser smoke test for the Scittle blog calculator, using JVM
   Playwright (the Clojure port of the earlier test.mjs/test2.mjs).

   It serves blogpost/ with the JDK SimpleFileServer (Scittle's fetch needs HTTP,
   not file://), loads the page in headless Chromium, and asserts:
     - no console errors and no uncaught page errors (i.e. no Scittle errors),
     - the calculator renders, and
     - the risk multiplier reacts to changing an input (proves predict works
       with the JSON-derived index maps).

   Run:  clojure -X:smoke
   Browsers come from the shared Playwright cache; if missing, run once:
         clojure -X:smoke orca.blog-smoke/install

   Not a unit test — the ns name doesn't end in -test, so `clojure -X:test`
   ignores it (and never needs the Playwright dep)."
  (:require
   [clojure.string :as str])
  (:import
   (com.microsoft.playwright BrowserType$LaunchOptions Locator$WaitForOptions Page$NavigateOptions)
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

;; JS read of the big risk-multiplier div (the only one at font-size 2.8em).
(def ^:private mult-js
  "() => { const e=[...document.querySelectorAll('div')]
             .find(d => d.style && d.style.fontSize==='2.8em');
           return e ? e.textContent.trim() : null; }")

(defn install
  "One-time: download the Chromium browser for JVM Playwright (System.exits)."
  [_]
  (com.microsoft.playwright.CLI/main (into-array String ["install" "chromium"])))

(defn run
  "Run the blog smoke test. Returns a result map; throws if it fails."
  [{:keys [dir port] :or {dir "../blogpost" port 8137}}]
  (let [url     (str "http://127.0.0.1:" port "/index.html")
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
          (.waitForTimeout page 2500)
          ;; calculator mounted once the fetch().then has rendered it
          (-> page (.getByText "Risk Calculator") .first
              (.waitFor (.setTimeout (Locator$WaitForOptions.) 8000)))
          (let [before (.evaluate page mult-js)
                _      (-> page (.locator "select") .first (.selectOption "Motoring"))
                _      (.waitForTimeout page 600)
                sail   (.evaluate page "() => document.querySelector('select').value")
                after  (.evaluate page mult-js)
                errs   (filterv #(= "error" (:type %)) @console)
                ok     (and (empty? errs) (empty? @perrors)
                            (some? before) (some? after) (not= before after))]
            (.close browser)
            (println "console messages :" (count @console))
            (println "console errors   :" (count errs))
            (doseq [e errs] (println "  [error]" (:text e)))
            (println "page errors      :" (count @perrors))
            (doseq [e @perrors] (println "  " (first (str/split-lines e))))
            (println "failed requests  :" (count @failed))
            (doseq [f @failed] (println "  " f))
            (println "multiplier before        :" before)
            (println "sailing value after change:" sail)
            (println "multiplier after Motoring :" after)
            (println "RESULT:" (if ok
                                 "PASS — no scittle/console errors, calculator reactive"
                                 "FAIL"))
            (when-not ok
              (throw (ex-info "blog smoke test failed"
                              {:console-errors errs :page-errors @perrors
                               :before before :after after})))
            {:pass? true :console-errors 0 :page-errors 0
             :before before :after after})))
      (finally
        (.stop server 0)))))
