(ns orca.synth
  "Part-A synthetic attribute generator (PURE).

   Generates incident/uneventful (Part-A) data from KNOWN parameters — a base
   rate (→ intercept) and a vector of true slopes — for `attr_logit.stan`
   recovery. The whole point is that the USER knows the result: you set the base
   rate, the predictor distributions, and the true slopes, and a later recovery
   harness (`orca.recover`, build step B4) confirms the model recovers them.

   The outcome model mirrors `attr_logit.stan` exactly:

       y_i ~ Bernoulli(inv_logit(alpha + sum_j beta_j * x_ij))

   where `alpha = logit(base-rate)`. Everything is deterministic given a seed:
   all randomness flows through a single `java.util.Random`, matching the
   convention in `orca.models/parameter-recovery` and `orca.models/prior-
   predictive`. No CmdStan dependency — fitting is build step B4/B6, not here.

   The Stan-ready half of the output (`{:N :K :X :y}`) matches what
   `orca.planner-fit/build-attr-design` emits and what `attr_logit.stan`'s data
   block expects: `int N; int K; matrix[N,K] X; array[N] int y`, with `X` a
   row-major vector-of-row-vectors (one inner vector per observation)."
  (:require
   [orca.util :as util]))

;; ── scalar helpers ───────────────────────────────────────────────────────────

(defn logit
  "log(p/(1-p)) — inverse of `orca.util/sigmoid`. The intercept is derived from
   the base rate: `alpha = (logit base-rate)` (e.g. 0.03 → ≈ -3.476)."
  ^double [^double p]
  (Math/log (/ p (- 1.0 p))))

(defn inv-logit
  "Logistic 1/(1+e^-x). Thin alias over `orca.util/sigmoid` so callers can keep
   the logit/inv-logit pair local to this namespace."
  ^double [^double x]
  (util/sigmoid x))

(defn make-rng
  "java.util.Random seeded from `seed` — the SINGLE source of all randomness in
   a generation run (this is what makes output reproducible given a seed)."
  ^java.util.Random [seed]
  (java.util.Random. (long seed)))

;; ── Cholesky (for optional correlated continuous predictors) ─────────────────

(defn cholesky
  "Lower-triangular Cholesky factor L of a symmetric positive-definite matrix
   `cov` (a vector-of-row-vectors), such that L·Lᵀ = cov. Returns L in the same
   vector-of-row-vectors shape. Used to draw correlated continuous predictors:
   x = L·z with z ~ Normal(0, I) yields x ~ Normal(0, cov)."
  [cov]
  (let [n (count cov)
        m (mapv (fn [_] (double-array n)) (range n))]
    (dotimes [i n]
      (dotimes [j (inc i)]
        (let [s (reduce (fn [acc k]
                          (+ acc (* (aget ^doubles (m i) k)
                                    (aget ^doubles (m j) k))))
                        0.0 (range j))
              cij (double (get-in cov [i j]))]
          (if (= i j)
            (aset ^doubles (m i) j (Math/sqrt (- cij s)))
            (aset ^doubles (m i) j (/ (- cij s) (aget ^doubles (m j) j)))))))
    (mapv vec m)))

;; ── predictor draws ──────────────────────────────────────────────────────────

(defn draw-continuous
  "Draw `n` values for a continuous predictor `spec` from `rng`.

   `{:dist :normal :mean 0 :sd 1}` → vector of n Normal(mean,sd) draws (defaults
   mean 0, sd 1: the standardized scale the design columns live on).

   `{:dist :mvnormal :mean [..] :cov [[..]..]}` is the OPTIONAL correlated form
   and draws a JOINT block of `k` correlated continuous predictors at once,
   returning a vector of n rows (each a length-k vector). x = mean + L·z with L
   the Cholesky factor of `cov` and z ~ Normal(0,I); document the chosen `cov`
   (a correlation/covariance matrix) at the call site."
  [^java.util.Random rng spec n]
  (case (:dist spec)
    :normal
    (let [{:keys [mean sd] :or {mean 0.0 sd 1.0}} spec
          mean (double mean) sd (double sd)]
      (vec (repeatedly n #(+ mean (* sd (.nextGaussian rng))))))

    :mvnormal
    (let [{:keys [mean cov]} spec
          k (count mean)
          l (cholesky cov)]
      (vec (repeatedly
             n
             (fn []
               (let [z (double-array k)]
                 (dotimes [i k] (aset z i (.nextGaussian rng)))
                 (mapv (fn [i]
                         (+ (double (nth mean i))
                            (reduce (fn [acc j]
                                      (+ acc (* (double (get-in l [i j]))
                                                (aget z j))))
                                    0.0 (range (inc i)))))
                       (range k)))))))

    (throw (ex-info "Unknown continuous distribution" {:spec spec}))))

(defn draw-categorical
  "Draw `n` INDEX codes 0..K-1 from a Categorical with class probabilities
   `probs` (need not sum to exactly 1 — they are treated as cumulative cut
   points and the last class absorbs any remainder). Index coding matches how
   `orca.planner-fit` folds an ordinal into a single design column: the integer
   level itself becomes the column value."
  [^java.util.Random rng probs n]
  (let [cum (vec (reductions + probs))
        k   (count probs)]
    (vec (repeatedly
           n
           (fn []
             (let [u (.nextDouble rng)]
               ;; first class whose cumulative prob exceeds u; last absorbs rest.
               (loop [i 0]
                 (cond
                   (>= i (dec k)) (dec k)
                   (< u (double (nth cum i))) i
                   :else (recur (inc i))))))))))

;; ── design matrix ────────────────────────────────────────────────────────────

(defn draw-predictors
  "Draw the per-predictor raw values for every spec in `predictors`, in order,
   from a single shared `rng`. Returns a vector aligned to `predictors`; each
   element is the n-long draw for that predictor:
     - continuous :normal     → vector of n doubles
     - continuous :mvnormal   → vector of n rows (each a k-vector)
     - categorical            → vector of n integer index codes
   Pulling all draws up front (one rng, predictor order fixed) is what keeps the
   whole run reproducible."
  [^java.util.Random rng predictors n]
  (mapv (fn [spec]
          (case (:dist spec)
            (:normal :mvnormal) (draw-continuous rng spec n)
            :categorical (draw-categorical rng (:probs spec) n)
            (throw (ex-info "Unknown predictor distribution" {:spec spec}))))
        predictors))

(defn design-matrix
  "Expand ordered `predictors` + their `raw-draws` (from `draw-predictors`) into
   the N×K design matrix `attr_logit.stan` expects — a row-major
   vector-of-row-vectors (one inner vector per observation).

   Column expansion per spec:
     - continuous :normal    → 1 column (the value as-is)
     - continuous :mvnormal  → k columns (the joint block, in order)
     - categorical           → 1 column holding the integer index value
       (matching how `orca.planner-fit` standardizes ordinals into a single
       column rather than one-hot dummies)."
  [predictors raw-draws]
  (let [n (count (first raw-draws))]
    (mapv (fn [i]
            (vec (mapcat (fn [spec draw]
                           (case (:dist spec)
                             :normal      [(double (nth draw i))]
                             :mvnormal    (mapv double (nth draw i))
                             :categorical [(double (nth draw i))]))
                         predictors raw-draws)))
          (range n))))

;; ── outcome simulation ───────────────────────────────────────────────────────

(defn- truth-map
  "Ground-truth coefficient map keyed the way the recovery harness scores Stan
   draws: {\"alpha\" .. \"beta.1\" .. \"beta.2\" ..} (1-based, matching Stan's
   `vector[K] beta` indexing in `attr_logit.stan`)."
  [alpha betas]
  (into {"alpha" alpha}
        (map-indexed (fn [i b] [(str "beta." (inc i)) (double b)]) betas)))

(defn simulate-attr
  "Generate synthetic Part-A attribute data from KNOWN parameters.

   Config map keys:
     :base-rate   incident base rate p₀; the true intercept is
                  alpha = (logit p₀)  (e.g. 0.03 → ≈ -3.476).
     :predictors  ORDERED vector of column specs. Continuous:
                  {:dist :normal :mean 0 :sd 1} or correlated-block
                  {:dist :mvnormal :mean [..] :cov [[..]..]}; categorical:
                  {:dist :categorical :probs [..]} (index coded).
     :true-slopes vector aligned to the EXPANDED design columns (so an n-column
                  :mvnormal block consumes n slopes). length must equal K.
     :n           number of observations.
     :seed        RNG seed (default 42).

   Outcome: y_i ~ Bernoulli(inv_logit(alpha + Σ_k beta_k x_ik)). Deterministic
   given the seed.

   Returns:
     {:N :K            dimensions (K = #expanded design columns)
      :X               row-major N×K design matrix (vector-of-row-vectors),
                       directly usable as `attr_logit.stan`'s `X`
      :y               length-N vector of 0/1 outcomes
      :truth           {\"alpha\" .. \"beta.1\" ..} ground-truth coefficients
      :alpha :betas    same truth, split out for convenience
      :base-rate       echoed input base rate
      :predictor-spec  echoed `:predictors` (so the harness can re-derive layout)
      :seed}"
  [{:keys [base-rate predictors true-slopes n seed] :or {seed 42}}]
  (let [alpha     (logit (double base-rate))
        betas     (mapv double true-slopes)
        rng       (make-rng seed)
        raw       (draw-predictors rng predictors n)
        x         (design-matrix predictors raw)
        k         (count (first x))]
    (when (not= k (count betas))
      (throw (ex-info "true-slopes length must equal expanded design columns"
                      {:K k :n-slopes (count betas)})))
    (let [y (mapv (fn [row]
                    (let [lp (reduce (fn [acc [b xv]] (+ acc (* (double b) (double xv))))
                                     alpha (map vector betas row))
                          p  (inv-logit lp)]
                      (if (< (.nextDouble rng) p) 1 0)))
                  x)]
      {:N n :K k :X x :y y
       :truth (truth-map alpha betas)
       :alpha alpha :betas betas
       :base-rate base-rate :predictor-spec (vec predictors)
       :seed seed})))

(defn gen-attr-data
  "Config-driven alias for `simulate-attr` matching the build-plan call shape:
     (gen-attr-data {:n 600 :base-rate 0.03 :predictors [..] :seed 42})
   `:predictors` entries may carry a `:slope` (or `:slopes` for an :mvnormal
   block) so distribution + true slope are declared TOGETHER; this derives
   `:true-slopes` from them when `:true-slopes` is not given explicitly."
  [{:keys [predictors true-slopes] :as cfg}]
  (let [slopes (or true-slopes
                   (vec (mapcat (fn [spec]
                                  (if (= :mvnormal (:dist spec))
                                    (mapv double (:slopes spec))
                                    [(double (:slope spec))]))
                                predictors)))]
    (simulate-attr (assoc cfg :true-slopes slopes))))
