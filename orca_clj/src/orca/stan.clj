(ns orca.stan
  "Thin wrapper around CmdStan: compile a .stan model and run NUTS sampling,
   then read the draws back as a tablecloth dataset.

   Requires `make`/`clang`/`gnumake` and the cmdstan tree on PATH (provided by
   the nix-shell the REPL runs inside). CMDSTAN points at a *writable* copy of
   the nix cmdstan tree (the store copy is read-only and its prebuilt
   precompiled header is compiler-mismatched on Darwin)."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [orca.util :as util]
   [tablecloth.api :as tc]))

(def cmdstan-dir
  (or (System/getenv "CMDSTAN")
      (str (System/getProperty "user.home") "/.cache/orca-cmdstan")))

(defn- check! [{:keys [exit out err] :as res} what]
  (when-not (zero? exit)
    (throw (ex-info (str what " failed (exit " exit ")")
                    {:out out :err err})))
  res)

(defn compile-model
  "Compile `stan-path` (a .stan file) into an executable next to it via
   `make -C $CMDSTAN <model>` (model = abs path without extension). Returns the
   executable path."
  [stan-path]
  (let [model (str/replace (.getAbsolutePath (io/file stan-path)) #"\.stan$" "")]
    (check! (sh/sh "make" "-C" cmdstan-dir model) (str "compile " stan-path))
    model))

(defn read-draws-csv
  "Read a CmdStan output CSV (comment lines start with #) into a dataset."
  [csv-path]
  (let [body (->> (str/split-lines (slurp csv-path))
                  (remove #(str/starts-with? % "#"))
                  (str/join "\n"))]
    (tc/dataset (java.io.StringReader. body) {:file-type :csv})))

(defn run-sampling
  "Run one NUTS chain of `model` executable with `data-json` (Stan JSON data),
   writing draws to `out-csv`."
  [model {:keys [data-json out-csv chain-id seed num-warmup num-samples]
          :or   {seed 42 num-warmup 1000 num-samples 2000 chain-id 1}}]
  (check!
    (sh/sh model "sample"
           (str "num_warmup=" num-warmup)
           (str "num_samples=" num-samples)
           "data" (str "file=" data-json)
           "output" (str "file=" out-csv)
           "random" (str "seed=" seed)
           (str "id=" chain-id))
    (str "sample " model " chain " chain-id))
  out-csv)

(defn sample
  "Compile and sample `n-chains` chains of `stan-path` against `data-map`.
   Returns a tablecloth dataset of pooled posterior draws (one row per draw,
   columns = Stan output names). Chains share `seed` with id=1..n (CmdStan
   derives an independent RNG stream per id)."
  [stan-path data-map
   {:keys [n-chains seed num-warmup num-samples out-dir]
    :or   {n-chains 4 seed 42 num-warmup 1000 num-samples 2000 out-dir "out"}}]
  (let [model     (compile-model stan-path)
        data-json (str out-dir "/data.json")]
    (io/make-parents data-json)
    (util/write-json data-json data-map)
    (->> (for [c (range 1 (inc n-chains))]
           (let [csv (str out-dir "/draws_" c ".csv")]
             (run-sampling model {:data-json data-json :out-csv csv
                                  :chain-id c :seed seed
                                  :num-warmup num-warmup
                                  :num-samples num-samples})
             (read-draws-csv csv)))
         (apply tc/concat))))
