(ns orca.models
  "The M0–M4 model ladder (porting.md §4.5; methodology §4).

   Five logistic-regression models of increasing complexity, all fit with
   CmdStan via `orca.stan` against the prepared data (`orca.prepare`):

     M0  intercept only                                   (base rate)
     M1  + boat length, rudder, antifoul, hull            (vessel)
     M2  + sailing mode, speed, autopilot                 (activity)
     M3  + depth, distance, wind, sea state — 30 params   (primary; no daylight)
     M4  + moon, tide, cloud cover — 33 params            (extended)

   All ladder rungs use the model-building Fermi intercept prior N(-3.5,0.6) with
   slopes/offsets N(0,0.5) (stan/m0..m2, m3_build, m4). This is the ladder M3
   (`m3_build.stan`), which differs from the calculator's relaxed final refit
   (`orca.model` / `stan/m3.stan`). Time of day is excluded per methodology §7;
   risk scenarios therefore drop the historical `beta_daylight` term.

   Convergence is gated in Clojure (R̂<1.01, ESS>400, 0 divergences) via
   `orca.diagnostics`; model comparison is WAIC via `orca.waic`."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [orca.diagnostics :as diag]
   [orca.plot :as plot]
   [orca.prepare :as prep]
   [orca.stan :as stan]
   [orca.util :as util]
   [orca.waic :as waic]
   [tablecloth.api :as tc]))

;; ── model definitions ────────────────────────────────────────────────────────

(def stan-file
  "Stan source for each ladder rung. M3 uses the model-building m3_build.stan
   (no daylight, Fermi prior), not the calculator's final m3.stan."
  {:m0 "stan/m0.stan" :m1 "stan/m1.stan" :m2 "stan/m2.stan"
   :m3 "stan/m3_build.stan" :m4 "stan/m4.stan"})

(def required
  "Predictor columns that must be complete (complete-case mask) per model."
  (let [m1 [:boat_length_ord_std :rudder_idx :antifoul_idx :hull_colour_idx]
        m2 (into m1 [:sailing_mode_idx :speed_ord_std :autopilot_on])
        m3 (into m2 [:depth_ord_std :distance_ord_std :wind_ord_std
                     :sea_state_ord_std])
        m4 (into m3 [:moon_illumination_std :is_spring_tide :cloud_cover_ord_std])]
    {:m0 [] :m1 m1 :m2 m2 :m3 m3 :m4 m4}))

(def order
  "Ladder order, smallest to largest."
  [:m0 :m1 :m2 :m3 :m4])

(defn- col [d k] (vec (d k)))
(defn- idx1 [d k] (mapv #(inc (long %)) (d k)))
(defn- ncat [md k] (count (get-in md [:categories k])))

(defn- vessel-data [d md]
  {:n_antifoul (ncat md :antifoul) :antifoul (idx1 d :antifoul_idx)
   :n_hull     (ncat md :hull_colour) :hull (idx1 d :hull_colour_idx)
   :n_rudder   (ncat md :rudder) :rudder (idx1 d :rudder_idx)})

(defn- sailing-data [d md]
  {:n_sailing (ncat md :sailing_mode) :sailing (idx1 d :sailing_mode_idx)})

(defn- env-data [d]
  {:depth     (col d :depth_ord_std)
   :autopilot (mapv double (d :autopilot_on))
   :speed     (col d :speed_ord_std)
   :boatlen   (col d :boat_length_ord_std)
   :distance  (col d :distance_ord_std)
   :wind      (col d :wind_ord_std)
   :sea       (col d :sea_state_ord_std)})

(defn stan-data
  "Build the CmdStan data map for ladder model `k` (:m0..:m4) from a
   complete-case dataset `d` + metadata `md`. Category indices are 1-based."
  [k d md]
  (let [base {:N (tc/row-count d) :y (mapv long (d :interaction))}]
    (case k
      :m0 base
      :m1 (merge base {:boatlen (col d :boat_length_ord_std)} (vessel-data d md))
      :m2 (merge base
                 {:boatlen   (col d :boat_length_ord_std)
                  :speed     (col d :speed_ord_std)
                  :autopilot (mapv double (d :autopilot_on))}
                 (sailing-data d md) (vessel-data d md))
      :m3 (merge base (env-data d) (sailing-data d md) (vessel-data d md))
      :m4 (merge base (env-data d)
                 {:moon  (col d :moon_illumination_std)
                  :tide  (mapv double (d :is_spring_tide))
                  :cloud (col d :cloud_cover_ord_std)}
                 (sailing-data d md) (vessel-data d md)))))

(defn complete-cases
  "Filter `data` to rows complete on model `k`'s required predictors."
  [k data]
  (let [req (required k)]
    (if (seq req) (tc/drop-missing data req) data)))

;; ── fitting & summaries ──────────────────────────────────────────────────────

(defn fit
  "Fit ladder model `k` on its complete cases. `data`/`md` from `orca.prepare`.
   Returns {:model :data :n :stan-data :chains :draws}. `opts` flow to
   `orca.stan/sample-chains` (n-chains, seed, num-warmup, num-samples, out-dir)."
  ([k data md] (fit k data md {}))
  ([k data md opts]
   (let [d  (complete-cases k data)
         sd (stan-data k d md)
         chains (stan/sample-chains (stan-file k) sd opts)]
     {:model k :data d :n (tc/row-count d) :stan-data sd
      :chains chains :draws (apply tc/concat chains)})))

(defn param-cols
  "Posterior parameter column names of a draws dataset: all columns except the
   sampler diagnostics (`*__`) and the pointwise `log_lik.*`."
  [draws]
  (->> (tc/column-names draws)
       (map str)
       (remove #(str/ends-with? % "__"))
       (remove #(str/starts-with? % "log_lik"))))

(defn summary
  "Per-parameter summary table for a fit: {:param mean sd eti-lo eti-hi rhat
   ess-bulk ess-tail}, one row per posterior parameter, via `orca.diagnostics`."
  [{:keys [chains draws]}]
  (mapv (fn [p]
          (assoc (diag/summarize (mapv #(vec (% p)) chains)) :param p))
        (param-cols draws)))

(defn convergence
  "Convergence gate for a fit: max R̂, min ESS (bulk & tail), divergence count,
   and an :ok flag (R̂<1.01, ESS>400, 0 divergences)."
  [ft]
  (let [s        (summary ft)
        max-rhat (apply max (map :rhat s))
        min-ess  (apply min (mapcat (juxt :ess-bulk :ess-tail) s))
        n-div    (->> ((:draws ft) "divergent__") (filter pos?) count)]
    {:max-rhat max-rhat :min-ess min-ess :n-divergent n-div
     :ok (and (< max-rhat 1.01) (> min-ess 400.0) (zero? n-div))}))

(defn print-summary
  "Print a fit's parameter summary (mean, sd, 89% ETI, R̂, ESS) + convergence."
  [ft]
  (println (format "%-22s %9s %8s %9s %9s %7s %8s %8s"
                   "param" "mean" "sd" "eti_lo" "eti_hi" "rhat" "ess_b" "ess_t"))
  (doseq [{:keys [param mean sd eti-lo eti-hi rhat ess-bulk ess-tail]} (summary ft)]
    (println (format "%-22s %+9.3f %8.3f %+9.3f %+9.3f %7.4f %8.0f %8.0f"
                     param mean sd eti-lo eti-hi rhat ess-bulk ess-tail)))
  (let [{:keys [max-rhat min-ess n-divergent ok]} (convergence ft)]
    (println (format "  max R̂=%.4f  min ESS=%.0f  divergences=%d  %s"
                     max-rhat min-ess n-divergent (if ok "✅" "❌")))))

;; ── linear-predictor reconstruction (PPC, risk scenarios) ────────────────────

(def ^:private cont-terms
  "Stan data key → posterior coefficient column for continuous/binary terms."
  {:depth "beta_depth" :autopilot "beta_autopilot" :speed "beta_speed"
   :boatlen "beta_length" :distance "beta_distance" :wind "beta_wind"
   :sea "beta_sea" :moon "beta_moon" :tide "beta_tide" :cloud "beta_cloud"})

(def ^:private index-terms
  "Stan data key → posterior offset family for index (categorical) terms."
  {:sailing "alpha_sailing" :antifoul "alpha_antifoul"
   :hull "alpha_hull" :rudder "alpha_rudder"})

(defn- idx-cols
  "Per-category posterior draw arrays for index family `pname`, ordered 1..K."
  [draws pname]
  (let [pat   (re-pattern (str (java.util.regex.Pattern/quote pname) "\\.\\d+"))
        nc    (count (filter #(re-matches pat (str %)) (tc/column-names draws)))]
    (mapv #(double-array (draws (str pname "." (inc %)))) (range nc))))

(defn pred-rates
  "Posterior predicted interaction rate per draw for a fit: for each draw s,
   mean over observations of sigmoid(logit_p[s,i]). Reconstructs logit_p from
   the fit's stan-data and posterior draws (a posterior-predictive check on the
   mean rate)."
  [{sd :stan-data draws :draws}]
  (let [n     (long (:N sd))
        s     (tc/row-count draws)
        alpha (double-array (draws "alpha"))
        conts (vec (for [[k pn] cont-terms :when (contains? sd k)]
                     [(double-array (draws pn)) (double-array (sd k))]))
        idxs  (vec (for [[k pn] index-terms :when (contains? sd k)]
                     [(idx-cols draws pn) (int-array (map (comp dec long) (sd k)))]))
        out   (double-array s)]
    (dotimes [d s]
      (let [a (aget alpha d)]
        (loop [i 0 sum 0.0]
          (if (< i n)
            (let [lp (loop [lp a c conts]
                       (if (seq c)
                         (let [[^doubles cd ^doubles dv] (first c)]
                           (recur (+ lp (* (aget cd d) (aget dv i))) (next c)))
                         lp))
                  lp (loop [lp lp ix idxs]
                       (if (seq ix)
                         (let [[cols ^ints idx] (first ix)]
                           (recur (+ lp (aget ^doubles (nth cols (aget idx i)) d))
                                  (next ix)))
                         lp))]
              (recur (inc i) (+ sum (/ 1.0 (+ 1.0 (Math/exp (- lp)))))))
            (aset out d (/ sum n))))))
    (vec out)))

(defn posterior-predictive-check
  "Compare posterior predicted interaction rate against observed. Returns
   {:observed :pred-mean :eti}; optionally writes a histogram to `:png`."
  [{:keys [data] :as ft} & [{:keys [png]}]]
  (let [rates    (pred-rates ft)
        observed (util/mean (data :interaction))
        [lo hi]  (diag/eti rates)]
    (when png
      (plot/histogram png rates
                      {:title "Posterior predictive: predicted rate"
                       :x-label "predicted interaction rate" :bins 50}))
    {:observed observed :pred-mean (util/mean rates) :eti [lo hi]}))

;; ── prior predictive ─────────────────────────────────────────────────────────

(defn prior-predictive
  "Prior predictive interaction-rate summary for the intercept-only base rate:
   draw `n` samples α~N(-3.5,0.6) and report sigmoid(α). Mirrors methodology §4's
   M0 prior predictive check (probability mass centred ~2-7%). Optionally writes
   a histogram to `:png`."
  [& [{:keys [n png seed] :or {n 4000 seed 42}}]]
  (let [rng (java.util.Random. (long seed))
        ps  (vec (repeatedly n #(let [a (+ -3.5 (* 0.6 (.nextGaussian rng)))]
                                  (/ 1.0 (+ 1.0 (Math/exp (- a)))))))
        [lo hi] (diag/eti ps)]
    (when png
      (plot/histogram png ps {:title "Prior predictive — M0 intercept"
                              :x-label "prior P(interaction)" :bins 50}))
    {:mean (util/mean ps) :median (util/quantile ps 0.5) :eti [lo hi]}))

;; ── parameter recovery ───────────────────────────────────────────────────────

(defn parameter-recovery
  "Simulate N=654 observations from known α=-3.5, β_speed=0.3, β_depth=-0.2,
   refit `stan/recovery.stan`, and check each true value lies in its 89% ETI
   (methodology §4). Uses java.util.Random (seed 42); the RNG differs from numpy
   so the check is HDI coverage, not exact numbers. Returns a row per parameter
   with :true, :mean, :eti and :in-eti."
  [& [opts]]
  (let [rng    (java.util.Random. 42)
        n      654
        truth  {"alpha" -3.5 "beta_speed" 0.3 "beta_depth" -0.2}
        speed  (vec (repeatedly n #(.nextGaussian rng)))
        depth  (vec (repeatedly n #(.nextGaussian rng)))
        y      (mapv (fn [s d]
                       (let [lp (+ -3.5 (* 0.3 s) (* -0.2 d))
                             p  (/ 1.0 (+ 1.0 (Math/exp (- lp))))]
                         (if (< (.nextDouble rng) p) 1 0)))
                     speed depth)
        chains (stan/sample-chains "stan/recovery.stan"
                                   {:N n :y y :speed speed :depth depth}
                                   (or opts {}))]
    (mapv (fn [[p tv]]
            (let [[lo hi] (diag/eti (apply concat (map #(vec (% p)) chains)))]
              {:param p :true tv :mean (util/mean (apply concat (map #(vec (% p)) chains)))
               :eti [lo hi] :in-eti (<= lo tv hi)}))
          truth)))

;; ── risk scenarios ───────────────────────────────────────────────────────────

(defn- standardize-val
  "Standardize a raw ordinal level for predictor `param` using metadata."
  [md param raw]
  (let [{:keys [mean sd]} (get-in md [:standardization param])]
    (/ (- raw mean) sd)))

(defn- scenario-p
  "Posterior P(interaction) draws for a scenario = seq of [coeff-name value],
   plus the intercept. The categorical offsets (alpha_sailing/antifoul/hull/
   rudder) are MARGINALIZED at their prior-centered mean of 0, i.e. an
   average-category vessel — NOT a named configuration. The absolute P is
   therefore only meaningful *relative* across scenarios (the blog frames these
   as ordinal comparisons; named-category absolute risk is the calculator's job,
   index.html)."
  [draws params]
  (let [s     (tc/row-count draws)
        alpha (double-array (draws "alpha"))
        terms (mapv (fn [[nm v]] [(double-array (draws nm)) (double v)]) params)]
    (mapv (fn [d]
            (let [lp (reduce (fn [acc [^doubles c v]] (+ acc (* (aget c d) v)))
                             (aget alpha d) terms)]
              (/ 1.0 (+ 1.0 (Math/exp (- lp))))))
          (range s))))

(defn risk-scenarios
  "Predicted P(interaction) for the low/medium/higher-risk scenarios
   (methodology §4), with no daylight term (time of day is handled separately,
   §7). Categorical offsets are marginalized at 0 (see `scenario-p`), so the
   absolute P is meaningful only *relative* across scenarios — the ordinal
   comparison the blog calls the robust output. Returns a row per scenario with
   :mean, :median, :eti."
  [{:keys [draws]} md]
  (let [sv (partial standardize-val md)
        scenarios
        [["Low: 12m, 5-7kts, 200m+, light wind, autopilot on"
          [["beta_length" (sv :boat_length_ord 1)] ["beta_speed" (sv :speed_ord 2)]
           ["beta_depth" (sv :depth_ord 3)] ["beta_distance" (sv :distance_ord 3)]
           ["beta_wind" (sv :wind_ord 1)] ["beta_sea" (sv :sea_state_ord 0)]
           ["beta_autopilot" 1]]]
         ["Medium: 12m, 5-7kts, 40-200m, moderate wind, autopilot on"
          [["beta_length" (sv :boat_length_ord 1)] ["beta_speed" (sv :speed_ord 2)]
           ["beta_depth" (sv :depth_ord 2)] ["beta_distance" (sv :distance_ord 2)]
           ["beta_wind" (sv :wind_ord 1)] ["beta_sea" (sv :sea_state_ord 1)]
           ["beta_autopilot" 1]]]
         ["Higher: 15m+, 3-4kts, 20-40m, 0-2nm, autopilot on"
          [["beta_length" (sv :boat_length_ord 3)] ["beta_speed" (sv :speed_ord 1)]
           ["beta_depth" (sv :depth_ord 1)] ["beta_distance" (sv :distance_ord 0)]
           ["beta_wind" (sv :wind_ord 0)] ["beta_sea" (sv :sea_state_ord 0)]
           ["beta_autopilot" 1]]]]]
    (mapv (fn [[nm params]]
            (let [p (scenario-p draws params)
                  [lo hi] (diag/eti p)]
              {:scenario nm :mean (util/mean p) :median (util/quantile p 0.5)
               :eti [lo hi]}))
          scenarios)))

;; ── plots ────────────────────────────────────────────────────────────────────

(def results-path
  "Path under the configured output dir's results/ folder (see orca.config)."
  config/results-path)

(defn coefficient-forest
  "Forest plot (mean + 89% ETI) of the slope (beta_*) coefficients of a fit."
  [ft png]
  (let [rows (->> (summary ft)
                  (filter #(str/starts-with? (:param %) "beta_"))
                  (mapv (fn [{:keys [param mean eti-lo eti-hi]}]
                          {:label param :mean mean :lo eti-lo :hi eti-hi})))]
    (plot/forest png rows {:title "Coefficient posteriors (89% ETI)"
                           :x-label "log-odds per 1 SD"})))

(defn index-forest
  "Forest plot of category-level offsets for index `family` (e.g. \"alpha_antifoul\"),
   labelled with the category names from metadata key `cat-key`."
  [ft md family cat-key png]
  (let [cats (get-in md [:categories cat-key])
        rows (->> (summary ft)
                  (filter #(str/starts-with? (:param %) (str family ".")))
                  (sort-by #(parse-long (subs (:param %) (inc (count family)))))
                  (map-indexed (fn [i {:keys [mean eti-lo eti-hi]}]
                                 {:label (nth cats i) :mean mean
                                  :lo eti-lo :hi eti-hi})))]
    (plot/forest png (vec rows) {:title (str cat-key " effects (89% ETI)")
                                 :x-label "log-odds"})))

(defn trace-plot
  "Trace plot of one parameter `p` across a fit's chains."
  [{:keys [chains]} p png]
  (plot/trace png (mapv #(vec (% p)) chains) {:title (str "Trace — " p) :y-label p}))

;; ── orchestration ────────────────────────────────────────────────────────────

(defn compare-m3-m4
  "WAIC comparison of M3 (m3_build) vs M4 on a common complete-case set (M4's
   required predictors, so both share N and the comparison is well defined).
   Returns the `orca.waic/compare` table (best-first)."
  [data md opts]
  (let [d   (complete-cases :m4 data)
        f3  {:draws (apply tc/concat
                           (stan/sample-chains (stan-file :m3) (stan-data :m3 d md) opts))}
        f4  {:draws (apply tc/concat
                           (stan/sample-chains (stan-file :m4) (stan-data :m4 d md) opts))}]
    (waic/compare {"M3" (waic/waic-of (:draws f3))
                   "M4" (waic/waic-of (:draws f4))})))

(defn run-all
  "End-to-end ladder pipeline: prior predictive (M0),
   parameter recovery, fit M0–M4 with convergence gating, PPC for M3/M4, WAIC
   comparison, and the M3 result plots. Returns a map of the computed pieces.
   `opts` flow to sampling (defaults from config :mcmc)."
  [& [{:keys [opts] :or {opts {}}}]]
  (let [opts (stan/mcmc-opts opts)
        {:keys [data metadata]} (prep/prepare
                                  (util/read-json (config/cfg :paths :raw)))]
    (println "── prior predictive (M0) ──")
    (let [pp (prior-predictive {:png (results-path "prior_predictive_M0.png")})]
      (println pp))
    (println "── parameter recovery ──")
    (doseq [r (parameter-recovery opts)] (println r))
    (println "── fit ladder ──")
    (let [fits (into {} (for [k order]
                          (let [f (fit k data metadata opts)]
                            (println (format "%s: N=%d" (name k) (:n f)))
                            (print-summary f)
                            [k f])))]
      (println "── PPC ──")
      (doseq [k [:m3 :m4]]
        (println (name k)
                 (posterior-predictive-check
                   (fits k) {:png (results-path (str "ppc_" (name k) ".png"))})))
      (println "── WAIC M3 vs M4 ──")
      (let [cmp (compare-m3-m4 data metadata opts)]
        (doseq [r cmp] (println r))
        (println "── M3 result plots ──")
        (coefficient-forest (fits :m3) (results-path "coefficient_forest.png"))
        (index-forest (fits :m3) metadata "alpha_antifoul" :antifoul
                      (results-path "antifoul_effects.png"))
        (index-forest (fits :m3) metadata "alpha_sailing" :sailing_mode
                      (results-path "sailing_mode_effects.png"))
        (trace-plot (fits :m3) "alpha" (results-path "trace_M3_alpha.png"))
        (doseq [r (risk-scenarios (fits :m3) metadata)] (println r))
        {:prior-predictive (prior-predictive)
         :convergence (into {} (for [[k f] fits] [k (convergence f)]))
         :comparison cmp}))))
