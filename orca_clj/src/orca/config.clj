(ns orca.config
  "Single source of analysis configuration, loaded once from config.edn.

   Holds the tunable *inputs* (paths, MCMC settings, the orca zone, ordinal
   encodings, validation tolerances) so a re-run only edits EDN, not source.
   Computed metadata and posterior draws stay JSON (see resources/config.edn).

   Resolution: classpath resource first (fresh JVMs), then resources/config.edn
   relative to the working dir (so a long-lived REPL started before the file
   existed still finds it)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def config
  (delay (-> (or (io/resource "config.edn")
                 (io/file "resources/config.edn"))
             slurp
             edn/read-string)))

(defn cfg
  "Look up a config value by key path, e.g. (cfg :mcmc :seed)."
  [& ks]
  (get-in @config ks))
