(ns orca.params
  "Shared accessors over a posterior-draws dataset and metadata categories.

   Centralizes three things that were copied (and subtly diverging) across
   orca.findings / orca.results / orca.sensitivity:

   - `cat-index`  — the metadata category → 0-based position lookup, ASSERTED
     present. A raw `.indexOf` returns -1 on a typo/absent category, which then
     reads a `family.0` column (corrupting a contrast) or NPEs downstream; the
     assert turns that into a loud, named failure.
   - `cat-col`    — the per-category posterior draw vector (Stan emits index
     families 1-based as `family.1`..`family.K`).
   - `contrast`   — the pairwise (a − b) posterior contrast summary every caller
     was recomputing by hand (mapv -, mean, ETI, P(a>b), odds)."
  (:require
   [orca.diagnostics :as diag]
   [orca.util :as util]))

(defn cat-index
  "0-based position of `cat-name` within metadata `cat-key`, asserted present.
   A -1 from `.indexOf` would silently corrupt a 2×2 / contrast (reading a
   `family.0` column) — fail loudly instead."
  [md cat-key cat-name]
  (let [i (.indexOf (vec (get-in md [:categories cat-key])) cat-name)]
    (assert (nat-int? i)
            (str "category " (pr-str cat-name) " not found in " cat-key))
    i))

(defn cat-col
  "Posterior draws of index family `family`'s 0-based category `i` (Stan emits
   1-based columns family.1..family.K)."
  [draws family i]
  (vec (draws (str family "." (inc i)))))

(defn cat-draws
  "Posterior draws for category `cat-name` of index family `family`
   (labelled by metadata `cat-key`)."
  [draws md family cat-key cat-name]
  (cat-col draws family (cat-index md cat-key cat-name)))

(defn contrast
  "Posterior contrast summary of two draw vectors `av` − `bv`:
   {:mean :lo :hi :odds :p-gt} where :p-gt = P(a > b) and :odds = exp(mean).
   The single primitive behind every pairwise category/effect contrast."
  [av bv]
  (let [d       (mapv - av bv)
        m       (util/mean d)
        [lo hi] (diag/eti d)]
    {:mean m :lo lo :hi hi :odds (Math/exp m)
     :p-gt (/ (double (count (filter pos? d))) (count d))}))

(defn category-contrast
  "Posterior contrast (category `a` − category `b`) within index `family`/`cat-key`,
   as `contrast`, with an extra :contrast label \"a vs b\"."
  [draws md family cat-key a b]
  (assoc (contrast (cat-draws draws md family cat-key a)
                   (cat-draws draws md family cat-key b))
         :contrast (str a " vs " b)))
