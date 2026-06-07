(ns orca.core
  "End-to-end runner for the Bayesian orca-interaction analysis on the
   JVM/Clojure + CmdStan stack, validating each piece against the committed
   reference artifacts.

   Two entry points:
     - `run-all`        — the three headline pieces (data prep, the no-daylight
                          M3 calculator model, and the time-of-day rate model),
                          each validated. Fast-ish; the original reproduction.
     - `run-everything` — the whole analysis in one call: data prep, the M0–M4
                          ladder + WAIC, results, sensitivity, the antifoul /
                          daylight finding validations, the encoding studies, the
                          causal DAG and the EDA tables. Long-running (many models
                          × 4 chains). Each stage is a stand-alone fn so a caller
                          can run sub-parts.

   Run via `clojure -M -m orca.core` (= `run-everything`) or call the fns from a
   REPL. The namespaces it wires together stay independently runnable."
  (:require
   [orca.dag :as dag]
   [orca.eda :as eda]
   [orca.encoding :as encoding]
   [orca.findings :as findings]
   [orca.model :as model]
   [orca.models :as models]
   [orca.results :as results]
   [orca.sensitivity :as sensitivity]
   [orca.timeofday :as tod]
   [orca.validate :as v]))

(defn- line [c] (apply str (repeat 72 c)))

(defn- header
  "Print a banner section header."
  [title]
  (println)
  (println (line "="))
  (println " " title)
  (println (line "=")))

(defn- subheader [title]
  (println (str "\n── " title " ──")))

;; ── individual stages (each independently runnable) ──────────────────────────

(defn stage-prep
  "Data preparation + row-for-row validation vs modeling_data.csv. Returns the
   validate-prep result map."
  []
  (subheader "Data preparation (tablecloth) vs modeling_data.csv")
  (let [prep (v/validate-prep {})]
    (println "  data-prep match:" (:pass? prep)
             "—" (-> prep :details
                     (select-keys [:row-order :categories :standardization])))
    prep))

(defn stage-m3-calculator
  "Fit + validate the no-daylight M3 (the live calculator model) against the
   committed posterior_draws.json. Returns the validate-posterior result."
  [opts]
  (subheader "M3 calculator model (no daylight, relaxed priors) vs oracle")
  (model/run (if opts {:opts opts} {}))
  (let [post (v/validate-posterior {})]
    (println (format "  posterior match: %s  (max |Δmean|=%.3f, max rel=%.3f)"
                     (:pass? post) (:max-dmean post) (:max-rel post)))
    post))

(defn stage-timeofday
  "Time-of-day exposure-based Poisson rate model (commons-suncalc + CmdStan).
   Returns the rate-ratio summary; checks it against the published 0.56."
  [opts]
  (subheader "Time-of-day Poisson rate model (commons-suncalc + CmdStan)")
  (let [{:keys [data rate-ratio]} (tod/fit (if opts {:opts opts} {}))
        {:keys [median ci89 p-night-lower]} rate-ratio
        ok (and (< 0.43 median 0.72) (> p-night-lower 0.99))]
    (println "  incidents:" (select-keys data [:y_night :y_day])
             " exposure(yacht-hrs):" (select-keys data [:T_night :T_day]))
    (println (format "  rate ratio (night/day): %.3f  89%% CI [%.3f, %.3f]  P(night lower)=%.2f"
                     median (first ci89) (second ci89) p-night-lower))
    (println (format "  vs published 0.56 [0.43, 0.72], P~1.0  ->  %s" ok))
    (println)
    (tod/interaction-report (if opts {:opts opts} {}))
    rate-ratio))

;; ── the original three-piece reproduction ────────────────────────────────────

(defn run-all
  "The three headline pieces — data prep, the no-daylight M3 calculator model,
   and the time-of-day rate model — each validated against the committed oracle.
   Returns a summary map. `opts` flow to sampling (default config :mcmc)."
  [& [{:keys [opts]}]]
  (header "Orca interaction analysis — headline reproduction")
  (let [prep (stage-prep)
        post (stage-m3-calculator opts)
        rr   (stage-timeofday opts)]
    (println "\n" (line "="))
    {:prep prep :posterior post :rate-ratio rr}))

;; ── the full pipeline ─────────────────────────────────────────────────────────

(defn run-everything
  "Run the entire analysis in one call, mirroring the script pipeline:
   data prep + validate, the M0–M4 ladder + WAIC compare (orca.models),
   interpretation/results (orca.results), prior sensitivity (orca.sensitivity),
   the antifoul + daylight finding validations (orca.findings), the time/night
   encoding studies (orca.encoding), the causal DAG (orca.dag), the EDA
   distribution tables (orca.eda), and the time-of-day rate model
   (orca.timeofday). Long-running. Returns a map of each stage's result.

   `opts` flow to every sampling call (default config :mcmc). `:plots?` (default
   true) toggles PNG output in the plot-producing stages."
  [& [{:keys [opts plots?] :or {plots? true}}]]
  (header "Orca interaction analysis — full pipeline (Clojure/CmdStan)")

  (let [prep (stage-prep)]

    (header "Model ladder M0–M4 + WAIC comparison (orca.models)")
    (let [ladder (models/run-all (when opts {:opts opts}))]

      (header "Results: contrasts, risk scenarios, plots (orca.results)")
      (let [res (results/run {:opts opts :plots? plots?})]

        (header "Prior sensitivity analysis (orca.sensitivity)")
        (let [sens (sensitivity/run {:opts opts :plots? plots?})]

          (header "Finding validation: black antifoul (orca.findings)")
          (let [antifoul (findings/validate-antifoul {:opts opts})]

            (header "Finding validation: night/day (orca.findings)")
            (let [daylight (findings/validate-daylight {:opts opts})]

              (header "Time-of-day rate model (orca.timeofday)")
              (let [rr (stage-timeofday opts)]

                (header "Time/night encoding studies (orca.encoding)")
                (let [enc-explore (encoding/explore-time-encoding)
                      enc-night   (encoding/test-night-encoding {:opts opts})
                      enc-solar   (encoding/solar-encoding)]

                  (header "Causal DAG (orca.dag)")
                  (dag/print-dag)

                  (header "Exploratory distribution tables (orca.eda)")
                  (let [eda (eda/run {:plots? plots?})]

                    (println "\n" (line "="))
                    (println "  Full pipeline complete.")
                    (println (line "="))
                    {:prep prep
                     :ladder ladder
                     :results res
                     :sensitivity sens
                     :antifoul antifoul
                     :daylight daylight
                     :rate-ratio rr
                     :encoding {:explore enc-explore
                                :night enc-night
                                :solar enc-solar}
                     :eda eda}))))))))))

(defn -main [& _] (run-everything) (shutdown-agents))
