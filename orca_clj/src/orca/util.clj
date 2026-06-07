(ns orca.util
  "Small IO helpers: JSON read/write and summary stats used across namespaces.

   The stats run through dtype-next (`tech.v3.datatype.functional`) on primitive
   buffers — the same typed-loop substrate tablecloth columns use — so summaries
   over thousands of posterior draws stay unboxed."
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]
   [tech.v3.datatype :as dtype]
   [tech.v3.datatype.functional :as dfn]))

(defn read-json
  "Parse a JSON file into Clojure data with keyword keys."
  [path]
  (charred/read-json (io/reader path) :key-fn keyword))

(defn write-json
  "Write `data` to `path` as JSON. `:pretty?` for indentation."
  ([path data] (write-json path data {}))
  ([path data {:keys [pretty?]}]
   (io/make-parents path)
   (with-open [w (io/writer path)]
     (charred/write-json w data {:indent-str (when pretty? "  ")}))))

(defn mean
  "Arithmetic mean (dtype-next typed reduction)."
  [xs]
  (-> xs dtype/->double-array dfn/mean))

(defn pstdev
  "Population standard deviation (ddof=0)."
  [xs]
  (let [ds (dtype/->double-array xs)
        m  (dfn/mean ds)]
    (-> ds (dfn/- m) dfn/sq dfn/mean Math/sqrt)))

(defn quantile
  "Linear-interpolated quantile q in [0,1] of a numeric collection (matches the
   numpy default), computed on a primitive double[] for an unboxed sort."
  [xs q]
  (let [v    (dtype/->double-array xs)
        _    (java.util.Arrays/sort v)
        idx  (* (double q) (dec (alength v)))
        lo   (long (Math/floor idx))
        hi   (long (Math/ceil idx))
        frac (- idx lo)]
    (+ (aget v lo) (* frac (- (aget v hi) (aget v lo))))))
