#!/usr/bin/env bb
;; REPL client: pipe Clojure forms on stdin, eval them in the running socket
;; REPL on port 5577, print output up to a sentinel. Usage: echo '<forms>' | bb re.clj
(import '[java.net Socket] '[java.io BufferedReader InputStreamReader PrintWriter])
(require '[clojure.string :as str])
(let [code (slurp *in*)
      sock (Socket. "127.0.0.1" (Integer/parseInt (or (first *command-line-args*) "5577")))
      out  (PrintWriter. (.getOutputStream sock) true)
      in   (BufferedReader. (InputStreamReader. (.getInputStream sock)))]
  (.print out code)
  (.print out "\n")
  (.println out "(println \"<<<DONE>>>\")")
  (.flush out)
  (loop []
    (let [line (.readLine in)]
      (when line
        (println line)
        (when-not (str/includes? line "<<<DONE>>>")
          (recur)))))
  (.close sock))
