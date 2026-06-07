(ns orca.core
  "End-to-end runner: reproduce the three pieces of the Bayesian orca analysis in
   the JVM/Clojure + CmdStan stack and validate each against the committed
   reference artifacts. Run via `clojure -M -m orca.core` or `(orca.core/run-all)`."
  (:require
   [orca.model :as model]
   [orca.timeofday :as tod]
   [orca.validate :as v]))

(defn- line [c] (apply str (repeat 72 c)))

(defn run-all
  "Run data prep, M3 fit and the time-of-day rate model; print a report and
   return a summary map."
  []
  (println (line "="))
  (println "  Orca interaction analysis — Clojure/CmdStan reproduction")
  (println (line "="))

  ;; 1. Data preparation
  (println "\n[1/3] Data preparation (tablecloth) vs modeling_data.csv ...")
  (let [prep (v/validate-prep {})]
    (println "      data-prep match:" (:pass? prep)
             "—" (-> prep :details (select-keys [:row-order :categories :standardization]))))

  ;; 2. M3 logistic regression
  (println "\n[2/3] M3 logistic regression (CmdStan NUTS, 4x2000) ...")
  (model/run {})
  (let [post (v/validate-posterior {})]
    (println (format "      posterior match: %s  (max |Δmean|=%.3f, max rel=%.3f vs reference)"
                     (:pass? post) (:max-dmean post) (:max-rel post))))

  ;; 3. Time-of-day exposure rate model
  (println "\n[3/3] Time-of-day Poisson rate model (commons-suncalc + CmdStan) ...")
  (let [{:keys [data rate-ratio]} (tod/fit {})
        {:keys [median ci89 p-night-lower]} rate-ratio
        ok (and (< 0.43 median 0.72) (> p-night-lower 0.99))]
    (println "      incidents:" (select-keys data [:y_night :y_day])
             " exposure(yacht-hrs):" (select-keys data [:T_night :T_day]))
    (println (format "      rate ratio (night/day): %.3f  89%% CI [%.3f, %.3f]  P(night lower)=%.2f"
                     median (first ci89) (second ci89) p-night-lower))
    (println (format "      vs published 0.56 [0.43, 0.72], P~1.0  ->  %s" ok))
    (println "\n" (line "="))
    {:rate-ratio rate-ratio}))

(defn -main [& _] (run-all) (shutdown-agents))
