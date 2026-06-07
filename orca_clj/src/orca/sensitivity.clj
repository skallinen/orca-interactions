(ns orca.sensitivity
  "Prior sensitivity analysis.

   Question (methodology §4, Priors): do our conclusions depend on the
   Fermi-estimated base-rate intercept prior? Fit M3 under four intercept priors
   and check the blog's claim that the SLOPE coefficients (relative risk factors)
   are robust while only the intercept (absolute risk) shifts.

   Four priors, spanning the blog's stated \"Normal(-4.5,0.6) … Normal(0,1.5)\":
     Conservative N(-4.5,0.6) ~1% base    Central N(-3.5,0.6) ~3% (default)
     Aggressive   N(-2.9,0.6) ~5% base    Flat    N(0,1.5)   uninformative

   Time of day is not in the regression (methodology §7 handles it as a separate
   exposure-based rate ratio), so the sensitivity sweep varies the intercept
   prior on the NO-daylight M3 (stan/m3_prior.stan, alpha_mu/alpha_sd supplied as
   data) — identical predictors to m3_build, no beta_daylight in the slope
   comparison.

   Saves sensitivity_intercept.png (intercept posterior per prior) and
   sensitivity_slopes.png (slope ETIs per prior — overlapping ⇒ robust)."
  (:require
   [orca.config :as config]
   [orca.diagnostics :as diag]
   [orca.models :as models]
   [orca.params :as params]
   [orca.plot :as plot]
   [orca.prepare :as prep]
   [orca.stan :as stan]
   [orca.util :as util]
   [tablecloth.api :as tc]))

(def priors
  "The four intercept-prior scenarios (methodology §4)."
  [{:name "Conservative" :note "~1% base" :mu -4.5 :sd 0.6}
   {:name "Central" :note "~3% base" :mu -3.5 :sd 0.6}
   {:name "Aggressive" :note "~5% base" :mu -2.9 :sd 0.6}
   {:name "Flat" :note "uninformative" :mu 0.0 :sd 1.5}])

(def key-betas
  "Slopes whose robustness across priors is reported (no beta_daylight — removed
   from M3 per blog §7)."
  ["beta_depth" "beta_autopilot" "beta_speed" "beta_length"
   "beta_distance" "beta_wind" "beta_sea"])

;; ── helpers ──────────────────────────────────────────────────────────────────

(def ^:private sigmoid util/sigmoid)

(defn- summ
  "Mean + 89% ETI of a draw seq."
  [xs]
  (let [[lo hi] (diag/eti xs)] {:mean (util/mean xs) :lo lo :hi hi}))

(def ^:private cat-index params/cat-index)
(def ^:private cat-col params/cat-col)

(defn fit-prior
  "Fit the no-daylight M3 (stan/m3_prior.stan) with intercept prior N(mu, sd).
   Returns {:draws :chains :n}."
  [data md {:keys [mu sd]} opts]
  (let [d     (models/complete-cases :m3 data)
        sdata (assoc (models/stan-data :m3 d md) :alpha_mu mu :alpha_sd sd)
        chains (stan/sample-chains "stan/m3_prior.stan" sdata opts)]
    {:draws (apply tc/concat chains) :chains chains :n (tc/row-count d)}))

;; ── printing ─────────────────────────────────────────────────────────────────

(defn- print-intercepts [fits]
  (println "\n══ Intercept (absolute base rate) by prior ══")
  (doseq [{nm :name :keys [mu sd draws]} fits]
    (let [a (vec (draws "alpha"))
          p (mapv sigmoid a)
          sa (summ a) sp (summ p)]
      (println (format "  %-12s N(%+.1f,%.1f): α=%+.3f [%+.3f, %+.3f]  P=%.1f%% [%.1f%%, %.1f%%]"
                       nm mu sd (:mean sa) (:lo sa) (:hi sa)
                       (* 100 (:mean sp)) (* 100 (:lo sp)) (* 100 (:hi sp)))))))

(defn- print-slopes [fits]
  (println "\n══ Slope robustness across priors (overlap ⇒ robust) ══")
  (doseq [beta key-betas]
    (println (str "  " beta ":"))
    (doseq [{nm :name :keys [draws]} fits]
      (let [{:keys [mean lo hi]} (summ (vec (draws beta)))]
        (println (format "    %-12s %+.3f [%+.3f, %+.3f] OR=%.2f"
                         nm mean lo hi (Math/exp mean)))))))

(defn- print-category-effects [fits md]
  (println "\n══ Category effects across priors ══")
  (doseq [[label family cat-key lvl] [["Motoring" "alpha_sailing" :sailing_mode "Motoring"]
                                      ["Black antifoul" "alpha_antifoul" :antifoul "Black"]]]
    (println (str "  " label ":"))
    (doseq [{nm :name :keys [draws]} fits]
      (let [{:keys [mean lo hi]} (summ (cat-col draws family (cat-index md cat-key lvl)))]
        (println (format "    %-12s %+.3f [%+.3f, %+.3f] OR=%.2f"
                         nm mean lo hi (Math/exp mean))))))
  (println "  Black vs Coppercoat contrast:")
  (doseq [{nm :name :keys [draws]} fits]
    (let [{:keys [mean lo hi odds p-gt]}
          (params/category-contrast draws md "alpha_antifoul" :antifoul "Black" "Coppercoat")]
      (println (format "    %-12s %+.3f [%+.3f, %+.3f] OR=%.2f P(Black>Copper)=%.1f%%"
                       nm mean lo hi odds (* 100 p-gt))))))

;; ── plots ────────────────────────────────────────────────────────────────────

(defn intercept-plot
  "Box plot of the intercept posterior (logit scale) for each prior."
  [fits png]
  (plot/box png
            (mapv (fn [{nm :name :keys [draws]}] [nm (vec (draws "alpha"))]) fits)
            {:title "Prior sensitivity — intercept posterior" :y-label "α (logit)"}))

(defn slope-plot
  "Forest of each key slope's 89% ETI under every prior (rows grouped by slope,
   one whisker per prior). Overlapping whiskers ⇒ robust to prior choice."
  [fits md png]
  (let [params (concat (mapv (fn [b] [b (fn [draws] (vec (draws b)))]) key-betas)
                       [["Motoring" (fn [draws]
                                      (cat-col draws "alpha_sailing"
                                               (cat-index md :sailing_mode "Motoring")))]
                        ["Black antifoul" (fn [draws]
                                            (cat-col draws "alpha_antifoul"
                                                     (cat-index md :antifoul "Black")))]])
        rows (vec (for [[label getter] params
                        {nm :name :keys [draws]} fits]
                    (let [{:keys [mean lo hi]} (summ (getter draws))]
                      {:label (str label " · " nm) :mean mean :lo lo :hi hi})))]
    (plot/forest png rows {:title "Prior sensitivity — slope ETIs by prior"
                           :x-label "log-odds per 1 SD"})))

;; ── orchestration ────────────────────────────────────────────────────────────

(defn run
  "Fit M3 under the four intercept priors, print the intercept / slope / category
   comparisons, and (unless `:plots?` false) save sensitivity_intercept.png and
   sensitivity_slopes.png. Returns a summary map. `opts` flow to sampling."
  [& [{:keys [opts plots?] :or {plots? true}}]]
  (let [opts (stan/mcmc-opts opts)
        {:keys [data metadata]} (prep/prepare (util/read-json (config/cfg :paths :raw)))
        md   metadata
        fits (mapv (fn [p] (merge p (fit-prior data md p opts))) priors)]
    (println (format "Complete cases (M3): %d" (:n (first fits))))
    (print-intercepts fits)
    (print-slopes fits)
    (print-category-effects fits md)
    (when plots?
      (println "\n══ Plots ══")
      (intercept-plot fits (models/results-path "sensitivity_intercept.png"))
      (slope-plot fits md (models/results-path "sensitivity_slopes.png"))
      (println "  saved sensitivity_intercept.png, sensitivity_slopes.png"))
    {:intercepts (mapv (fn [{nm :name :keys [mu sd draws]}]
                         (let [a (vec (draws "alpha"))]
                           {:name nm :mu mu :sd sd
                            :alpha (summ a) :implied-p (summ (mapv sigmoid a))}))
                       fits)
     :slopes (into {} (for [beta key-betas]
                        [beta (mapv (fn [{nm :name :keys [draws]}]
                                      (assoc (summ (vec (draws beta))) :prior nm))
                                    fits)]))}))
