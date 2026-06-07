(ns orca.util
  "Small IO helpers: JSON read/write and summary stats used across namespaces.

   The stats run through dtype-next (`tech.v3.datatype.functional`) on primitive
   buffers — the same typed-loop substrate tablecloth columns use — so summaries
   over thousands of posterior draws stay unboxed."
  (:require
   [charred.api :as charred]
   [clojure.java.io :as io]
   [tablecloth.api :as tc]
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

(defn raw-reports
  "Raw reports split into {:incident [...] :uneventful [...]} by report_type."
  [raw]
  (group-by #(keyword (:report_type %)) raw))

;; ── group-and-aggregate (interaction rate by a grouping column) ──────────────

(defn group-rates
  "Aggregate `data` by grouping column `group-col` into per-group incident rates.
   Returns a map {group-value {:n :incidents :uneventful :rate}} over the rows
   whose `group-col` is non-nil (nil-group rows are excluded, not pooled). `:rate`
   is incidents/n; groups with zero rows simply don't appear. The interaction
   flag column defaults to :interaction (1 = incident). Integral group values are
   normalized to `long` keys (so 0.0/0 collapse), since every grouping column
   here is integer-valued (ordinal levels, category indices, 0/1 binaries).

   This is the single primitive behind the per-level / per-category rate
   breakdowns; it replaces the hand-rolled parallel-vector group passes."
  ([data group-col] (group-rates data group-col :interaction))
  ([data group-col interaction-col]
   (->> (-> data
            (tc/drop-missing [group-col])
            (tc/group-by group-col {:result-type :as-map}))
        (map (fn [[gv g]]
               (let [n    (tc/row-count g)
                     ninc (-> g (tc/select-rows #(= 1 (% interaction-col)))
                              tc/row-count)
                     k    (if (and (number? gv) (== gv (Math/rint (double gv))))
                            (long gv) gv)]
                 [k {:n n :incidents ninc :uneventful (- n ninc)
                     :rate (/ (double ninc) n)}])))
        (into {}))))

(defn sigmoid
  "Logistic function 1/(1+e^-x)."
  ^double [^double x]
  (/ 1.0 (+ 1.0 (Math/exp (- x)))))

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
