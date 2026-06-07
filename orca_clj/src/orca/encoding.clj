(ns orca.encoding
  "Time-of-day / night encoding studies.

   These are the *justification* for removing time of day from the primary
   regression (methodology §4, §7). The core problem: incident reports record a
   single time period (Day/Night/Dawn/Dusk) while uneventful passages record
   every period they cover (e.g. \"Dawn , Day , Dusk , Night\"). That asymmetry
   means no binary day/night encoding is well posed, and the apparent night
   effect is sensitive to which encoding you pick. We show the range of results
   across reasonable encodings and let the reader judge; the night effect is then
   reported separately via the exposure-based Poisson rate ratio (orca.timeofday).

   Three studies:
   - `explore-time-encoding` — four binary encodings (A multi-period→day,
     B contains-Night→night, C split dawn→day/dusk→night, D split
     dawn→night/dusk→day) and their interaction rates.
   - `test-night-encoding` — refit the with-daylight model-building M3
     (m3_daylight.stan) under encodings A and B and compare beta_daylight.
   - `solar-encoding` — reclassify each report by actual solar position
     (orca.timeofday) and compare with the reported period.

   All read the raw reports JSON (orca.config :paths :raw)."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [orca.diagnostics :as diag]
   [orca.models :as models]
   [orca.prepare :as prep]
   [orca.stan :as stan]
   [orca.timeofday :as tod]
   [orca.util :as util]
   [tablecloth.api :as tc]))

;; ── shared helpers ───────────────────────────────────────────────────────────

(def ^:private raw-reports
  "Raw reports split into {:incident [...] :uneventful [...]} by report_type."
  util/raw-reports)

(defn- periods
  "Comma-split a darkness_or_daylight string into trimmed period tokens, or []."
  [s]
  (if (and s (not (str/blank? (str s))))
    (mapv str/trim (str/split (str s) #","))
    []))

(defn- rate-row
  "Build a {:label :n :incidents :uneventful :rate} row from binary-day values
   `days` (0/1/nil) and interaction flags `inters`, for a given level."
  [label level days inters]
  (let [idxs (filter #(= level (nth days %)) (range (count days)))
        n    (count idxs)
        ninc (count (filter #(= 1 (nth inters %)) idxs))]
    {:label label :n n :incidents ninc :uneventful (- n ninc)
     :rate (if (pos? n) (/ (double ninc) n) 0.0)}))

;; ── explore-time-encoding ─────────────────────────────────────────────────────

(def period->binary
  "Split encoding C: Dawn→day (light increasing), Dusk→night (light decreasing)."
  {"Day" 1 "Night" 0 "Dawn" 1 "Dusk" 0})

(def period->binary-alt
  "Split encoding D: Dawn→night, Dusk→day (the alternative split)."
  {"Day" 1 "Night" 0 "Dawn" 0 "Dusk" 1})

(defn split-windows
  "Expand reports into exposure windows under a period→binary `pmap`. Incidents
   contribute one window (their single period); uneventful passages contribute
   one window per distinct binary value among their periods. Returns a seq of
   {:is-daytime 0/1/nil :interaction 0/1}."
  [{incs :incident unes :uneventful} period-map]
  (concat
    (for [r incs]
      {:interaction 1
       :is-daytime (let [ps (periods (:darkness_or_daylight r))]
                     (when (= 1 (count ps)) (get period-map (first ps))))})
    (mapcat
      (fn [r]
        (let [ps   (periods (:darkness_or_daylight r))
              bins (->> ps (keep period-map) distinct sort)]
          (if (seq bins)
            (for [b bins] {:interaction 0 :is-daytime b})
            [{:interaction 0 :is-daytime nil}])))
      unes)))

(defn- window-rates
  "Night/Day rate rows from a seq of {:is-daytime :interaction} windows."
  [windows]
  (let [days   (mapv :is-daytime windows)
        inters (mapv :interaction windows)]
    {:night (rate-row "Night" 0 days inters)
     :day   (rate-row "Day" 1 days inters)}))

(defn encoding-rates
  "Compute the four encodings' night/day rate rows:
   A (modeling is_daytime, multi-period→day), B (contains Night→night),
   C (split dawn→day/dusk→night), D (split dawn→night/dusk→day). Returns
   {:a {...} :b {...} :c {...} :d {...}} each {:night row :day row}."
  [raw]
  (let [{:keys [data]} (prep/prepare raw)
        ;; A: the modeling is_daytime (harmonize-daylight: contains "day" → 1)
        days-a   (vec (data :is_daytime))
        inters-a (vec (data :interaction))
        ;; B: contains "Night" → 0 else (present → 1) on the combined raw reports
        {incs :incident unes :uneventful} (raw-reports raw)
        combined (concat incs unes)
        days-b   (mapv (fn [r]
                         (let [s (:darkness_or_daylight r)]
                           (cond (or (nil? s) (str/blank? (str s))) nil
                                 (str/includes? (str s) "Night") 0
                                 :else 1)))
                       combined)
        inters-b (mapv #(if (= "incident" (:report_type %)) 1 0) combined)]
    {:a {:night (rate-row "Night" 0 days-a inters-a)
         :day   (rate-row "Day" 1 days-a inters-a)}
     :b {:night (rate-row "Night" 0 days-b inters-b)
         :day   (rate-row "Day" 1 days-b inters-b)}
     :c (window-rates (split-windows (raw-reports raw) period->binary))
     :d (window-rates (split-windows (raw-reports raw) period->binary-alt))}))

(defn- print-encoding-row [label {:keys [night day]}]
  (println (format "  %-32s Night n=%4d inc=%3d rate=%5.1f%%   Day n=%4d inc=%3d rate=%5.1f%%"
                   label (:n night) (:incidents night) (* 100 (:rate night))
                   (:n day) (:incidents day) (* 100 (:rate day)))))

(defn explore-time-encoding
  "Print the four binary time-of-day encodings and their interaction rates,
   plus the raw period distributions. Returns the `encoding-rates` map."
  [& _]
  (let [raw  (util/read-json (config/cfg :paths :raw))
        {incs :incident unes :uneventful} (raw-reports raw)]
    (println "══ TIME-OF-DAY DATA EXPLORATION ══")
    (println "\n█ INCIDENTS — darkness_or_daylight value-counts:")
    (doseq [[v c] (sort-by (comp - val) (frequencies (map :darkness_or_daylight incs)))]
      (println (format "    %-30s %4d" (str v) c)))
    (println "\n█ UNEVENTFUL — number of periods per passage:")
    (doseq [[np c] (sort (frequencies (map #(count (periods (:darkness_or_daylight %))) unes)))]
      (println (format "    %d periods: %4d" np c)))
    (println "\n█ COMPARISON OF ENCODINGS (Night/Day interaction rates):")
    (let [{:keys [a b c d]} (encoding-rates raw)]
      (print-encoding-row "A: multi-period→day" a)
      (print-encoding-row "B: contains Night→night" b)
      (print-encoding-row "C: split dawn→day,dusk→night" c)
      (print-encoding-row "D: split dawn→night,dusk→day" d)
      (println "\n  Note: split encodings (C/D) pseudo-replicate multi-period passages,")
      (println "  inflating N and violating independence. The point-in-time vs time-span")
      (println "  asymmetry between incident and uneventful reports cannot be fully fixed")
      (println "  by any encoding — hence time of day is reported separately (orca.timeofday).")
      {:a a :b b :c c :d d})))

;; ── test-night-encoding ───────────────────────────────────────────────────────

(defn night-encoding-column
  "Per-report binary is_daytime under a night-test encoding (over combined raw
   reports, incidents then uneventful):
     :a — exact value in {Night, Dawn} → 0, else (present) → 1  (the test's
          encoding A, which differs from prepare's harmonize-daylight);
     :b — contains \"Night\" → 0, else (present) → 1.
   nil for missing. Returns a vector aligned with (concat incidents uneventful)."
  [raw encoding]
  (let [{incs :incident unes :uneventful} (raw-reports raw)]
    (mapv (fn [r]
            (let [s (:darkness_or_daylight r)]
              (cond
                (or (nil? s) (str/blank? (str s))) nil
                (= :b encoding) (if (str/includes? (str s) "Night") 0 1)
                :else (if (contains? #{"Night" "Dawn"} (str/trim (str s))) 0 1))))
          (concat incs unes))))

(def ^:private m3d-required
  "Predictors that must be complete for the with-daylight M3 fit — the ladder
   M3's required set (orca.models), so this fit can't drift from M3."
  (models/required :m3))

(defn- m3d-stan-data
  "Build m3_daylight.stan data from a complete-case dataset `d` + metadata `md`,
   using `daytime` (vector aligned with d's rows) as the is_daytime predictor.
   This is exactly the ladder M3 data (orca.models/stan-data :m3) plus is_daytime,
   so the with-daylight encoding model can't drift from the ladder's M3."
  [d md daytime]
  (assoc (models/stan-data :m3 d md) :is_daytime (mapv double daytime)))

(defn fit-night-encoding
  "Fit the with-daylight M3 (m3_daylight.stan) under night-test `encoding`
   (:a or :b). The is_daytime column is recomputed from the raw reports under the
   encoding and attached to the prepared rows before complete-casing on the M3
   predictors AND is_daytime. Returns {:encoding :chains :draws :n}."
  [raw md encoding opts]
  (let [{:keys [data]} (prep/prepare raw)
        col  (night-encoding-column raw encoding)
        data (tc/add-column data :is_daytime_enc col)
        d    (tc/drop-missing data (conj m3d-required :is_daytime_enc))
        sd   (m3d-stan-data d md (vec (d :is_daytime_enc)))
        chains (stan/sample-chains "stan/m3_daylight.stan" sd (stan/mcmc-opts opts))]
    {:encoding encoding :chains chains :draws (apply tc/concat chains)
     :n (tc/row-count d)}))

(defn- beta-daylight-summary [{:keys [chains]}]
  (let [s (diag/summarize (mapv #(vec (% "beta_daylight")) chains))]
    {:mean (:mean s) :sd (:sd s) :lo (:eti-lo s) :hi (:eti-hi s)
     :rhat (:rhat s) :ess-bulk (:ess-bulk s) :ess-tail (:ess-tail s)}))

(defn test-night-encoding
  "Refit the with-daylight model-building M3 under night-test encodings A and B
   and compare beta_daylight (negative = daylight protective, i.e. night riskier)
   and the implied night/day odds ratio exp(-beta). Prints the comparison and
   returns {:a summary :b summary}. `opts` flow to sampling."
  [& [{:keys [opts]}]]
  (let [raw (util/read-json (config/cfg :paths :raw))
        {:keys [metadata]} (prep/prepare raw)
        fa  (fit-night-encoding raw metadata :a opts)
        fb  (fit-night-encoding raw metadata :b opts)
        sa  (beta-daylight-summary fa)
        sb  (beta-daylight-summary fb)]
    (println "══ ENCODING COMPARISON: beta_daylight ══")
    (println "  (negative = daylight protective, i.e. night is riskier)")
    (let [fmt (str "  Encoding %s (%s, N=%d): mean=%+.3f sd=%.3f "
                   "89%%ETI=[%+.3f, %+.3f]  OR(night/day)=%.2f")]
      (println (format fmt "A" "{Night,Dawn}→night" (:n fa)
                       (:mean sa) (:sd sa) (:lo sa) (:hi sa) (Math/exp (- (:mean sa)))))
      (println (format fmt "B" "contains Night→night" (:n fb)
                       (:mean sb) (:sd sb) (:lo sb) (:hi sb) (Math/exp (- (:mean sb))))))
    {:a (assoc sa :n (:n fa)) :b (assoc sb :n (:n fb))}))

;; ── solar-encoding ────────────────────────────────────────────────────────────

(defn- solar-binary
  "Binary is_daytime from a solar period keyword: :day/:dawn → 1, :night/:dusk → 0."
  [period]
  (cond (#{:day :dawn} period) 1
        (#{:night :dusk} period) 0
        :else nil))

(defn incident-solar
  "For each incident with a parseable timestamp, classify the moment by solar
   position (orca.timeofday). Returns {:agree n :total n :day n :night n
   :crosstab {[reported solar] count}} where reported is the raw period token."
  [incidents]
  (reduce
    (fn [acc r]
      (if-let [dt (tod/parse-dt (:date_of_interaction r) (:time_of_interaction r))]
        (let [sp       (tod/solar-period dt)
              reported (let [ps (periods (:darkness_or_daylight r))]
                         (when (= 1 (count ps)) (first ps)))
              solar-lbl (str/capitalize (name sp))
              bin      (solar-binary sp)]
          (-> acc
              (update :total inc)
              (update (if (= 1 bin) :day :night) inc)
              (cond-> (= reported solar-lbl) (update :agree inc))
              (update-in [:crosstab [reported solar-lbl]] (fnil inc 0))))
        acc))
    {:agree 0 :total 0 :day 0 :night 0 :crosstab {}}
    incidents))

(defn passage-solar
  "For each uneventful passage with parseable start/end timestamps (and duration
   within tod/default-max-passage-hours), integrate day vs night hours and derive
   a night fraction (night = Night+Dusk hours). Returns a seq of {:night-frac
   :any-night :reported-has-night :duration-hrs}."
  [uneventful]
  (keep
    (fn [r]
      (when-let [start (tod/parse-dt (:date_passage_commenced r)
                                     (:time_passage_commenced r))]
        (when-let [end (tod/parse-dt (:date_passage_ended r) (:time_passage_ended r))]
          (let [dur (tod/passage-duration-hours start end)]
            (when (<= dur tod/default-max-passage-hours)
              (let [[dh nh] (tod/passage-hours start end)
                    tot     (+ dh nh)
                    nf      (if (pos? tot) (/ nh tot) 0.0)]
                {:night-frac nf
                 :any-night (if (pos? nh) 1 0)
                 :reported-has-night
                 (if (str/includes? (str (:darkness_or_daylight r)) "Night") 1 0)
                 :duration-hrs dur}))))))
    uneventful))

(defn solar-encoding
  "Reclassify time of day by actual solar position (orca.timeofday) and compare
   with the reported periods. Prints incident solar-vs-reported agreement, the
   uneventful night-fraction distribution, the solar-vs-reported \"contains
   Night\" agreement, and solar-based interaction rates. Returns a summary map."
  [& _]
  (let [raw  (util/read-json (config/cfg :paths :raw))
        {incs :incident unes :uneventful} (raw-reports raw)
        is   (incident-solar incs)
        ps   (passage-solar unes)
        inc-day   (:day is)
        inc-night (:night is)
        une-day   (count (filter #(zero? (:any-night %)) ps))
        une-night (count (filter #(pos? (:any-night %)) ps))
        n-day     (+ inc-day une-day)
        n-night   (+ inc-night une-night)
        nfs       (mapv :night-frac ps)]
    (println "══ SOLAR-BASED TIME ENCODING ══")
    (println "\n█ INCIDENTS — solar classification:")
    (println (format "  agreement with reported: %d/%d (%.0f%%)"
                     (:agree is) (:total is)
                     (if (pos? (:total is)) (* 100.0 (/ (:agree is) (:total is))) 0.0)))
    (println (format "  solar binary: Day=%d  Night=%d" inc-day inc-night))
    (println "\n█ UNEVENTFUL — night-fraction distribution:")
    (when (seq nfs)
      (println (format "  mean=%.2f  median=%.2f  =0 (pure day)=%d  >0 (some night)=%d  >0.5=%d"
                       (util/mean nfs) (util/quantile nfs 0.5)
                       (count (filter zero? nfs))
                       (count (filter pos? nfs))
                       (count (filter #(> % 0.5) nfs)))))
    (println (format "  binary (any night exposure): Day-only=%d  includes-night=%d"
                     une-day une-night))
    (let [agree (count (filter #(= (:any-night %) (:reported-has-night %)) ps))]
      (println (format "  solar vs reported 'contains Night' agreement: %d/%d (%.0f%%)"
                       agree (count ps)
                       (if (seq ps) (* 100.0 (/ agree (count ps))) 0.0))))
    (println "\n█ SOLAR-BASED interaction rates (incident=solar moment, uneventful=any night):")
    (println (format "  Day:   inc=%4d total=%4d rate=%.1f%%"
                     inc-day n-day (if (pos? n-day) (* 100.0 (/ inc-day n-day)) 0.0)))
    (println (format "  Night: inc=%4d total=%4d rate=%.1f%%"
                     inc-night n-night (if (pos? n-night) (* 100.0 (/ inc-night n-night)) 0.0)))
    {:incident-agreement {:agree (:agree is) :total (:total is)}
     :incident-binary {:day inc-day :night inc-night}
     :uneventful-binary {:day une-day :night une-night}
     :solar-rates {:day {:incidents inc-day :n n-day}
                   :night {:incidents inc-night :n n-night}}}))
