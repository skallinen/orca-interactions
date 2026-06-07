(ns orca.fetch
  "Fetch orca-interaction reports from the Cruising Association survey API.

   A port of the original Python downloader: pull the report list, then fetch
   every detailed incident / uneventful-passage report concurrently, flatten
   each response, and write the raw JSON plus tabular CSVs (via tablecloth).

   This is an API client, not a scraper — every record comes from a documented
   JSON endpoint (see :api in config.edn). The CA API is slow, so detail fetches
   run on a bounded thread pool and retry a few times before giving up.

   REPL entry point: (fetch-reports). Returns a summary map and writes files
   under (config/cfg :api :out-dir)."
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [orca.config :as config]
   [orca.util :as util]
   [tablecloth.api :as tc])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
   (java.time Duration)
   (java.util.concurrent Callable Executors)))

;; --- HTTP / API client -----------------------------------------------------

(def ^:private client
  "Lazily-built shared HttpClient (JDK java.net.http — no extra deps)."
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 30))
             .build)))

(defn api-get
  "GET `path` from the orca-survey API and parse the JSON body into Clojure data
   with keyword keys. Returns nil on any non-200 status or error."
  ([path] (api-get path (config/cfg :api :timeout-seconds)))
  ([path timeout-s]
   (try
     (let [url  (str (config/cfg :api :base-url) "/" path)
           req  (-> (HttpRequest/newBuilder (URI/create url))
                    (.header "caapi-clienttype" (config/cfg :api :client-type))
                    (.timeout (Duration/ofSeconds timeout-s))
                    .GET
                    .build)
           resp (.send @client req (HttpResponse$BodyHandlers/ofString))]
       (when (= 200 (.statusCode resp))
         (charred/read-json (.body resp) :key-fn keyword)))
     (catch Exception _ nil))))

;; --- Pure transforms -------------------------------------------------------

(defn flatten-response
  "Flatten a report's `response` map from {field {:Q .. :A ..}} to {field answer},
   trimming string answers. Values that aren't Q/A maps pass through unchanged."
  [response]
  (reduce-kv
    (fn [m k v]
      (assoc m k (if (and (map? v) (contains? v :A))
                   (let [a (:A v)] (if (string? a) (str/trim a) a))
                   v)))
    {} response))

(defn- summary-row
  "One report-list summary row {report_id type serial time lat long}."
  [report-type [rid s]]
  {:report_id (name rid) :type report-type :serial (:serial s "")
   :time (:time s "") :lat (:lat s "") :long (:long s "")})

(defn- ordered-columns
  "Column names for a CSV: those in `priority` that are present (in priority
   order), then any remaining columns alphabetically — mirrors save_csv."
  [ds priority]
  (let [present (set (tc/column-names ds))]
    (into (filterv present priority)
          (->> present (remove (set priority)) (sort-by name) vec))))

;; --- Concurrent detail fetch -----------------------------------------------

(defn fetch-report
  "Fetch one detailed report, retrying up to :retries times. On success returns
   the flattened response with report ids + summary fields merged in; nil on
   repeated failure. `task` is {:report-id :summary :report-type}."
  [{:keys [report-id summary report-type]}]
  (let [endpoint (if (= "incident" report-type) "incidentresponse" "uneventfulresponse")
        retries  (config/cfg :api :retries)]
    (loop [attempt 0]
      (let [detail (api-get (str endpoint "/" report-id))]
        (if (and detail (= "OK" (:status detail)) (:response detail))
          (-> (flatten-response (:response detail))
              (assoc :report_id report-id
                     :serial (:serial summary "")
                     :summary_lat (:lat summary "")
                     :summary_long (:long summary "")
                     :summary_time (:time summary "")
                     :report_type report-type))
          (when (< (inc attempt) retries)
            (recur (inc attempt))))))))

(defn- fetch-all
  "Fetch every task on a bounded thread pool, logging progress. Returns the
   results in task order (nils for reports that never succeeded)."
  [tasks]
  (let [n      (count tasks)
        pool   (Executors/newFixedThreadPool (config/cfg :api :max-workers))
        done   (atom 0)
        failed (atom 0)]
    (try
      (->> tasks
           (mapv (fn [task]
                   (.submit pool
                            ^Callable
                            (fn []
                              (let [r (fetch-report task)
                                    c (swap! done inc)]
                                (when (nil? r) (swap! failed inc))
                                (when (or (zero? (mod c 25)) (= c n))
                                  (println (format "  Progress: %d/%d (%d failed)" c n @failed)))
                                r)))))
           (mapv #(.get %)))
      (finally (.shutdown pool)))))

;; --- Output ----------------------------------------------------------------

(defn- save-csv!
  "Write `records` (seq of maps) to `path` as CSV with priority column ordering.
   No-op for empty input."
  [records path priority]
  (when (seq records)
    (let [ds (tc/dataset records)]
      (io/make-parents path)
      (tc/write-csv! (tc/select-columns ds (ordered-columns ds priority)) path))))

(defn- task-list
  "Build the [{:report-id :summary :report-type}] fetch tasks from a reports map."
  [reports]
  (into (mapv (fn [[rid s]] {:report-id (name rid) :summary s :report-type "incident"})
              (:incident reports))
        (mapv (fn [[rid s]] {:report-id (name rid) :summary s :report-type "uneventful"})
              (:uneventful reports))))

;; --- Orchestration ---------------------------------------------------------

(defn fetch-reports
  "Run the full download against the live API: report list -> all detailed
   reports (concurrent) -> raw JSON + CSV outputs under `out-dir`.
   Returns {:n-incidents :n-uneventful :failed :out-dir}."
  ([] (fetch-reports {}))
  ([{:keys [out-dir] :or {out-dir (config/cfg :api :out-dir)}}]
   (let [path (fn [f] (str out-dir "/" f))]
     (println "Fetching report list...")
     (if-let [data (api-get "reportlist?withdetails=true")]
       (let [reports    (:reports data)
             incidents  (:incident reports)
             uneventful (:uneventful reports)]
         (println (format "Found %d incident reports and %d uneventful passage reports"
                          (count incidents) (count uneventful)))

         ;; Raw report list + a quick summary CSV (coordinates + times).
         (util/write-json (path "reportlist.json") data {:pretty? true})
         (let [summaries (into (mapv (partial summary-row "incident") incidents)
                               (mapv (partial summary-row "uneventful") uneventful))]
           (save-csv! summaries (path "all_reports_summary.csv")
                      (config/cfg :api :summary-priority))
           (println (format "Saved %d report summaries to %s"
                            (count summaries) (path "all_reports_summary.csv"))))

         ;; Detailed reports, fetched concurrently.
         (let [tasks (task-list reports)]
           (println (format "\nDownloading %d detailed reports with %d concurrent workers..."
                            (count tasks) (config/cfg :api :max-workers)))
           (println "(This API is slow — expect ~5-10 minutes)")
           (let [details    (->> (fetch-all tasks) (remove nil?) vec)
                 by-type    (group-by :report_type details)
                 incident-d (get by-type "incident" [])
                 unevent-d  (get by-type "uneventful" [])
                 failed     (- (count tasks) (count details))]
             (util/write-json (path "all_reports_detailed.json") details)
             (println (format "\nSaved %d detailed reports to %s"
                              (count details) (path "all_reports_detailed.json")))
             (save-csv! incident-d (path "incident_reports.csv")
                        (config/cfg :api :incident-priority))
             (println (format "Saved %d incident reports to %s"
                              (count incident-d) (path "incident_reports.csv")))
             (save-csv! unevent-d (path "uneventful_reports.csv")
                        (config/cfg :api :uneventful-priority))
             (println (format "Saved %d uneventful reports to %s"
                              (count unevent-d) (path "uneventful_reports.csv")))
             (println (format "\nDone — incidents %d/%d, uneventful %d/%d, failed %d"
                              (count incident-d) (count incidents)
                              (count unevent-d) (count uneventful) failed))
             {:n-incidents (count incident-d)
              :n-uneventful (count unevent-d)
              :failed failed
              :out-dir out-dir})))
       (do (println "Failed to fetch report list!") nil)))))

(defn -main [& _] (fetch-reports) (shutdown-agents))
