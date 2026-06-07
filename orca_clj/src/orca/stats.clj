(ns orca.stats
  "Frequentist tests for the finding validations (orca.findings). Each follows
   the standard textbook definition and the widely-used `scipy.stats` defaults,
   so its console numbers match those conventions exactly:

   - `chi2-contingency` — Pearson χ² of independence with Yates' continuity
     correction for 2×2 tables (scipy default `correction=True`); p-value from
     the χ² distribution (Apache Commons Math).
   - `fisher-exact` — 2×2 sample odds ratio `(a·d)/(b·c)` plus a two-sided
     p-value summing hypergeometric probabilities of every table at least as
     unlikely as the observed one (scipy's two-sided method).
   - `t-test` — independent two-sample t-test with pooled variance, i.e.
     scipy's `ttest_ind` default (`equal_var=True`, Student's t), two-sided p.

   Frequencies/cross-tabs are small console helpers (value counts and a 2×2
   cross-tabulation)."
  (:require
   [orca.util :as util])
  (:import
   (org.apache.commons.math3.distribution ChiSquaredDistribution HypergeometricDistribution)
   (org.apache.commons.math3.stat.inference TTest)))

;; ── frequency helpers (pandas value_counts / crosstab) ──────────────────────

(defn value-counts
  "Map of distinct value → count over `xs` (pandas `value_counts(dropna=False)`).
   nil is kept as its own key."
  [xs]
  (frequencies xs))

(defn crosstab-2x2
  "2×2 contingency counts from two boolean collections of equal length, laid out
   like `pd.crosstab(row-pred, col-pred)` with rows/cols ordered false then true:

       [[ n(¬row ∧ ¬col)  n(¬row ∧ col) ]
        [ n( row ∧ ¬col)  n( row ∧ col) ]]

   Pairs where either value is nil are dropped (pandas drops NA from crosstab)."
  [row-bools col-bools]
  (let [pairs (->> (map vector row-bools col-bools)
                   (remove (fn [[r c]] (or (nil? r) (nil? c)))))
        cnt   (fn [rv cv]
                (count (filter (fn [[r c]] (and (= (boolean r) rv)
                                                (= (boolean c) cv)))
                               pairs)))]
    [[(cnt false false) (cnt false true)]
     [(cnt true false)  (cnt true true)]]))

(defn odds-ratio
  "Sample odds ratio of a 2×2 `[[a b] [c d]]`: `(a·d)/(b·c)`. ∞ when `b·c` = 0
   (matches scipy `fisher_exact`'s returned ratio)."
  [[[a b] [c d]]]
  (let [ad (* (double a) (double d))
        bc (* (double b) (double c))]
    (if (zero? bc) Double/POSITIVE_INFINITY (/ ad bc))))

;; ── χ² test of independence (scipy chi2_contingency) ────────────────────────

(defn- row-sums [m] (mapv #(reduce + %) m))
(defn- col-sums [m] (apply mapv + m))

(defn chi2-contingency
  "Pearson χ² test of independence on an r×c integer count matrix (vector of
   row vectors). Mirrors scipy `chi2_contingency`: Yates' continuity correction
   is applied when dof = 1 (any 2×2) unless `:correction? false`.

   Returns `{:chi2 :dof :p :expected}` (`:expected` is the r×c matrix of
   expected counts). p comes from the χ² survival function."
  ([m] (chi2-contingency m {}))
  ([m {:keys [correction?] :or {correction? true}}]
   (let [rs    (row-sums m)
         cs    (col-sums m)
         n     (double (reduce + rs))
         nr    (count m)
         nc    (count (first m))
         dof   (* (dec nr) (dec nc))
         exp   (mapv (fn [rt]
                       (mapv (fn [ct] (/ (* (double rt) (double ct)) n)) cs))
                     rs)
         yates (and correction? (= dof 1))
         chi2  (reduce
                 + (for [i (range nr) j (range nc)]
                     (let [o (double (get-in m [i j]))
                           e (double (get-in exp [i j]))
                           d (Math/abs (- o e))
                           ;; scipy nudges observed toward expected by min(.5,|d|)
                           d (if yates (- d (min 0.5 d)) d)]
                       (/ (* d d) e))))
         p     (if (zero? dof)
                 1.0
                 (- 1.0 (.cumulativeProbability
                          (ChiSquaredDistribution. (double dof)) (double chi2))))]
     {:chi2 chi2 :dof dof :p p :expected exp})))

;; ── Fisher's exact test, two-sided (scipy fisher_exact) ─────────────────────

(defn fisher-exact
  "Two-sided Fisher's exact test on a 2×2 `[[a b] [c d]]`. Returns
   `{:odds-ratio :p}`.

   The p-value fixes both margins and sums the hypergeometric probabilities of
   every table whose probability is ≤ that of the observed table (within
   scipy's 1+1e-7 tolerance) — scipy's exact two-sided algorithm. The odds
   ratio is the sample ratio `(a·d)/(b·c)`."
  [[[a b] [c d] :as table]]
  (let [a    (long a) b (long b) c (long c) d (long d)
        n    (+ a b c d)
        r1   (+ a b)               ; row-0 total  → sample size
        c1   (+ a c)               ; col-0 total  → successes in population
        dist (HypergeometricDistribution. (int n) (int c1) (int r1))
        lo   (max 0 (- (+ r1 c1) n))
        hi   (min r1 c1)
        p-obs (.probability dist (int a))
        tol  (* p-obs (+ 1.0 1.0e-7))
        p    (reduce
               + (for [k (range lo (inc hi))
                       :let [pk (.probability dist (int k))]
                       :when (<= pk tol)]
                   pk))]
    {:odds-ratio (odds-ratio table) :p (min 1.0 p)}))

;; ── independent two-sample t-test, pooled (scipy ttest_ind) ─────────────────

(defn t-test
  "Independent two-sample t-test of `xs` vs `ys`. Defaults to scipy
   `ttest_ind`'s `equal_var=True` (pooled-variance Student's t); pass
   `:equal-var? false` for Welch. Returns `{:t :p :df :mean-x :mean-y :diff}`
   with a two-sided p-value (Apache Commons Math `TTest`)."
  ([xs ys] (t-test xs ys {}))
  ([xs ys {:keys [equal-var?] :or {equal-var? true}}]
   (let [xa (double-array xs)
         ya (double-array ys)
         tt (TTest.)
         mx (util/mean xs)
         my (util/mean ys)
         t  (if equal-var?
              (.homoscedasticT tt xa ya)
              (.t tt xa ya))
         p  (if equal-var?
              (.homoscedasticTTest tt xa ya)
              (.tTest tt xa ya))
         nx (count xs)
         ny (count ys)
         df (if equal-var? (- (+ nx ny) 2) nil)]
     {:t t :p p :df df :mean-x mx :mean-y my :diff (- mx my)})))
