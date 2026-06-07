(ns orca.findings
  "End-to-end validation of the two headline findings — black antifoul and
   night/day.

   Each validation walks a full chain of evidence:
     1. raw value-counts of the predictor (incident vs uneventful), straight
        from the scraped reports;
     2. the processed modeling data: per-level n / incidents / uneventful / rate;
     3. 2×2 contingency tables with χ² (Yates) and Fisher's exact test — for
        antifoul both Black-vs-Not-Black and Black-vs-Coppercoat; for daylight
        Night-vs-Day;
     4. confound checks: t-tests of the genuinely continuous predictors across the
        two groups, plus a category-share crosstab (the meaningful nominal check);
     5. a single-predictor Bayesian model (antifoul_only.stan / daylight_only.stan,
        no other controls), its convergence diagnostics, and the contrasts /
        predicted P(interaction) / risk ratio it implies.

   The frequentist tests come from orca.stats (matched to scipy defaults so the
   console numbers reproduce); the Bayesian fits go through orca.stan and are
   summarized with orca.diagnostics.

   Note on the science: the single-predictor models are *isolation checks*, not
   the primary analysis. The headline antifoul effect is read from the full M3
   (orca.results); the night effect is reported separately by the exposure-based
   Poisson rate ratio (orca.timeofday), time of day having been removed from M3
   (methodology §7). These validations show the raw signals exist before any
   controls — they do not replace the controlled estimates."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [orca.diagnostics :as diag]
   [orca.params :as params]
   [orca.prepare :as prep]
   [orca.stan :as stan]
   [orca.stats :as stats]
   [orca.util :as util]
   [tablecloth.api :as tc]))

;; ── shared helpers ───────────────────────────────────────────────────────────

(defn- pct [n d] (if (pos? d) (* 100.0 (/ (double n) d)) 0.0))

(def ^:private cat-index params/cat-index)

(defn- print-value-counts
  "Print value counts of `xs` (nil kept), sorted by count descending."
  [label xs]
  (let [n  (count xs)
        vc (sort-by (comp - val) (stats/value-counts xs))]
    (println (format "  %s (n=%d):" label n))
    (doseq [[v c] vc]
      (println (format "    %-30s %4d  (%.1f%%)" (str v) c (pct c n))))))

(defn confound-ttests
  "Independent t-tests of each genuinely continuous predictor in `vars` between
   the rows where `group?` (a fn of row index) is true vs false, dropping nil
   values and rows where `group?` is nil (excluded from BOTH groups, not pooled
   into false). Returns a row per var {:predictor :mean-true :mean-false :diff
   :t :p}.

   Note: this is only meaningful for continuous confounds. Nominal index columns
   (sailing_mode_idx, antifoul_idx) have no t-test interpretation — use
   `category-share` for those."
  [data vars group?]
  (let [n   (tc/row-count data)
        grp (mapv group? (range n))]
    (->> vars
         (keep (fn [v]
                 (let [col (vec (data v))
                       tv  (->> (range n)
                                (filter #(and (true? (nth grp %)) (some? (nth col %))))
                                (mapv #(double (nth col %))))
                       fv  (->> (range n)
                                (filter #(and (false? (nth grp %)) (some? (nth col %))))
                                (mapv #(double (nth col %))))]
                   (when (and (> (count tv) 5) (> (count fv) 5))
                     (let [{:keys [t p mean-x mean-y diff]} (stats/t-test tv fv)]
                       {:predictor v :mean-true mean-x :mean-false mean-y
                        :diff diff :t t :p p})))))
         vec)))

(defn category-share
  "Per-group category-share crosstab: for each group in `groups` (a seq of
   [label row-pred]), the % of that group's non-nil `cat-col` rows falling in each
   category of `cats`. Returns a seq of {:label :n :shares [{:category :pct}…]}.
   This is the interpretable nominal confound diagnostic — e.g. the sailing-mode
   distribution by night/day and by antifoul colour."
  [data cat-col cats groups]
  (let [n   (tc/row-count data)
        col (vec (data cat-col))]
    (mapv (fn [[label pred]]
            (let [rows  (filter #(true? (pred %)) (range n))
                  cvals (->> rows (map #(nth col %)) (remove nil?) vec)
                  total (count cvals)]
              {:label label :n (count rows)
               :shares (mapv (fn [i category]
                               {:category category
                                :pct (if (pos? total)
                                       (* 100.0 (/ (count (filter #(= i %) cvals)) total))
                                       0.0)})
                             (range (count cats)) cats)}))
          groups)))

(defn- print-convergence
  "Print R̂ + min-ESS (and divergence count) for `params` of a fit's chains,
   before reporting the contrasts (same gate test-night-encoding uses)."
  [{:keys [chains draws]} params]
  (let [summ  (mapv (fn [p] (assoc (diag/summarize (mapv #(vec (% p)) chains))
                                   :param p))
                    params)
        max-r (apply max (map :rhat summ))
        min-e (apply min (mapcat (juxt :ess-bulk :ess-tail) summ))
        ndiv  (when (some #(= "divergent__" (str %)) (tc/column-names draws))
                (->> (draws "divergent__") (filter pos?) count))]
    (println (format "  convergence: max R̂=%.4f  min ESS=%.0f%s  %s"
                     max-r min-e
                     (if ndiv (format "  divergences=%d" ndiv) "")
                     (if (and (< max-r 1.01) (> min-e 400.0)
                              (or (nil? ndiv) (zero? ndiv)))
                       "✅" "❌")))))

;; ── antifoul validation ──────────────────────────────────────────────────────

(defn antifoul-levels
  "Per-category breakdown of the processed data: a row per antifoul category
   {:idx :category :n :incidents :uneventful :rate}, in metadata order. Empty
   categories appear with zero counts."
  [data md]
  (let [cats (get-in md [:categories :antifoul])
        by   (util/group-rates data :antifoul_idx)]
    (mapv (fn [k category]
            (let [{:keys [n incidents uneventful rate]
                   :or   {n 0 incidents 0 uneventful 0 rate 0.0}} (by (long k))]
              {:idx k :category category :n n :incidents incidents
               :uneventful uneventful :rate rate}))
          (range (count cats)) cats)))

(defn black-vs-not-black
  "2×2 (Black vs Not-Black) × (uneventful vs incident) over ALL rows: nil
   antifoul_idx lands in Not-Black (only `idx == 0` is Black).
   Returns {:table :chi2 :fisher}.

   Layout (orca.stats/crosstab-2x2, rows=is-black, cols=is-incident, false→true):
     [[notblack-uneventful notblack-incident]
      [black-uneventful    black-incident]]"
  [data md]
  (let [black-i  (cat-index md :antifoul "Black")
        idxv     (vec (data :antifoul_idx))
        interv   (vec (data :interaction))
        is-black (mapv #(= black-i %) idxv)
        is-inc   (mapv #(= 1 %) interv)
        table    (stats/crosstab-2x2 is-black is-inc)]
    {:table table :chi2 (stats/chi2-contingency table) :fisher (stats/fisher-exact table)}))

(defn black-vs-coppercoat
  "2×2 contingency (Black vs Coppercoat) × (uneventful vs incident) on the
   Black/Coppercoat rows, with χ², Fisher, and per-group incident rates. Returns
   {:table :chi2 :fisher :black-rate :copper-rate}.

   Table layout (orca.stats/crosstab-2x2, rows=is-black, cols=is-incident,
   false→true):
     [[copper-uneventful copper-incident]
      [black-uneventful  black-incident]]"
  [data md]
  (let [black-i  (cat-index md :antifoul "Black")
        copper-i (cat-index md :antifoul "Coppercoat")
        idxv     (vec (data :antifoul_idx))
        interv   (vec (data :interaction))
        rows     (filter #(#{black-i copper-i} (nth idxv %)) (range (count idxv)))
        is-black (mapv #(= black-i (nth idxv %)) rows)
        is-inc   (mapv #(= 1 (nth interv %)) rows)
        table    (stats/crosstab-2x2 is-black is-inc)
        [[cu ci] [bu bi]] table]
    {:table table
     :chi2 (stats/chi2-contingency table)
     :fisher (stats/fisher-exact table)
     :black-rate (/ (double bi) (max 1 (+ bu bi)))
     :copper-rate (/ (double ci) (max 1 (+ cu ci)))}))

(defn- antifoul-stan-data
  "Single-predictor stan data: complete-case on antifoul, 1-based index."
  [data md]
  (let [d (tc/drop-missing data [:antifoul_idx])]
    {:data d
     :stan-data {:N (tc/row-count d)
                 :y (mapv long (d :interaction))
                 :n_antifoul (count (get-in md [:categories :antifoul]))
                 :antifoul (mapv #(inc (long %)) (d :antifoul_idx))}}))

(defn- index-draws
  "Per-category posterior draw vectors for index family `family` (Stan columns
   family.1..family.K), ordered 0..K-1."
  [draws family k]
  (mapv #(vec (draws (str family "." (inc %)))) (range k)))

(defn fit-antifoul
  "Fit antifoul_only.stan and return {:chains :draws :n :categories}."
  [data md opts]
  (let [{:keys [data stan-data]} (antifoul-stan-data data md)
        chains (stan/sample-chains "stan/antifoul_only.stan" stan-data (stan/mcmc-opts opts))]
    {:chains chains :draws (apply tc/concat chains)
     :n (tc/row-count data) :categories (get-in md [:categories :antifoul])}))

(defn antifoul-contrasts
  "All pairwise category contrasts (i − j, i<j) from the single-predictor fit,
   filtered to |mean| > `min-abs` (default 0.3). Returns a
   row per contrast {:a :b :mean :lo :hi :p-gt :odds}."
  ([fit] (antifoul-contrasts fit 0.3))
  ([{:keys [draws categories]} min-abs]
   (let [k    (count categories)
         cols (index-draws draws "alpha_antifoul" k)]
     (vec (for [i (range k) j (range k)
                :when (< i j)
                :let [c (params/contrast (nth cols i) (nth cols j))]
                :when (> (Math/abs (double (:mean c))) min-abs)]
            (assoc c :a (nth categories i) :b (nth categories j)))))))

(defn antifoul-black-vs-coppercoat
  "Black − Coppercoat contrast from the single-predictor fit:
   {:mean :lo :hi :odds :p-gt}."
  [{:keys [draws]} md]
  (params/category-contrast draws md "alpha_antifoul" :antifoul "Black" "Coppercoat"))

(defn validate-antifoul
  "End-to-end black-antifoul validation. Prints raw
   value-counts, processed per-level rates, the Black-vs-Not-Black and
   Black-vs-Coppercoat χ²/Fisher contingency tests, confound t-tests + a
   sailing-mode-by-antifoul share crosstab, and the single-predictor Bayesian
   contrasts (with convergence diagnostics). Returns a summary map. `opts` flow
   to sampling."
  [& [{:keys [opts]}]]
  (let [raw  (util/read-json (config/cfg :paths :raw))
        {incs :incident unes :uneventful} (util/raw-reports raw)
        {:keys [data metadata]} (prep/prepare raw)
        md      metadata
        levels  (antifoul-levels data md)
        bvnb    (black-vs-not-black data md)
        bvc     (black-vs-coppercoat data md)
        black-i (cat-index md :antifoul "Black")
        idxv    (vec (data :antifoul_idx))
        ;; Black vs Other: nil antifoul_idx lands in Other (only idx == 0 is
        ;; Black, so nil → False). Only the daylight side excludes nil from
        ;; both groups.
        tt      (confound-ttests
                  data
                  [:depth_ord_std :is_daytime :boat_length_ord_std
                   :speed_ord_std :autopilot_on :distance_ord_std]
                  (fn [i] (= black-i (nth idxv i))))
        shares  (category-share
                  data :sailing_mode_idx (get-in md [:categories :sailing_mode])
                  (mapv (fn [[lbl k]]
                          [lbl (fn [i] (= k (nth idxv i)))])
                        (map-indexed (fn [k c] [c k]) (get-in md [:categories :antifoul]))))
        fit     (fit-antifoul data md opts)
        contr   (antifoul-contrasts fit)
        bayes   (antifoul-black-vs-coppercoat fit md)]
    (println "══ ANTIFOUL VALIDATION ══")
    (println "\n█ STEP 1: Raw antifoul_colour value-counts")
    (print-value-counts "INCIDENT" (map :antifoul_colour incs))
    (print-value-counts "UNEVENTFUL" (map :antifoul_colour unes))

    (println "\n█ STEP 2: Processed modeling data — antifoul levels")
    (doseq [{:keys [idx category n incidents uneventful rate]} levels]
      (println (format "  [%d] %-12s n=%4d  inc=%3d  une=%3d  rate=%.1f%%"
                       idx category n incidents uneventful (* 100 rate))))

    (println "\n█ STEP 3: Black vs Not-Black 2×2")
    (let [{:keys [table chi2 fisher]} bvnb
          [[ou oi] [bu bi]] table]
      (println "            uneventful  incident")
      (println (format "  Not Black %10d %9d" ou oi))
      (println (format "  Black     %10d %9d" bu bi))
      (println (format "  χ²=%.2f df=%d p=%.6f" (:chi2 chi2) (:dof chi2) (:p chi2)))
      (println (format "  Fisher OR=%.3f p=%.6f" (:odds-ratio fisher) (:p fisher))))

    (println "\n█ STEP 3b: Black vs Coppercoat 2×2")
    (let [{:keys [table chi2 fisher black-rate copper-rate]} bvc
          [[cu ci] [bu bi]] table]
      (println "             uneventful  incident")
      (println (format "  Coppercoat %10d %9d" cu ci))
      (println (format "  Black      %10d %9d" bu bi))
      (println (format "  χ²=%.2f df=%d p=%.6f" (:chi2 chi2) (:dof chi2) (:p chi2)))
      (println (format "  Fisher OR=%.3f p=%.6f" (:odds-ratio fisher) (:p fisher)))
      (println (format "  Black rate=%.1f%%  Coppercoat rate=%.1f%%"
                       (* 100 black-rate) (* 100 copper-rate))))

    (println "\n█ STEP 4: Confound check — Black vs Other")
    (println "  continuous predictors (t-tests):")
    (doseq [{:keys [predictor mean-true mean-false diff p]} tt]
      (println (format "    %-22s Black=%+.3f Other=%+.3f diff=%+.3f p=%.4f"
                       (name predictor) mean-true mean-false diff p)))
    (println "  sailing-mode share by antifoul colour (nominal — share, not t-test):")
    (doseq [{:keys [label n shares]} shares
            :when (>= n 10)]
      (println (format "    %-12s (n=%3d): %s" label n
                       (str/join ", " (map #(format "%s:%.0f%%"
                                                    (:category %) (:pct %))
                                           shares)))))

    (println "\n█ STEP 6: Single-predictor Bayesian model (antifoul only)")
    (println (format "  fit on N=%d" (:n fit)))
    (print-convergence fit (cons "alpha"
                                 (map #(str "alpha_antifoul." (inc %))
                                      (range (count (:categories fit))))))
    (println "  pairwise contrasts (|mean|>0.3):")
    (doseq [{:keys [a b mean lo hi p-gt odds]} contr]
      (println (format "    %-12s − %-12s %+.3f [%+.3f, %+.3f]  P(%s>%s)=%.1f%%  OR=%.2f"
                       a b mean lo hi a b (* 100 p-gt) odds)))
    (println (format "  Black − Coppercoat: %+.3f [%+.3f, %+.3f]  OR=%.2f  P(B>C)=%.1f%%"
                     (:mean bayes) (:lo bayes) (:hi bayes) (:odds bayes) (* 100 (:p-gt bayes))))
    {:levels levels
     :black-vs-not-black-freq {:chi2 (:chi2 bvnb) :fisher (:fisher bvnb)}
     :black-vs-coppercoat-freq {:chi2 (:chi2 bvc) :fisher (:fisher bvc)
                                :black-rate (:black-rate bvc) :copper-rate (:copper-rate bvc)}
     :confounds tt
     :sailing-shares shares
     :contrasts contr
     :black-vs-coppercoat-bayes bayes}))

;; ── daylight validation ──────────────────────────────────────────────────────

(defn daylight-levels
  "Per-level breakdown of the processed data for is_daytime: a row per level
   {:level :label :n :incidents :uneventful :rate}, Night (0) then Day (1).
   nil is_daytime rows are excluded."
  [data]
  (let [by (util/group-rates data :is_daytime)]
    (mapv (fn [level label]
            (let [{:keys [n incidents uneventful rate]
                   :or   {n 0 incidents 0 uneventful 0 rate 0.0}} (by level)]
              {:level level :label label :n n :incidents incidents
               :uneventful uneventful :rate rate}))
          [0 1] ["Night" "Daytime"])))

(defn daylight-contingency
  "2×2 (Night vs Day) × (uneventful vs incident) on the complete-case data, with
   χ² and Fisher. Returns {:table :chi2 :fisher}.

   Layout (row=is-night, col=is-incident, false→true):
     [[day-uneventful  day-incident]
      [night-uneventful night-incident]]"
  [data]
  (let [d        (tc/drop-missing data [:is_daytime])
        is-night (mapv #(= 0 (long %)) (d :is_daytime))
        is-inc   (mapv #(= 1 (long %)) (d :interaction))
        table    (stats/crosstab-2x2 is-night is-inc)]
    {:table table :chi2 (stats/chi2-contingency table) :fisher (stats/fisher-exact table)}))

(defn- daylight-stan-data
  "Single-predictor stan data for daylight, complete-case on is_daytime."
  [data]
  (let [d (tc/drop-missing data [:is_daytime])]
    {:data d
     :stan-data {:N (tc/row-count d)
                 :y (mapv long (d :interaction))
                 :is_daytime (mapv double (d :is_daytime))}}))

(defn fit-daylight
  "Fit daylight_only.stan and return {:chains :draws :n}."
  [data opts]
  (let [{:keys [data stan-data]} (daylight-stan-data data)
        chains (stan/sample-chains "stan/daylight_only.stan" stan-data (stan/mcmc-opts opts))]
    {:chains chains :draws (apply tc/concat chains) :n (tc/row-count data)}))

(defn daylight-predictions
  "Predicted P(interaction | night) and P(interaction | day) and the risk ratio
   from the single-predictor fit. P_night = sigmoid(alpha),
   P_day = sigmoid(alpha + beta_day). The risk ratio is the posterior MEAN of the
   per-draw ratio p_night/p_day (E[p_n/p_d], not p̄_n/p̄_d), reported with its
   89% ETI. Returns {:p-night {:mean :eti} :p-day {:mean :eti}
   :risk-ratio-mean :risk-ratio-eti}."
  [{:keys [draws]}]
  (let [alpha (vec (draws "alpha"))
        beta  (vec (draws "beta_day"))
        p-n   (mapv util/sigmoid alpha)
        p-d   (mapv util/sigmoid (map + alpha beta))
        rr    (mapv / p-n p-d)]
    {:p-night {:mean (util/mean p-n) :eti (diag/eti p-n)}
     :p-day   {:mean (util/mean p-d) :eti (diag/eti p-d)}
     :risk-ratio-mean (util/mean rr)
     :risk-ratio-eti (diag/eti rr)}))

(defn validate-daylight
  "End-to-end night/day validation. Prints raw daylight
   value-counts, processed per-level rates, the χ²/Fisher contingency tests, the
   night breakdown, confound t-tests (complete-cased on is_daytime) + a
   sailing-mode-by-night/day share crosstab, and the single-predictor Bayesian
   model's predicted P(interaction | night/day) and risk ratio (with convergence
   diagnostics and a science caveat). Returns a summary map. `opts` flow to
   sampling."
  [& [{:keys [opts]}]]
  (let [raw  (util/read-json (config/cfg :paths :raw))
        {incs :incident unes :uneventful} (util/raw-reports raw)
        {:keys [data metadata]} (prep/prepare raw)
        md       metadata
        levels   (daylight-levels data)
        cont     (daylight-contingency data)
        night    (first (filter #(= 0 (:level %)) levels))
        day      (first (filter #(= 1 (:level %)) levels))
        tot-inc  (+ (:incidents night) (:incidents day))
        tot      (+ (:n night) (:n day))
        dayv     (vec (data :is_daytime))
        ;; complete-case on is_daytime: nil rows excluded from BOTH groups
        night?   (fn [i] (let [v (nth dayv i)] (when (some? v) (= 0 (long v)))))
        tt       (confound-ttests
                   data
                   [:depth_ord_std :speed_ord_std :autopilot_on
                    :distance_ord_std :boat_length_ord_std
                    :wind_ord_std :sea_state_ord_std]
                   night?)
        shares   (category-share
                   data :sailing_mode_idx (get-in md [:categories :sailing_mode])
                   [["Night" (fn [i] (= 0 (some-> (nth dayv i) long)))]
                    ["Day" (fn [i] (= 1 (some-> (nth dayv i) long)))]])
        fit      (fit-daylight data opts)
        preds    (daylight-predictions fit)]
    (println "══ NIGHT vs DAY VALIDATION ══")
    (println "\n█ STEP 1: Raw darkness_or_daylight value-counts")
    (print-value-counts "INCIDENT" (map :darkness_or_daylight incs))
    (print-value-counts "UNEVENTFUL" (map :darkness_or_daylight unes))

    (println "\n█ STEP 2: Processed modeling data — is_daytime levels")
    (doseq [{:keys [label level n incidents uneventful rate]} levels]
      (println (format "  %-8s (is_daytime=%d) n=%4d  inc=%3d  une=%3d  rate=%.1f%%"
                       label level n incidents uneventful (* 100 rate))))

    (println "\n█ STEP 3: Statistical tests (Night vs Day)")
    (let [{:keys [table chi2 fisher]} cont
          [[du di] [nu ni]] table]
      (println "          uneventful  incident")
      (println (format "  Daytime %10d %9d" du di))
      (println (format "  Night   %10d %9d" nu ni))
      (println (format "  χ²=%.2f df=%d p=%.3e" (:chi2 chi2) (:dof chi2) (:p chi2)))
      (println (format "  Fisher OR=%.3f p=%.3e" (:odds-ratio fisher) (:p fisher))))

    (println "\n█ STEP 4: Night reports breakdown")
    (println (format "  Night total=%d (inc=%d, une=%d)"
                     (:n night) (:incidents night) (:uneventful night)))
    (println (format "  Day total=%d (inc=%d, une=%d)"
                     (:n day) (:incidents day) (:uneventful day)))
    (println (format "  Night is %.1f%% of reports but %.1f%% of incidents"
                     (pct (:n night) tot) (pct (:incidents night) tot-inc)))

    (println "\n█ STEP 5: Confound check — Night vs Day")
    (println "  continuous predictors (t-tests, complete-cased on is_daytime):")
    (doseq [{:keys [predictor mean-true mean-false diff p]} tt]
      (println (format "    %-22s Night=%+.3f Day=%+.3f diff=%+.3f p=%.4f"
                       (name predictor) mean-true mean-false diff p)))
    (println "  sailing-mode share by night/day (nominal — share, not t-test):")
    (doseq [{:keys [label n shares]} shares]
      (println (format "    %-6s (n=%3d): %s" label n
                       (str/join ", " (map #(format "%s:%.0f%%"
                                                    (:category %) (:pct %))
                                           shares)))))

    (println "\n█ STEP 7: Single-predictor Bayesian model (daylight only)")
    (println (format "  fit on N=%d" (:n fit)))
    (print-convergence fit ["alpha" "beta_day"])
    (println (format "  P(interaction | night): %.1f%% [%.1f%%, %.1f%%]"
                     (* 100 (:mean (:p-night preds)))
                     (* 100 (first (:eti (:p-night preds))))
                     (* 100 (second (:eti (:p-night preds))))))
    (println (format "  P(interaction | day):   %.1f%% [%.1f%%, %.1f%%]"
                     (* 100 (:mean (:p-day preds)))
                     (* 100 (first (:eti (:p-day preds))))
                     (* 100 (second (:eti (:p-day preds))))))
    (println (format "  Risk ratio (posterior mean of per-draw night/day): %.1fx [%.1fx, %.1fx]"
                     (:risk-ratio-mean preds)
                     (first (:risk-ratio-eti preds)) (second (:risk-ratio-eti preds))))
    (println "  CAVEAT: these are sample-conditional isolation checks on the very")
    (println "  is_daytime encoding the night-encoding study disowns, NOT population")
    (println "  risk. Time of day is excluded from primary M3 and reported via the")
    (println "  Poisson rate ratio (orca.timeofday).")
    {:levels levels
     :contingency {:chi2 (:chi2 cont) :fisher (:fisher cont)}
     :confounds tt
     :sailing-shares shares
     :predictions preds}))
