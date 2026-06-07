(ns orca.results
  "Interpretation & results layer.

   Re-fits M3 and M4 and prints a model comparison, slope and category-offset
   tables (mean, 89% ETI, odds ratio, *** when the ETI excludes 0), risk
   scenarios, key contrasts, and saves three PNGs (coefficient_forest,
   antifoul_effects, sailing_mode_effects).

   Two method points (the two blog posts are the source of truth):
   - Time of day is NOT in the regression: M3 is the 30-param no-daylight model
     (m3_build) and the night question is answered separately by the exposure-based
     Poisson rate ratio, 0.56 [0.43, 0.72] (orca.timeofday). There is no
     Night-vs-Day contrast in these coefficient tables.
   - Model comparison uses WAIC (orca.waic), an intentional substitution for
     PSIS-LOO recorded in porting.md §6.3. The valid (shared-N) WAIC of M3 vs
     M4 is delegated to orca.models/compare-m3-m4.

   The coefficient tables/plots use M3 fit on its own complete cases (N≈605, the
   blog's primary-model N); M4 is fit on its own complete cases for its table. A
   full `run` therefore performs four MCMC fits (M3, M4, and the two inside
   compare-m3-m4 on the shared M4 complete-case set)."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [orca.diagnostics :as diag]
   [orca.models :as models]
   [orca.params :as params]
   [orca.prepare :as prep]
   [orca.stan :as stan]
   [orca.util :as util]))

;; ── small helpers ────────────────────────────────────────────────────────────

(defn- credible?
  "An effect is credible when its 89% ETI excludes zero."
  [lo hi]
  (or (pos? lo) (neg? hi)))

;; ── coefficient & category tables ────────────────────────────────────────────

(defn slope-rows
  "Slope (beta_*) rows for a fit: {:param :mean :lo :hi :odds :sig}, sorted by name."
  [ft]
  (->> (models/summary ft)
       (filter #(str/starts-with? (:param %) "beta_"))
       (sort-by :param)
       (mapv (fn [{:keys [param mean eti-lo eti-hi]}]
               {:param param :mean mean :lo eti-lo :hi eti-hi
                :odds (Math/exp mean) :sig (credible? eti-lo eti-hi)}))))

(defn category-rows
  "Category-offset rows for index `family` (e.g. \"alpha_antifoul\") labelled from
   metadata `cat-key`: {:category :mean :lo :hi :odds :sig}, in category order."
  [ft md family cat-key]
  (let [cats (get-in md [:categories cat-key])]
    (->> (models/summary ft)
         (filter #(str/starts-with? (:param %) (str family ".")))
         (sort-by #(parse-long (subs (:param %) (inc (count family)))))
         (map-indexed (fn [i {:keys [mean eti-lo eti-hi]}]
                        {:category (nth cats i) :mean mean :lo eti-lo :hi eti-hi
                         :odds (Math/exp mean) :sig (credible? eti-lo eti-hi)}))
         vec)))

;; ── contrasts ────────────────────────────────────────────────────────────────

(def category-contrast
  "Posterior contrast (category `a` − category `b`) within index `family`/`cat-key`:
   {:contrast :mean :lo :hi :odds :p-gt} where :p-gt = P(a > b)."
  params/category-contrast)

(defn slope-effect
  "Single-slope effect summary: {:param :mean :lo :hi :odds :p-neg} where :p-neg =
   P(effect < 0) (e.g. P(autopilot reduces risk))."
  [draws beta]
  (let [v (vec (draws beta))
        [lo hi] (diag/eti v)
        m  (util/mean v)]
    {:param beta :mean m :lo lo :hi hi :odds (Math/exp m)
     :p-neg (/ (double (count (filter neg? v))) (count v))}))

;; ── printing ─────────────────────────────────────────────────────────────────

(defn- print-slopes [title rows]
  (println (str "\n  " title " — slopes (log-odds per 1 SD):"))
  (doseq [{:keys [param mean lo hi odds sig]} rows]
    (println (format "    %-18s %+.3f [%+.3f, %+.3f]  OR=%.2f  %s%s"
                     param mean lo hi odds (if (pos? mean) "↑risk" "↓risk")
                     (if sig " ***" "")))))

(defn- print-categories [cat-key rows]
  (println (str "\n  " (name cat-key) " offsets:"))
  (doseq [{:keys [category mean lo hi odds sig]} rows]
    (println (format "    %-14s %+.3f [%+.3f, %+.3f]  OR=%.2f%s"
                     category mean lo hi odds (if sig " ***" "")))))

;; ── orchestration ────────────────────────────────────────────────────────────

(defn run
  "Re-fit M3 (no-daylight m3_build) and M4, print the WAIC comparison, coefficient
   and category tables, risk scenarios and key contrasts, and (unless `:plots?`
   false) save coefficient_forest.png / antifoul_effects.png /
   sailing_mode_effects.png. Returns a summary map. `opts` flow to sampling."
  [& [{:keys [opts plots?] :or {plots? true}}]]
  (let [opts (stan/mcmc-opts opts)
        {:keys [data metadata]} (prep/prepare (util/read-json (config/cfg :paths :raw)))
        md   metadata
        f3   (models/fit :m3 data md opts)
        f4   (models/fit :m4 data md opts)
        d3   (:draws f3)]
    (println "\n══ Model comparison (WAIC) ══")
    (let [cmp (models/compare-m3-m4 data md opts)]
      (doseq [{nm :name :keys [elpd-waic waic d-elpd d-se se n-p-warn]} cmp]
        (println (format "  %-3s elpd=%.2f waic=%.1f Δelpd=%.2f d_se=%.2f se=%.2f p_waic>0.4: %d"
                         nm elpd-waic waic d-elpd d-se se (or n-p-warn 0))))

      (println (format "\n══ M3 (primary, no daylight) — N=%d ══" (:n f3)))
      (let [s3 (slope-rows f3)]
        (print-slopes "M3" s3)
        (doseq [[family cat-key] [["alpha_rudder" :rudder] ["alpha_antifoul" :antifoul]
                                  ["alpha_hull" :hull_colour] ["alpha_sailing" :sailing_mode]]]
          (print-categories cat-key (category-rows f3 md family cat-key)))

        (println (format "\n══ M4 (extended) — N=%d ══" (:n f4)))
        (print-slopes "M4" (slope-rows f4))
        (doseq [[family cat-key] [["alpha_antifoul" :antifoul] ["alpha_sailing" :sailing_mode]]]
          (print-categories cat-key (category-rows f4 md family cat-key)))

        (println "\n══ Risk scenarios (M3) ══")
        (println "  (categorical offsets marginalized at 0 — absolute P is meaningful")
        (println "   only RELATIVE across scenarios; named-config risk is the calculator's job)")
        (doseq [{:keys [scenario mean median eti]} (models/risk-scenarios f3 md)]
          (println (format "  %s\n    P=%.1f%% median=%.1f%% 89%%ETI[%.1f%%, %.1f%%]"
                           scenario (* 100 mean) (* 100 median)
                           (* 100 (first eti)) (* 100 (second eti)))))

        (println "\n══ Key contrasts (M3) ══")
        (let [bvc (category-contrast d3 md "alpha_antifoul" :antifoul "Black" "Coppercoat")
              mvs (category-contrast d3 md "alpha_sailing" :sailing_mode "Motoring" "Sailing")
              ap  (slope-effect d3 "beta_autopilot")
              dep (slope-effect d3 "beta_depth")]
          (println (format "  Black vs Coppercoat: %+.3f [%+.3f,%+.3f] OR=%.2f P(B>C)=%.1f%%"
                           (:mean bvc) (:lo bvc) (:hi bvc) (:odds bvc) (* 100 (:p-gt bvc))))
          (println (format "  Motoring vs Sailing: %+.3f [%+.3f,%+.3f] OR=%.2f P(M>S)=%.1f%%"
                           (:mean mvs) (:lo mvs) (:hi mvs) (:odds mvs) (* 100 (:p-gt mvs))))
          (println (format "  Autopilot:           %+.3f [%+.3f,%+.3f] OR=%.2f P(<0)=%.1f%%"
                           (:mean ap) (:lo ap) (:hi ap) (:odds ap) (* 100 (:p-neg ap))))
          (println (format "  Depth (per 1 SD):    %+.3f [%+.3f,%+.3f] OR=%.2f"
                           (:mean dep) (:lo dep) (:hi dep) (:odds dep)))
          (println "  Night vs Day:        n/a — removed from M3 per blog §7;")
          (println "                       see orca.timeofday rate ratio 0.56 [0.43, 0.72].")

          (when plots?
            (println "\n══ Plots ══")
            (models/coefficient-forest f3 (models/results-path "coefficient_forest.png"))
            (models/index-forest f3 md "alpha_antifoul" :antifoul
                                 (models/results-path "antifoul_effects.png"))
            (models/index-forest f3 md "alpha_sailing" :sailing_mode
                                 (models/results-path "sailing_mode_effects.png"))
            (println "  saved coefficient_forest, antifoul_effects, sailing_mode_effects PNGs"))

          {:n {:m3 (:n f3) :m4 (:n f4)}
           :comparison cmp
           :slopes {:m3 s3 :m4 (slope-rows f4)}
           :scenarios (models/risk-scenarios f3 md)
           :contrasts {:black-vs-coppercoat bvc :motoring-vs-sailing mvs
                       :autopilot ap :depth dep}})))))
