(ns orca.planner-smoke
  "Headless-browser runner for the orca.planner.core math tests.

   Serves the repo root with the JDK SimpleFileServer (Scittle's fetch needs
   HTTP, not file://), loads route-planner/test/core_test.html in headless
   Chromium, waits for network idle, then asserts:
     - zero console error messages,
     - zero uncaught page errors, and
     - document.title == \"CORE TESTS PASS\".

   Run:  clojure -X:planner-smoke
   Browsers come from the shared Playwright cache; if missing, run once:
         clojure -X:planner-smoke orca.planner-smoke/install

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

(defn run
  "Run the planner-core headless tests. Returns a result map; throws on failure."
  [{:keys [dir port] :or {dir ".." port 8138}}]
  (let [url     (str "http://127.0.0.1:" port
                     "/route-planner/test/core_test.html")
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
          (let [title (.evaluate page "() => document.title")
                errs  (filterv #(= "error" (:type %)) @console)
                ok    (and (empty? errs) (empty? @perrors)
                           (= title "CORE TESTS PASS"))]
            (.close browser)
            (println "console messages :" (count @console))
            (doseq [m @console] (println "  [" (:type m) "]" (:text m)))
            (println "console errors   :" (count errs))
            (doseq [e errs] (println "  [error]" (:text e)))
            (println "page errors      :" (count @perrors))
            (doseq [e @perrors] (println "  " (first (str/split-lines e))))
            (println "failed requests  :" (count @failed))
            (doseq [f @failed] (println "  " f))
            (println "title            :" title)
            (println "RESULT:" (if ok "PASS" "FAIL"))
            (when-not ok
              (throw (ex-info "planner smoke test failed"
                              {:console-errors errs :page-errors @perrors
                               :title title})))
            {:pass? true :console-errors 0 :page-errors 0 :title title})))
      (finally
        (.stop server 0)))))
