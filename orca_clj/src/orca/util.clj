(ns orca.util
  "Small IO helpers: JSON read/write and summary stats used across namespaces."
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]))

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

(defn mean [xs]
  (let [xs (seq xs)] (/ (double (reduce + 0.0 xs)) (count xs))))

(defn pstdev
  "Population standard deviation."
  [xs]
  (let [xs (vec xs) m (mean xs) n (count xs)]
    (Math/sqrt (/ (reduce (fn [a x] (+ a (Math/pow (- x m) 2))) 0.0 xs) n))))

(defn quantile
  "Linear-interpolated quantile q in [0,1] of a numeric collection."
  [xs q]
  (let [v (vec (sort xs))
        n (count v)
        idx (* q (dec n))
        lo (int (Math/floor idx))
        hi (int (Math/ceil idx))
        frac (- idx lo)]
    (+ (nth v lo) (* frac (- (nth v hi) (nth v lo))))))
