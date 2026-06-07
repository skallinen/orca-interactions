(ns orca.diagnostics
  "MCMC convergence diagnostics and interval summaries. These follow the
   ArviZ/Stan definitions (Vehtari et al. 2021), so the reported numbers match
   those conventions to ~2 significant figures.

   - `rhat`     — rank-normalized, folded split-R̂ (Vehtari et al. 2021; the
                  ArviZ default): max of the rank-normalized split-R̂ of the
                  draws and of the draws folded around their median.
   - `ess-bulk` — effective sample size of the rank-normalized split chains.
   - `ess-tail` — min ESS of the 5%/95% tail indicators (split chains).
   - `eti`      — equal-tailed interval (central `prob` mass; quantiles).
   - `hdi`      — highest-density interval (narrowest `prob`-mass interval).

   Chains are passed as a seq of per-chain numeric seqs; flat draw collections
   are accepted by `eti`/`hdi`. The autocovariance is computed directly (the
   draw counts here are small), and the rank-normal transform uses Apache
   Commons Math's normal quantile."
  (:require
   [orca.util :as util])
  (:import
   (org.apache.commons.math3.distribution NormalDistribution)))

(def ^:private normal (NormalDistribution. 0.0 1.0))

;; ── helpers ─────────────────────────────────────────────────────────────────

(defn- mean ^double [^doubles x]
  (/ (areduce x i s 0.0 (+ s (aget x i))) (alength x)))

(defn- var-ddof1
  "Sample variance (ddof=1) of a double seq."
  ^double [xs]
  (let [a (double-array xs)
        n (alength a)
        m (mean a)]
    (/ (areduce a i s 0.0 (+ s (let [d (- (aget a i) m)] (* d d))))
       (dec n))))

(defn- median ^double [xs]
  (util/quantile xs 0.5))

(defn- split-chains
  "Split each chain into its first and last half (ArviZ `_split_chains`),
   doubling the chain count. Odd middle sample is dropped."
  [chains]
  (mapcat (fn [c]
            (let [v    (vec c)
                  half (quot (count v) 2)]
              [(subvec v 0 half) (subvec v (- (count v) half))]))
          chains))

(defn- rank-average
  "1-based ranks of a flat seq, ties averaged (scipy `rankdata` 'average')."
  [xs]
  (let [v     (vec xs)
        n     (count v)
        order (vec (sort-by #(nth v %) (range n)))
        ranks (double-array n)]
    (loop [i 0]
      (when (< i n)
        (let [vi (nth v (nth order i))
              j  (loop [j i]
                   (if (and (< j n) (= (nth v (nth order j)) vi)) (recur (inc j)) j))
              avg (/ (+ (inc i) j) 2.0)]
          (doseq [k (range i j)] (aset ranks (nth order k) avg))
          (recur j))))
    (vec ranks)))

(defn- z-scale
  "Rank-normalize across all chains: rank → Φ⁻¹((rank-0.5)/N), reshaped back to
   the per-chain layout (ArviZ `_z_scale`)."
  [chains]
  (let [sizes (map count chains)
        flat  (apply concat chains)
        n     (count flat)
        z     (mapv #(.inverseCumulativeProbability normal (/ (- % 0.5) n))
                    (rank-average flat))]
    (first (reduce (fn [[acc more] sz]
                     [(conj acc (vec (take sz more))) (drop sz more)])
                   [[] z] sizes))))

(defn- fold
  "Fold every value around the global median (|x - median|)."
  [chains]
  (let [m (median (apply concat chains))]
    (mapv (fn [c] (mapv #(Math/abs (- (double %) m)) c)) chains)))

(defn- autocov
  "Biased autocovariance at every lag for one chain: acov[t] = (1/n) Σ
   (xᵢ-μ)(xᵢ₊ₜ-μ). acov[0] is the population variance (ArviZ `_autocov`)."
  ^doubles [^doubles x]
  (let [n (alength x)
        m (mean x)
        c (double-array n)]
    (dotimes [t n]
      (let [lim (- n t)]
        (aset c t
              (double
               (/ (loop [i 0 acc 0.0]
                    (if (< i lim)
                      (recur (inc i)
                             (+ acc (* (- (aget x i) m) (- (aget x (+ i t)) m))))
                      acc))
                  n)))))
    c))

;; ── ESS (ArviZ `_ess`, no internal split) ──────────────────────────────────

(defn- ess*
  "Core ESS on already-split (chain, draw) data — a seq of per-chain double[]."
  ^double [chains]
  (let [arrs       (mapv double-array chains)
        n-chain    (count arrs)
        n-draw     (alength ^doubles (first arrs))
        acov       (mapv autocov arrs)
        acov-at    (fn [t] (/ (reduce + (map #(aget ^doubles % t) acov)) n-chain))
        mean-var   (* (acov-at 0) (/ (double n-draw) (dec n-draw)))
        chain-mean (mapv mean arrs)
        var-plus   (cond-> (* mean-var (/ (dec n-draw) (double n-draw)))
                     (> n-chain 1) (+ (var-ddof1 chain-mean)))
        rho        (double-array n-draw)
        rho-t      (fn [t] (- 1.0 (/ (- mean-var (acov-at t)) var-plus)))]
    (aset rho 0 1.0)
    (aset rho 1 (rho-t 1))
    ;; Geyer initial positive sequence
    (let [max-t
          (loop [t 1, even 1.0, odd (rho-t 1)]
            (if (and (< t (- n-draw 3)) (> (+ even odd) 0.0))
              (let [e (rho-t (+ t 1)) o (rho-t (+ t 2))]
                (when (>= (+ e o) 0.0)
                  (aset rho (+ t 1) e)
                  (aset rho (+ t 2) o))
                (recur (+ t 2) e o))
              (- t 2)))]
      ;; Geyer initial monotone sequence
      (loop [t 1]
        (when (<= t (- max-t 2))
          (when (> (+ (aget rho (+ t 1)) (aget rho (+ t 2)))
                   (+ (aget rho (- t 1)) (aget rho t)))
            (let [avg (/ (+ (aget rho (- t 1)) (aget rho t)) 2.0)]
              (aset rho (+ t 1) avg)
              (aset rho (+ t 2) avg)))
          (recur (+ t 2))))
      (let [ess     (* n-chain n-draw)
            sum-pos (loop [i 0 s 0.0] (if (<= i max-t)
                                        (recur (inc i) (+ s (aget rho i))) s))
            extra   (if (< (inc max-t) n-draw) (aget rho (inc max-t)) 0.0)
            tau     (max (+ -1.0 (* 2.0 sum-pos) extra)
                         (/ 1.0 (Math/log10 ess)))]
        (/ ess tau)))))

(defn ess-bulk
  "Bulk effective sample size: ESS of the rank-normalized split chains."
  ^double [chains]
  (ess* (z-scale (split-chains chains))))

(defn- tail-indicator [chains threshold pred]
  (mapv (fn [c] (mapv #(if (pred (double %) threshold) 1.0 0.0) c)) chains))

(defn ess-tail
  "Tail effective sample size: min ESS of the lower-5% and upper-95% tail
   indicators (ArviZ `_ess_tail`, default probs 0.05/0.95)."
  ^double [chains]
  (let [flat (apply concat chains)
        q05  (util/quantile flat 0.05)
        q95  (util/quantile flat 0.95)
        lo   (ess* (split-chains (tail-indicator chains q05 <=)))
        hi   (ess* (split-chains (tail-indicator chains q95 >=)))]
    (min lo hi)))

;; ── R̂ ───────────────────────────────────────────────────────────────────────

(defn- rhat*
  "Classic split-R̂ on a seq of equal-length per-chain seqs."
  ^double [chains]
  (let [arrs   (mapv double-array chains)
        n-draw (alength ^doubles (first arrs))
        within (/ (reduce + (map var-ddof1 arrs)) (count arrs))
        means  (mapv mean arrs)
        between (* n-draw (var-ddof1 means))
        var-hat (+ (* (/ (dec n-draw) (double n-draw)) within)
                   (/ between n-draw))]
    (Math/sqrt (/ var-hat within))))

(defn rhat
  "Rank-normalized, folded split-R̂ (ArviZ default): the max of the
   rank-normalized split-R̂ and the median-folded rank-normalized split-R̂.
   Values near 1.00 indicate convergence (Phase B asserts < 1.01)."
  ^double [chains]
  (let [split (split-chains chains)
        bulk  (rhat* (z-scale split))
        tail  (rhat* (z-scale (fold split)))]
    (max bulk tail)))

;; ── intervals ───────────────────────────────────────────────────────────────

(defn eti
  "Equal-tailed interval holding the central `prob` mass (default 0.89):
   `[(1-prob)/2, 1-(1-prob)/2]` quantiles."
  ([draws] (eti draws 0.89))
  ([draws prob]
   (let [a (/ (- 1.0 prob) 2.0)]
     [(util/quantile draws a) (util/quantile draws (- 1.0 a))])))

(defn hdi
  "Highest-density interval covering `prob` mass (default 0.89): the narrowest
   interval between order statistics (ArviZ `_hdi`)."
  ([draws] (hdi draws 0.89))
  ([draws prob]
   (let [s   (vec (sort (map double draws)))
         n   (count s)
         inc-idx (long (Math/floor (* prob n)))
         k   (- n inc-idx)
         widths (mapv #(- (nth s (+ % inc-idx)) (nth s %)) (range k))
         ;; first minimal-width window (matches ArviZ's np.argmin tie-break)
         min-i (loop [i 1 best 0]
                 (if (< i k)
                   (recur (inc i) (if (< (widths i) (widths best)) i best))
                   best))]
     [(nth s min-i) (nth s (+ min-i inc-idx))])))

(defn summarize
  "Convenience summary for one parameter's `chains`: mean, sd, `prob` ETI,
   R̂, bulk & tail ESS."
  ([chains] (summarize chains 0.89))
  ([chains prob]
   (let [flat (apply concat chains)
         [lo hi] (eti flat prob)]
     {:mean (util/mean flat) :sd (util/pstdev flat)
      :eti-lo lo :eti-hi hi
      :rhat (rhat chains) :ess-bulk (ess-bulk chains) :ess-tail (ess-tail chains)})))
