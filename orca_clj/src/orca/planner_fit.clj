(ns orca.planner-fit
  "Phase 1 of the route planner: a Bayesian spatial smooth over the orca zone,
   fit as a presence/background (pseudo-absence) logistic model and exported as
   an additively-extended posterior JSON.

   Why presence/background and not a joint refit: the uneventful (non-incident)
   reports in orca_reportlist.json have NO coordinates, so we cannot refit the
   M3 attribute model jointly with the spatial term. Instead the 216 incidents
   are presences (y=1) and a fixed sample of sea cells drawn from the geo-grid
   are background pseudo-absences (y=0). The fitted RBF weights describe where
   incidents concentrate relative to the available sea area. The 30 base
   attribute coefficients are NOT refit here — they are copied unchanged from
   the published blogpost/posterior_draws.json. The planner logit is the M3
   base logit plus the spatial offset f = Σ_j w_j (B_j − m_j)."
  (:require
   [clojure.string :as str]
   [orca.config :as config]
   [orca.diagnostics :as diag]
   [orca.stan :as stan]
   [orca.util :as util]))

;; ── input paths (relative to the orca_clj working dir) ──────────────────────

(def reportlist-path "../orca_reportlist.json")
(def geo-grid-path "../route-planner/geo_grid.json")
(def base-posterior-path "../blogpost/posterior_draws.json")
(def out-path "../route-planner/posterior_planner.json")

;; ── geometry ────────────────────────────────────────────────────────────────

(def ^:const earth-radius-km 6371.0088)

(defn haversine-km
  "Great-circle distance in km between (lat1,lon1) and (lat2,lon2) degrees."
  ^double [^double lat1 ^double lon1 ^double lat2 ^double lon2]
  (let [to-rad (/ Math/PI 180.0)
        p1 (* lat1 to-rad)
        p2 (* lat2 to-rad)
        dp (* (- lat2 lat1) to-rad)
        dl (* (- lon2 lon1) to-rad)
        a (+ (* (Math/sin (/ dp 2.0)) (Math/sin (/ dp 2.0)))
             (* (Math/cos p1) (Math/cos p2)
                (Math/sin (/ dl 2.0)) (Math/sin (/ dl 2.0))))]
    (* 2.0 earth-radius-km (Math/asin (Math/min 1.0 (Math/sqrt a))))))

;; ── 1.1 presences ───────────────────────────────────────────────────────────

(defn numeric?
  "True if x is a finite number."
  [x]
  (and (number? x) (not (Double/isNaN (double x))) (not (Double/isInfinite (double x)))))

(defn presence-points
  "Incident (lat,lon) pairs from orca_reportlist.json. Returns
   {:points [[lat lon] …] :total <raw count> :dropped <missing-coord count>}."
  []
  (let [incidents (-> (util/read-json reportlist-path) :reports :incident vals)
        total (count incidents)
        kept (->> incidents
                  (keep (fn [{:keys [lat] lon :long}]
                          (when (and (numeric? lat) (numeric? lon))
                            [(double lat) (double lon)])))
                  vec)]
    {:points kept :total total :dropped (- total (count kept))}))

;; ── 1.2 RBF centers ─────────────────────────────────────────────────────────

(defn grid-centers
  "Regular grid of [lat lon] centers over `bbox` at `spacing` degrees."
  [{:keys [lat-min lat-max lon-min lon-max]} spacing]
  (let [steps (fn [lo hi]
                (let [n (long (Math/floor (/ (- (double hi) (double lo)) (double spacing))))]
                  (mapv #(+ (double lo) (* % (double spacing))) (range (inc n)))))]
    (vec (for [lat (steps lat-min lat-max)
               lon (steps lon-min lon-max)]
           [lat lon]))))

(defn prune-centers
  "Keep only centers with at least one presence within `radius-deg` degrees
   (planar). Returns the surviving centers."
  [centers presences radius-deg]
  (let [r2 (* (double radius-deg) (double radius-deg))]
    (->> centers
         (filterv (fn [[clat clon]]
                    (some (fn [[plat plon]]
                            (let [dlat (- (double plat) (double clat))
                                  dlon (- (double plon) (double clon))]
                              (<= (+ (* dlat dlat) (* dlon dlon)) r2)))
                          presences))))))

;; ── 1.3 background pseudo-absences ──────────────────────────────────────────

(defn background-points
  "Draw K sea cells from geo_grid.json with a fixed-seed RNG, recovering each
   cell-centre lat/lon from its integer-tenths key. Returns [[lat lon] …]."
  [k seed]
  (let [cells (-> (util/read-json geo-grid-path) :cells)
        keyvec (mapv name (keys cells))
        n (count keyvec)
        rng (java.util.Random. seed)]
    (mapv (fn [_]
            (let [kk (nth keyvec (.nextInt rng n))
                  [li oi] (mapv #(Long/parseLong %) (str/split kk #","))]
              [(/ li 10.0) (/ oi 10.0)]))
          (range k))))

;; ── 1.4 design matrix ───────────────────────────────────────────────────────

(defn lengthscale-km
  "ℓ = 1.25 × spacing × 111 km."
  ^double [spacing]
  (* 1.25 (double spacing) 111.0))

(defn raw-basis-row
  "Raw RBF basis [B_0 … B_{M-1}] at (lat,lon) for the given centers and ℓ."
  [centers ell lat lon]
  (let [two-l2 (* 2.0 ell ell)]
    (mapv (fn [[clat clon]]
            (let [d (haversine-km lat lon clat clon)]
              (Math/exp (- (/ (* d d) two-l2)))))
          centers)))

(defn build-design
  "Build the centered design. Presences first (y=1), background after (y=0).
   col_means m_j are computed over the BACKGROUND rows only. Returns
   {:y [...] :Bsp [[...] …] :col-means [...] :N :M}."
  [presences background centers ell]
  (let [pres-raw (mapv (fn [[la lo]] (raw-basis-row centers ell la lo)) presences)
        bg-raw (mapv (fn [[la lo]] (raw-basis-row centers ell la lo)) background)
        m (count centers)
        n-bg (count bg-raw)
        col-means (mapv (fn [j]
                          (/ (reduce + (map #(nth % j) bg-raw)) (double n-bg)))
                        (range m))
        center-rows (fn [rows]
                      (mapv (fn [row]
                              (mapv (fn [j] (- (double (nth row j)) (double (nth col-means j))))
                                    (range m)))
                            rows))
        bsp (into (center-rows pres-raw) (center-rows bg-raw))
        y (into (vec (repeat (count pres-raw) 1)) (vec (repeat n-bg 0)))]
    {:y y :Bsp bsp :col-means col-means :N (count bsp) :M m}))

;; ── 1.5 fit ─────────────────────────────────────────────────────────────────

(def k-background 3000)
(def fit-seed 42)

(defn prepare-fit
  "Assemble centers, presences, background and the design map. Returns a map
   with :centers :ell :design :presences :background ready for sampling."
  []
  (let [{:keys [bbox spacing]} (config/cfg :rbf)
        {:keys [points] :as pres} (presence-points)
        all-centers (grid-centers bbox spacing)
        centers (prune-centers all-centers points (* 1.5 (double spacing)))
        ell (lengthscale-km spacing)
        background (background-points k-background fit-seed)
        design (build-design points background centers ell)]
    {:centers centers :ell ell :design design
     :presences pres :background background
     :n-grid-centers (count all-centers)}))

(defn fit
  "Compile + sample the spatial Stan model. Returns the per-chain draw datasets
   (a vector of tablecloth datasets, one per chain)."
  [{:keys [design]}]
  (stan/sample-chains
    "stan/spatial.stan"
    {:N (:N design) :M (:M design) :y (:y design) :Bsp (:Bsp design)}
    {:n-chains 4 :seed fit-seed :num-warmup 1000 :num-samples 2000
     :out-dir (config/cfg :paths :out-dir)}))

(defn w-cols
  "Vector of w-column names \"w.1\" … \"w.M\" as they appear in the Stan CSV
   (CmdStan flattens `vector[M] w` and the CSV reader keeps string headers)."
  [m]
  (mapv #(str "w." (inc %)) (range m)))

(defn diagnose
  "Convergence summary over b0 and the M w columns of the per-chain datasets.
   Returns {:max-rhat :min-ess :divergences}."
  [chains m]
  (let [cols (into ["b0"] (w-cols m))
        per-chain-col (fn [col]
                        (mapv (fn [ds] (vec (get ds col))) chains))
        stats (mapv (fn [col]
                      (let [c (per-chain-col col)]
                        {:rhat (diag/rhat c)
                         :ess (min (diag/ess-bulk c) (diag/ess-tail c))}))
                    cols)
        div (reduce + (map (fn [ds]
                             (reduce + (map #(if (and % (pos? (long %))) 1 0)
                                            (get ds "divergent__" (repeat 0)))))
                           chains))]
    {:max-rhat (apply max (map :rhat stats))
     :min-ess (apply min (map :ess stats))
     :divergences div
     :per-param (zipmap cols stats)}))

;; ── 1.6 export ──────────────────────────────────────────────────────────────

(defn even-thin-indices
  "Indices selecting `out` rows evenly from `total`: floor(i·total/out)."
  [total out]
  (mapv (fn [i] (long (Math/floor (/ (* (double i) total) (double out))))) (range out)))

(defn round4 ^double [^double x]
  (/ (Math/rint (* x 10000.0)) 10000.0))

(defn thin-w-draws
  "Pool the per-chain w draws (presence/background fit) into one matrix and
   even-thin to `out` rows; each row is [w_0 … w_{M-1}] rounded to 4 dp."
  [chains m out]
  (let [cols (w-cols m)
        pooled (vec (mapcat (fn [ds]
                              (let [colvecs (mapv #(vec (get ds %)) cols)
                                    n (count (first colvecs))]
                                (map (fn [i] (mapv #(nth % i) colvecs)) (range n))))
                            chains))
        idx (even-thin-indices (count pooled) out)]
    (mapv (fn [i] (mapv #(round4 (double %)) (nth pooled i))) idx)))

(def spatial-note
  (str "The spatial term is a Bayesian presence/background incident-density "
       "offset: incident locations (presences, y=1) versus sea-cell "
       "pseudo-absences sampled from the geo-grid (y=0). It is fit separately "
       "from the M3 attribute coefficients because the uneventful reports lack "
       "coordinates. The fitted offset f = sum_j w_j (B_j - col_means_j) is "
       "ADDED to the M3 base logit and is NOT orthogonalized against "
       "depth/distance."))

(defn export!
  "Write route-planner/posterior_planner.json: the base 30 columns copied
   unchanged from posterior_draws.json, extended with M spatial w columns and a
   `spatial` block. `prep` is the prepare-fit map; `chains` the fit output."
  [{:keys [centers ell design]} chains]
  (let [base (util/read-json base-posterior-path)
        m (:M design)
        n (:n base)
        w-rows (thin-w-draws chains m n)
        base-draws (:draws base)
        new-draws (mapv (fn [base-row w-row] (into (vec base-row) w-row))
                        base-draws w-rows)
        w-names (mapv #(str "w_" %) (range m))
        new-layout (into (vec (:layout base)) w-names)
        spatial {:metric "haversine_km"
                 :centers (mapv (fn [[la lo]] [la lo]) centers)
                 :lengthscale ell
                 :col_means (mapv #(round4 (double %)) (:col-means design))
                 :coef_start 30
                 :n_basis m}
        out (assoc base
                   :layout new-layout
                   :draws new-draws
                   :spatial spatial
                   :spatial_note spatial-note)]
    (util/write-json out-path out)
    {:layout-len (count new-layout) :n-basis m :draw-row-len (count (first new-draws))}))

;; ── orchestration + verification ────────────────────────────────────────────

(defn mean-w
  "Posterior-mean weights over the exported (thinned) w rows."
  [w-rows m]
  (let [n (count w-rows)]
    (mapv (fn [j] (/ (reduce + (map #(nth % j) w-rows)) (double n))) (range m))))

(defn f-bar
  "Posterior-mean spatial offset f̄(lat,lon) = Σ_j w̄_j (B_j − m_j)."
  [centers ell col-means wbar lat lon]
  (let [b (raw-basis-row centers ell lat lon)]
    (reduce + (map (fn [bj mj wj] (* (double wj) (- (double bj) (double mj))))
                   b col-means wbar))))

(defn surface-check
  "Evaluate f̄ at the two hotspots and two open-ocean points. PASS when both
   hotspots clearly exceed both open-ocean points (and open-ocean ≤ ~0)."
  [{:keys [centers ell design]} chains]
  (let [m (:M design)
        w-rows (thin-w-draws chains m 500)
        wbar (mean-w w-rows m)
        col-means (:col-means design)
        f (fn [lat lon] (f-bar centers ell col-means wbar lat lon))
        gib (f 36.0 -5.5)
        gal (f 42.0 -9.5)
        atl1 (f 38.0 -18.0)
        atl2 (f 44.0 -15.0)
        pass? (and (> gib atl1) (> gib atl2) (> gal atl1) (> gal atl2)
                   (<= atl1 0.05) (<= atl2 0.05))]
    {:gibraltar gib :galicia gal :atlantic-1 atl1 :atlantic-2 atl2 :pass? pass?}))

(defn read-chains
  "Read the n-chains already-written CmdStan output CSVs back as per-chain
   datasets (so the post-fit steps can run without re-sampling)."
  [n-chains out-dir]
  (mapv (fn [c] (stan/read-draws-csv (str out-dir "/draws_" c ".csv")))
        (range 1 (inc n-chains))))

(defn report!
  "Diagnose, export and surface-check `chains` for the `prep` design. Prints the
   verification summary and returns it."
  [prep chains]
  (let [m (:M (:design prep))
        d (diagnose chains m)
        e (export! prep chains)
        s (surface-check prep chains)]
    (println "CONVERGENCE max-rhat:" (:max-rhat d)
             " min-ess:" (:min-ess d) " divergences:" (:divergences d))
    (println "EXPORT layout-len:" (:layout-len e)
             " n-basis:" (:n-basis e) " draw-row-len:" (:draw-row-len e))
    (println "SURFACE gibraltar:" (:gibraltar s) " galicia:" (:galicia s)
             " atlantic-1:" (:atlantic-1 s) " atlantic-2:" (:atlantic-2 s))
    (println "SURFACE-CHECK:" (if (:pass? s) "PASS" "FAIL"))
    {:diagnostics d :export e :surface s :M m
     :presences (:presences prep) :n-grid-centers (:n-grid-centers prep)}))

(defn run-phase1!
  "Full Phase-1 pipeline: prepare, fit, diagnose, export, surface-check. Prints
   the verification summary and returns it."
  []
  (let [prep (prepare-fit)
        _ (println "presences total/dropped:"
                   (:total (:presences prep)) "/" (:dropped (:presences prep)))
        _ (println "grid centers:" (:n-grid-centers prep)
                   "-> pruned M =" (:M (:design prep)))
        _ (println "background K =" (count (:background prep))
                   " N =" (:N (:design prep)))
        chains (fit prep)]
    (report! prep chains)))
