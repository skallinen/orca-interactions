(ns orca.waic
  "WAIC model comparison from pointwise log-likelihood (porting.md §4.3, §6.3).

   Each ladder model emits `generated quantities { vector[N] log_lik; }`, so a
   pooled draws dataset carries S draws × N columns `log_lik.1`..`log_lik.N`.
   For each observation i:

     lppd_i   = log( (1/S) Σ_s exp(log_lik[s,i]) )   (the log pointwise density)
     p_waic_i = var_s( log_lik[s,i] )                (effective # parameters)
     elpd_i   = lppd_i - p_waic_i

   and elpd_waic = Σ_i elpd_i, p_waic = Σ_i p_waic_i, waic = -2·elpd_waic,
   se = sqrt(N · var_i(elpd_i)).

   We use WAIC, not ArviZ's PSIS-LOO (`az.compare(ic='loo')`), as an intentional
   substitution: no Pareto tail fit, simpler and lower-risk. WAIC and PSIS-LOO
   agree closely for well-behaved models, so the M3-vs-M4 ranking reproduces
   (porting.md §6.3). `compare` ranks models best-first and reports Δelpd
   (best − model, the ArviZ `elpd_diff` convention) with its standard error."
  (:refer-clojure :exclude [compare])
  (:require
   [tablecloth.api :as tc]))

;; ── primitive helpers ───────────────────────────────────────────────────────

(defn- logsumexp
  "log Σ exp(xᵢ), shifted by the max for numerical stability."
  ^double [xs]
  (let [a (double-array xs)
        m (areduce a i mx Double/NEGATIVE_INFINITY (Math/max mx (aget a i)))]
    (+ m (Math/log (areduce a i s 0.0 (+ s (Math/exp (- (aget a i) m))))))))

(defn- var1
  "Sample variance (ddof=1), matching ArviZ's pointwise WAIC variance."
  ^double [xs]
  (let [a (double-array xs)
        n (alength a)
        m (/ (areduce a i s 0.0 (+ s (aget a i))) n)]
    (/ (areduce a i s 0.0 (+ s (let [d (- (aget a i) m)] (* d d))))
       (dec n))))

;; ── pointwise log-lik extraction ────────────────────────────────────────────

(defn log-lik-cols
  "Extract the pointwise log_lik columns from a pooled draws dataset, ordered by
   observation index. Stan emits the vector as columns `log_lik.1`..`log_lik.N`;
   returns a vector of N columns, each the S draws for one observation."
  [draws]
  (let [prefix "log_lik."
        names  (->> (tc/column-names draws)
                    (filter #(re-matches #"log_lik\.\d+" (str %))))
        sorted (sort-by #(parse-long (subs (str %) (count prefix))) names)]
    (mapv #(vec (draws %)) sorted)))

;; ── WAIC ─────────────────────────────────────────────────────────────────────

(defn waic
  "WAIC from a seq of pointwise log-lik columns (each = the S draws for one
   observation). Returns {:elpd-waic :p-waic :waic :se :n :n-draws :pointwise},
   where :pointwise is the per-observation elpd (for `compare`'s Δ standard
   error)."
  [cols]
  (let [cols   (vec cols)
        n      (count cols)
        s      (count (first cols))
        log-s  (Math/log (double s))
        elpd-i (mapv (fn [c]
                       (let [lppd (- (logsumexp c) log-s)
                             pw   (var1 c)]
                         {:lppd lppd :p pw :elpd (- lppd pw)}))
                     cols)
        elpd   (reduce + (map :elpd elpd-i))
        p      (reduce + (map :p elpd-i))]
    {:elpd-waic elpd
     :p-waic    p
     :waic      (* -2.0 elpd)
     :se        (if (> n 1) (Math/sqrt (* n (var1 (map :elpd elpd-i)))) Double/NaN)
     :n         n
     :n-draws   s
     :pointwise (mapv :elpd elpd-i)}))

(defn waic-of
  "WAIC of a pooled draws dataset (extracts log_lik columns first)."
  [draws]
  (waic (log-lik-cols draws)))

;; ── comparison ───────────────────────────────────────────────────────────────

(defn compare
  "Compare models given `name->waic` (model name → `waic` result map). Returns a
   vector of rows sorted best-first (highest elpd_waic), each:
     {:name :elpd-waic :p-waic :waic :d-elpd :d-se :se}
   where :d-elpd is (best − model) elpd (0 for the best), :d-se its standard
   error (sqrt(N·var_i(Δ_i)), 0 for the best), and :se the model's own WAIC SE.
   Requires all models share the same N observations (same complete-case set)."
  [name->waic]
  (let [entries (map (fn [[nm w]] (assoc w :name nm)) name->waic)
        sorted  (reverse (sort-by :elpd-waic entries))
        best-pw (:pointwise (first sorted))]
    (mapv (fn [w]
            (let [d-pw (mapv - best-pw (:pointwise w))
                  d    (reduce + d-pw)
                  n    (:n w)]
              {:name      (:name w)
               :elpd-waic (:elpd-waic w)
               :p-waic    (:p-waic w)
               :waic      (:waic w)
               :se        (:se w)
               :d-elpd    d
               :d-se      (if (or (zero? d) (< n 2)) 0.0
                              (Math/sqrt (* n (var1 d-pw))))}))
          sorted)))
