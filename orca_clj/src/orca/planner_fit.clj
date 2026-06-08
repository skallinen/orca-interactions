(ns orca.planner-fit
  "Route-risk model rebuild (ROUTE_PLANNER_PLAN.md I2.5/I2.6, phase M3).

   Two independent Bayesian logistic models, combined at runtime:

   - Part A (attribute model): plain logistic on all 654 reports (216 incident
     y=1, 438 uneventful y=0), predicting interaction from standardized ordinals
     (depth, distance, speed, boat-length, wind, sea-state) and one-hot
     categoricals (sailing, antifoul, hull, rudder; one reference level dropped
     per category). Keeps the relative attribute effects (the per-draw beta);
     alpha is discarded (absolute level comes from calibration). attr_logit.stan.

   - Part B (occupancy field): a presence/effort spatial logistic. y=1 at the 216
     incident (lat,lon,doy); y=0 at the 3000 effort-proportional background
     points. An RBF basis is evaluated at SEASONALLY-DRIFTED coordinates
     lat' = lat - mu_lat(doy), with mu_lat(doy) = A_lat*sin(2pi*(doy-phi)/365),
     A_lat=3.5, phi=149 (ecology-fixed; estimating the drift is a future
     upgrade). The Bayesian shrinkage w ~ normal(0,tau) bounds the field
     amplitude. spatial.stan, reused as-is.

   Combine (the contract M4 mirrors): the occupancy field is normalized to mean 1
   over the sailed background (a per-draw scalar Z), so the relative-risk
   multiplier RR is BOUNDED and risk stops saturating at 100%. See the module
   `export!`/`sanity` and route-planner/data/POSTERIOR_SCHEMA.md.

   Reproduce (inside the nix tooling shell so CmdStan compiles + samples):
     export CMDSTAN=$HOME/.cache/orca-cmdstan
     clojure -X:planner-fit            ; fit A + B, export, run the 6 gates
   or call orca.planner-fit/run-fit! from a REPL."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [orca.config :as config]
   [orca.diagnostics :as diag]
   [orca.stan :as stan]
   [orca.util :as util]))

;; ── input / output paths (relative to the orca_clj working dir) ─────────────

(def dataset-path "../route-planner/data/planner_dataset.edn")
(def background-path "../route-planner/data/background_sample.edn")
(def geo-grid-path "../route-planner/geo_grid.json")
(def out-path "../route-planner/posterior_planner.json")
(def schema-path "../route-planner/data/POSTERIOR_SCHEMA.md")

;; ── calibration / model constants ───────────────────────────────────────────

(def n-draws 500)
(def base-rate-default 0.025)
(def ref-nm-default 100.0)

;; Seasonal drift (ecology-fixed): mu_lat(doy) = a-lat * sin(2pi*(doy-phi)/365).
(def drift {:a-lat 3.5 :phi 149.0 :a-lon 0.0 :period 365.0})

(def lengthscale-km 150.0)
(def center-spacing 2.0)        ; degrees between candidate RBF centers
(def center-keep-deg 2.0)       ; keep centers within this of an incident/effort
(def fit-seed 42)

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

(defn mu-lat
  "Seasonal latitude drift at day-of-year `doy`."
  ^double [^double doy]
  (let [{:keys [a-lat phi period]} drift]
    (* (double a-lat) (Math/sin (/ (* 2.0 Math/PI (- doy (double phi)))
                                   (double period))))))

(defn mu-lon ^double [^double _doy] (double (:a-lon drift)))

(defn drifted
  "Drifted coordinates [lat' lon'] for (lat,lon,doy)."
  [^double lat ^double lon ^double doy]
  [(- lat (mu-lat doy)) (- lon (mu-lon doy))])

;; ── ordinal / categorical mapping (Part A) ──────────────────────────────────

;; Known string variants beyond config :ordinal-maps. Speed has a "12+" top bin
;; in the data; map it to 4 (one above "8 - 11"=3). depth occasionally carries a
;; stray integer 1 (bad data, not a bin string): treated as missing, logged.
(def speed-extra {"12+" 4})

(defn ordinal-maps []
  (let [m (config/cfg :ordinal-maps)]
    {:depth_ord       (:depth m)
     :distance_ord    (:distance m)
     :speed_ord       (merge (:speed m) speed-extra)
     :boat_length_ord (:boat-length m)
     :wind_ord        (:wind m)
     :sea_state_ord   (:sea-state m)}))

;; raw dataset key per ordinal field.
(def ordinal-src
  {:depth_ord :depth :distance_ord :distance :speed_ord :speed
   :boat_length_ord :length :wind_ord :wind :sea_state_ord :sea})

(def ordinal-keys
  [:depth_ord :distance_ord :speed_ord :boat_length_ord :wind_ord :sea_state_ord])

;; categorical fields and their reference (dropped) level => multiplier 1.
(def categorical-src
  {:sailing :sailing :antifoul :antifoul :hull :hull :rudder :rudder})
(def categorical-keys [:sailing :antifoul :hull :rudder])
(def categorical-reference
  {:sailing "Sailing" :antifoul "Black" :hull "White/light" :rudder "Spade"})

(defn map-ordinal
  "Map a raw value through `m`; returns {:val long} when mappable, {:val nil}
   when missing (nil), or {:unmappable v} when a non-nil value is not in `m`."
  [m v]
  (cond
    (nil? v) {:val nil}
    (contains? m v) {:val (get m v)}
    :else {:unmappable v}))

(defn category-levels
  "Sorted non-reference levels present in `rows` for categorical `k` (the
   reference is dropped). Unmappable/nil values are ignored for level
   discovery; rows with nil there fall back to the reference at encode time."
  [rows k]
  (let [src (categorical-src k)
        ref-lvl (categorical-reference k)]
    (->> rows (map src) (remove nil?) distinct (remove #(= % ref-lvl)) sort vec)))

(defn collect-attr
  "Read the dataset, label rows (incident=1, uneventful=0), and return
   {:rows [...] :ordmaps ... :cat-levels {k [levels]} :unmappable {field {v n}}
    :missing {field n}}.

   Standardization means/sds are computed over the COMBINED rows from the
   mappable ordinal values only (missing imputed to mean => z=0 contributes
   nothing). Boat length is nil for every uneventful row (logged); those rows
   get z=0 on that predictor."
  []
  (let [d (edn/read-string (slurp dataset-path))
        rows (concat (map #(assoc % :y 1) (:incidents d))
                     (map #(assoc % :y 0) (:uneventful d)))
        ordmaps (ordinal-maps)
        unmappable (atom {})
        missing (atom {})
        decoded (mapv
                  (fn [r]
                    (let [ords (reduce
                                 (fn [acc ok]
                                   (let [src (ordinal-src ok)
                                         res (map-ordinal (ordmaps ok) (get r src))]
                                     (cond
                                       (:unmappable res)
                                       (do (swap! unmappable update-in
                                                  [ok (:unmappable res)] (fnil inc 0))
                                           ;; treat unmappable as missing for fit
                                           (assoc acc ok nil))
                                       (nil? (:val res))
                                       (do (swap! missing update ok (fnil inc 0))
                                           (assoc acc ok nil))
                                       :else (assoc acc ok (:val res)))))
                                 {} ordinal-keys)]
                      (merge r ords)))
                  rows)
        cat-levels (into {} (map (fn [k] [k (category-levels decoded k)])
                                 categorical-keys))]
    {:rows decoded :ordmaps ordmaps :cat-levels cat-levels
     :unmappable @unmappable :missing @missing}))

(defn standardization
  "Per-ordinal {:mean :sd} over the mappable (non-nil) values across all rows."
  [rows]
  (into {}
        (map (fn [ok]
               (let [vs (->> rows (map ok) (remove nil?) (map double))]
                 [ok {:mean (util/mean vs) :sd (util/pstdev vs)}]))
             ordinal-keys)))

(defn attr-layout
  "Design-matrix column layout: the 6 ordinals (standardized) followed by the
   one-hot categorical columns \"<cat>=<level>\" for every non-reference level."
  [cat-levels]
  (into (mapv name ordinal-keys)
        (mapcat (fn [k] (map #(str (name k) "=" %) (cat-levels k)))
                categorical-keys)))

(defn encode-row
  "Encode one decoded row into the design vector matching `attr-layout`.
   Missing ordinal => z=0 (the training mean). Categorical at reference (or
   missing) => all-zero indicators for that category."
  [stdz cat-levels row]
  (let [ord-part (mapv (fn [ok]
                         (let [{:keys [mean sd]} (stdz ok)
                               v (get row ok)]
                           (if (nil? v) 0.0 (/ (- (double v) mean) sd))))
                       ordinal-keys)
        cat-part (vec
                   (mapcat
                     (fn [k]
                       (let [v (get row (categorical-src k))]
                         (map (fn [lvl] (if (= v lvl) 1.0 0.0)) (cat-levels k))))
                     categorical-keys))]
    (into ord-part cat-part)))

(defn build-attr-design
  "Build {:X [[...]] :y [...] :K :N :layout :stdz :cat-levels ...} for Part A."
  []
  (let [{:keys [rows cat-levels] :as c} (collect-attr)
        stdz (standardization rows)
        layout (attr-layout cat-levels)
        X (mapv #(encode-row stdz cat-levels %) rows)
        y (mapv :y rows)]
    (assoc c :X X :y y :K (count layout) :N (count rows)
           :layout layout :stdz stdz)))

;; ── Part A fit ──────────────────────────────────────────────────────────────

(defn fit-attr-chains
  "Compile + sample attr_logit.stan. Returns per-chain datasets."
  [{:keys [N K X y]}]
  (stan/sample-chains
    "stan/attr_logit.stan"
    {:N N :K K :X X :y y}
    {:n-chains 4 :seed fit-seed :num-warmup 1000 :num-samples 2000
     :out-dir (str (config/cfg :paths :out-dir) "/planner_attr")}))

(defn beta-cols [k] (mapv #(str "beta." (inc %)) (range k)))

(defn diagnose-attr [chains k]
  (let [cols (into ["alpha"] (beta-cols k))
        per-chain (fn [col] (mapv (fn [ds] (vec (get ds col))) chains))
        stats (mapv (fn [col]
                      (let [c (per-chain col)]
                        {:rhat (diag/rhat c)
                         :ess (min (diag/ess-bulk c) (diag/ess-tail c))}))
                    cols)
        div (reduce + (map (fn [ds]
                             (reduce + (map #(if (and % (pos? (long %))) 1 0)
                                            (get ds "divergent__" (repeat 0)))))
                           chains))]
    {:max-rhat (apply max (map :rhat stats))
     :min-ess (apply min (map :ess stats))
     :divergences div}))

;; ── Part B: RBF centers + drifted design ────────────────────────────────────

(defn incident-points
  "[[lat lon doy] ...] for the 216 incidents (presences)."
  []
  (let [d (edn/read-string (slurp dataset-path))]
    (mapv (fn [r] [(double (:lat r)) (double (:lon r)) (double (:doy r))])
          (:incidents d))))

(defn background-points
  "[[lat lon doy] ...] for the effort-proportional background sample."
  []
  (mapv (fn [r] [(double (:lat r)) (double (:lon r)) (double (:doy r))])
        (edn/read-string (slurp background-path))))

(defn candidate-centers
  "Regular [lat lon] grid over the bbox of all sailed points at `spacing` deg."
  [points spacing]
  (let [lats (map first points)
        lons (map second points)
        lo-la (Math/floor (apply min lats))
        hi-la (Math/ceil (apply max lats))
        lo-lo (Math/floor (apply min lons))
        hi-lo (Math/ceil (apply max lons))
        steps (fn [lo hi]
                (let [n (long (Math/floor (/ (- hi lo) (double spacing))))]
                  (mapv #(+ lo (* % (double spacing))) (range (inc n)))))]
    (vec (for [la (steps lo-la hi-la) lo (steps lo-lo hi-lo)] [la lo]))))

(defn prune-centers
  "Keep centers within `keep-deg` (planar) of any anchor [lat lon ...] point."
  [centers anchors keep-deg]
  (let [r2 (* (double keep-deg) (double keep-deg))]
    (filterv (fn [[clat clon]]
               (some (fn [[plat plon]]
                       (let [dla (- (double plat) clat)
                             dlo (- (double plon) clon)]
                         (<= (+ (* dla dla) (* dlo dlo)) r2)))
                     anchors))
             centers)))

(defn raw-basis-row
  "RBF basis at drifted (lat',lon') for `centers` and lengthscale `ell` (km)."
  [centers ^double ell ^double lat' ^double lon']
  (let [two-l2 (* 2.0 ell ell)]
    (mapv (fn [[clat clon]]
            (let [dkm (haversine-km lat' lon' clat clon)]
              (Math/exp (- (/ (* dkm dkm) two-l2)))))
          centers)))

(defn basis-at
  "RBF basis at (lat,lon,doy), drift applied."
  [centers ell lat lon doy]
  (let [[la' lo'] (drifted (double lat) (double lon) (double doy))]
    (raw-basis-row centers ell la' lo')))

(defn build-spatial-design
  "Presences (y=1) then background (y=0), drifted RBF basis, column-centered by
   the BACKGROUND col_means. Returns {:y :Bsp :col-means :N :M :centers :ell
   :presences :background}."
  []
  (let [pres (incident-points)
        bg (background-points)
        anchors (concat pres bg)
        cand (candidate-centers anchors center-spacing)
        ;; keep centers near incidents OR high-effort (background) areas
        centers (prune-centers cand anchors center-keep-deg)
        ell lengthscale-km
        pres-raw (mapv (fn [[la lo doy]] (basis-at centers ell la lo doy)) pres)
        bg-raw (mapv (fn [[la lo doy]] (basis-at centers ell la lo doy)) bg)
        m (count centers)
        n-bg (count bg-raw)
        col-means (mapv (fn [j] (/ (reduce + (map #(nth % j) bg-raw)) (double n-bg)))
                        (range m))
        center-rows (fn [rows]
                      (mapv (fn [row]
                              (mapv (fn [j] (- (double (nth row j))
                                               (double (nth col-means j))))
                                    (range m)))
                            rows))
        bsp (into (center-rows pres-raw) (center-rows bg-raw))
        y (into (vec (repeat (count pres-raw) 1)) (vec (repeat n-bg 0)))]
    {:y y :Bsp bsp :col-means col-means :N (count bsp) :M m
     :centers centers :ell ell :presences pres :background bg}))

(defn fit-spatial-chains
  "Compile + sample spatial.stan on the drifted design. Per-chain datasets."
  [{:keys [N M y Bsp]}]
  (stan/sample-chains
    "stan/spatial.stan"
    {:N N :M M :y y :Bsp Bsp}
    {:n-chains 4 :seed fit-seed :num-warmup 1000 :num-samples 2000
     :out-dir (str (config/cfg :paths :out-dir) "/planner_spatial")}))

(defn w-cols [m] (mapv #(str "w." (inc %)) (range m)))

(defn diagnose-spatial [chains m]
  (let [cols (into ["b0"] (w-cols m))
        per-chain (fn [col] (mapv (fn [ds] (vec (get ds col))) chains))
        stats (mapv (fn [col]
                      (let [c (per-chain col)]
                        {:rhat (diag/rhat c)
                         :ess (min (diag/ess-bulk c) (diag/ess-tail c))}))
                    cols)
        div (reduce + (map (fn [ds]
                             (reduce + (map #(if (and % (pos? (long %))) 1 0)
                                            (get ds "divergent__" (repeat 0)))))
                           chains))]
    {:max-rhat (apply max (map :rhat stats))
     :min-ess (apply min (map :ess stats))
     :divergences div}))

;; ── draw thinning ───────────────────────────────────────────────────────────

(defn even-thin-indices [total out]
  (mapv (fn [i] (long (Math/floor (/ (* (double i) total) (double out))))) (range out)))

(defn round6 ^double [^double x]
  (let [s 1000000.0] (/ (Math/rint (* x s)) s)))

(defn pool-cols
  "Pool the named CSV columns across chains into row vectors [c0 c1 ...]."
  [chains cols]
  (vec (mapcat (fn [ds]
                 (let [cv (mapv #(vec (get ds %)) cols)
                       n (count (first cv))]
                   (map (fn [i] (mapv #(nth % i) cv)) (range n))))
               chains)))

(defn thin-rows [pooled out]
  (let [idx (even-thin-indices (count pooled) out)]
    (mapv #(nth pooled %) idx)))

;; ── runtime math (mirrored by sanity gates and by M4) ───────────────────────

(defn field-f
  "f_d(lat,lon,doy) = sum_j w_j (B_j(drifted) - col_means_j) for one draw's w."
  [centers ell col-means w lat lon doy]
  (let [b (basis-at centers ell lat lon doy)]
    (reduce + (map (fn [wj bj mj] (* (double wj) (- (double bj) (double mj))))
                   w b col-means))))

(defn z-normalizer
  "Z_d = mean over background of exp(f_d) at each background point's own doy."
  [centers ell col-means w background]
  (let [s (reduce (fn [acc [la lo doy]]
                    (+ acc (Math/exp (field-f centers ell col-means w la lo doy))))
                  0.0 background)]
    (/ s (double (count background)))))

(defn rr
  "Relative risk RR_d = exp(f_d(lat,lon,doy)) / Z_d (mean 1 over sailed waters)."
  [centers ell col-means w z lat lon doy]
  (/ (Math/exp (field-f centers ell col-means w lat lon doy)) (double z)))

;; ── export ──────────────────────────────────────────────────────────────────

(def spatial-note
  (str "Presence/effort occupancy field: incident locations (y=1) versus "
       "effort-proportional background (y=0), an RBF basis evaluated at "
       "seasonally-drifted coordinates lat'=lat-A_lat*sin(2pi(doy-phi)/365). "
       "Normalized to mean 1 over the sailed background (per-draw Z) so relative "
       "risk is bounded."))

(declare schema-doc)

(defn export!
  "Write the new posterior_planner.json and the schema doc. Precomputes the
   per-draw Z normalizer over the background. Returns a small summary."
  [{:keys [attr-design attr-chains spatial-design spatial-chains]}]
  (let [{:keys [K layout stdz cat-levels]} attr-design
        attr-pooled (pool-cols attr-chains (beta-cols K))
        beta-rows (mapv (fn [row] (mapv #(round6 (double %)) row))
                        (thin-rows attr-pooled n-draws))
        {:keys [M centers col-means ell background]} spatial-design
        sp-pooled (pool-cols spatial-chains (into ["b0"] (w-cols M)))
        sp-draws (thin-rows sp-pooled n-draws)
        spatial-draws
        (mapv (fn [row]
                (let [b0 (double (first row))
                      w (vec (rest row))
                      z (z-normalizer centers ell col-means w background)]
                  {:w (mapv #(round6 (double %)) w)
                   :Z (round6 z)
                   :b0 (round6 b0)}))
              sp-draws)
        out {:model "presence-effort-seasonal"
             :n_draws n-draws
             :base_rate_default base-rate-default
             :ref_nm_default ref-nm-default
             :attr {:ordinals (mapv name ordinal-keys)
                    :standardization
                    (into {} (map (fn [ok]
                                    [ok {:mean (round6 (:mean (stdz ok)))
                                         :sd (round6 (:sd (stdz ok)))}])
                                  ordinal-keys))
                    :categories (into {} (map (fn [k] [k (cat-levels k)])
                                              categorical-keys))
                    :reference categorical-reference
                    :layout layout
                    :draws beta-rows}
             :spatial {:metric "haversine_km"
                       :centers (mapv (fn [[la lo]] [(round6 la) (round6 lo)]) centers)
                       :lengthscale_km lengthscale-km
                       :col_means (mapv #(round6 (double %)) col-means)
                       :n_basis M
                       :drift {:a_lat (:a-lat drift) :phi (:phi drift)
                               :a_lon (:a-lon drift) :period (:period drift)}
                       :draws spatial-draws
                       :note spatial-note}}]
    (util/write-json out-path out)
    (spit schema-path (schema-doc M K cat-levels))
    {:out out-path :n-basis M :k K :layout-len (count layout)
     :n-draws n-draws}))

(defn schema-doc [m k cat-levels]
  (str "# posterior_planner.json schema (presence-effort-seasonal)\n\n"
       "Written by `orca.planner-fit/export!`. Two independent Bayesian logistic\n"
       "models combined at runtime (see ROUTE_PLANNER_PLAN.md I2.5/I2.6).\n\n"
       "## Top level\n"
       "- `model`: \"presence-effort-seasonal\"\n"
       "- `n_draws`: " n-draws " posterior draws.\n"
       "- `base_rate_default`: " base-rate-default
       " (P of >=1 interaction over `ref_nm_default` nm at RR=1, attr_mult=1).\n"
       "- `ref_nm_default`: " ref-nm-default " nm.\n\n"
       "## attr (Part A: relative vessel/condition effects)\n"
       "- `ordinals`: " (vec (map name ordinal-keys))
       " (standardized z=(x-mean)/sd).\n"
       "- `standardization`: per-ordinal {mean, sd} from the combined 654 rows"
       " (mappable values only).\n"
       "- `categories`: non-reference levels per category: " cat-levels "\n"
       "- `reference`: dropped reference level per category"
       " (defines multiplier=1): " categorical-reference "\n"
       "- `layout`: the " k " design columns, ordinals then one-hot"
       " \"cat=level\".\n"
       "- `draws`: " n-draws " rows, each a length-" k
       " beta vector (alpha discarded).\n\n"
       "## spatial (Part B: seasonally-drifting occupancy field)\n"
       "- `metric`: haversine_km. `lengthscale_km`: " lengthscale-km ".\n"
       "- `centers`: " m " RBF center [lat,lon] pairs. `n_basis`: " m ".\n"
       "- `col_means`: length-" m
       " background column means (subtract from raw basis).\n"
       "- `drift`: {a_lat, phi, a_lon, period};"
       " mu_lat(doy)=a_lat*sin(2pi(doy-phi)/period), mu_lon=a_lon.\n"
       "- `draws`: " n-draws " of {w: length-" m
       ", Z: per-draw normalizer, b0: fit intercept (unused at runtime)}.\n\n"
       "## Runtime combine (per draw d, point (lat,lon), day doy, vessel x)\n"
       "```\n"
       "lat' = lat - a_lat*sin(2pi(doy-phi)/period); lon' = lon - a_lon\n"
       "B_j  = exp(-haversine_km((lat',lon'),center_j)^2 / (2*lengthscale_km^2))\n"
       "f_d  = sum_j w_dj * (B_j - col_means_j)\n"
       "RR_d = exp(f_d) / Z_d           ; mean 1 over sailed waters => bounded\n"
       "attr_adj_d  = sum_k beta_dk * (x_k - x_ref_k)   ; z / indicator-minus-ref\n"
       "attr_mult_d = exp(attr_adj_d)\n"
       "h0          = -ln(1 - base_rate_default) / ref_nm_default\n"
       "hazard_per_nm_d = h0 * RR_d * attr_mult_d\n"
       "; segment nm: lambda_seg_d = hazard_per_nm_d*nm; p=1-exp(-lambda_seg_d)\n"
       "; route: lambda_d = sum_segs; p_route_d = 1-exp(-lambda_d); pct over d.\n"
       "```\n"
       "x_ref: ordinals at training mean (z=0), each categorical at its reference\n"
       "level, so the reference vessel has attr_mult=1.\n"))

;; ── sanity gates ────────────────────────────────────────────────────────────

(defn read-export [] (util/read-json out-path))

(defn ord-key->kw [s] (keyword s))

(defn export->runtime
  "Repack the exported JSON into the closures the gates need (replicating M4)."
  [ex]
  (let [sp (:spatial ex)
        centers (mapv (fn [[la lo]] [(double la) (double lo)]) (:centers sp))
        ell (double (:lengthscale_km sp))
        col-means (mapv double (:col_means sp))
        drift* {:a-lat (double (:a_lat (:drift sp)))
                :phi (double (:phi (:drift sp)))
                :a-lon (double (:a_lon (:drift sp)))
                :period (double (:period (:drift sp)))}
        sp-draws (:draws sp)
        ws (mapv (fn [d] (mapv double (:w d))) sp-draws)
        zs (mapv (fn [d] (double (:Z d))) sp-draws)
        attr (:attr ex)
        layout (mapv name (:layout attr))
        betas (mapv (fn [r] (mapv double r)) (:draws attr))
        stdz (:standardization attr)]
    {:centers centers :ell ell :col-means col-means :drift* drift*
     :ws ws :zs zs :layout layout :betas betas :stdz stdz
     :base-rate (double (:base_rate_default ex))
     :ref-nm (double (:ref_nm_default ex))}))

(defn mu-lat* ^double [drift* ^double doy]
  (* (:a-lat drift*) (Math/sin (/ (* 2.0 Math/PI (- doy (:phi drift*)))
                                  (:period drift*)))))

(defn rr-draws
  "RR per draw at (lat,lon,doy) using the exported runtime block."
  [{:keys [centers ell col-means drift* ws zs]} lat lon doy]
  (let [la' (- (double lat) (mu-lat* drift* (double doy)))
        lo' (- (double lon) (:a-lon drift*))
        two-l2 (* 2.0 ell ell)
        b (mapv (fn [[clat clon]]
                  (let [dkm (haversine-km la' lo' clat clon)]
                    (Math/exp (- (/ (* dkm dkm) two-l2)))))
                centers)]
    (mapv (fn [w z]
            (let [f (reduce + (map (fn [wj bj mj] (* wj (- bj mj))) w b col-means))]
              (/ (Math/exp f) z)))
          ws zs)))

;; default/average vessel: all ordinals at z=0, all categoricals at reference =>
;; attr_mult = 1 for every draw. So gate routes use attr-mult = 1.0.

(defn h0 ^double [base-rate ref-nm]
  (/ (- (Math/log (- 1.0 (double base-rate)))) (double ref-nm)))

(defn subdivide
  "Split a great-circle leg (lat1,lon1)->(lat2,lon2) into <= max-nm sub-segments;
   return [[mid-lat mid-lon nm] ...] (linear interpolation, good enough here)."
  [lat1 lon1 lat2 lon2 max-nm]
  (let [dist-km (haversine-km lat1 lon1 lat2 lon2)
        dist-nm (/ dist-km 1.852)
        n (max 1 (long (Math/ceil (/ dist-nm (double max-nm)))))
        seg-nm (/ dist-nm n)]
    (mapv (fn [i]
            (let [t (/ (+ i 0.5) (double n))]
              [(+ lat1 (* t (- lat2 lat1)))
               (+ lon1 (* t (- lon2 lon1)))
               seg-nm]))
          (range n))))

(defn route-segments
  "Subdivide a polyline of [lat lon] waypoints into <= max-nm sub-segments."
  [waypoints max-nm]
  (vec (mapcat (fn [[[la1 lo1] [la2 lo2]]]
                 (subdivide la1 lo1 la2 lo2 max-nm))
               (partition 2 1 waypoints))))

(defn route-p
  "Per-draw route P(>=1) median + 89% CI for the default vessel (attr_mult=1)."
  [rt waypoints doy max-nm]
  (let [segs (route-segments waypoints max-nm)
        h (h0 (:base-rate rt) (:ref-nm rt))
        n-draws* (count (:ws rt))
        ;; accumulate per-draw lambda over segments
        lambda (reduce
                 (fn [acc [la lo nm]]
                   (let [rrs (rr-draws rt la lo doy)]
                     (mapv (fn [a r] (+ a (* h r nm))) acc rrs)))
                 (vec (repeat n-draws* 0.0))
                 segs)
        ps (mapv (fn [l] (- 1.0 (Math/exp (- l)))) lambda)
        [lo hi] (diag/eti ps 0.89)]
    {:median (util/quantile ps 0.5) :lo lo :hi hi :n-seg (count segs)}))

;; geo grid for gate 4 (hotspot factor over sea cells).
(defn sea-cells
  "[[lat lon] ...] cell centers from geo_grid.json."
  []
  (let [g (util/read-json geo-grid-path)]
    (mapv (fn [k]
            (let [[li oi] (mapv #(Long/parseLong %)
                                (str/split (name k) #","))]
              [(/ li 10.0) (/ oi 10.0)]))
          (keys (:cells g)))))

(defn posterior-mean-w [ws]
  (let [n (count ws) m (count (first ws))]
    (mapv (fn [j] (/ (reduce + (map #(nth % j) ws)) (double n))) (range m))))

(defn sanity
  "Run the 6 mandatory offline gates against the exported JSON. Prints every
   number and returns a map of results. attr/spatial diagnostics are passed in
   (from the fits) so gate 6 can print convergence."
  ([] (sanity nil))
  ([diags]
   (let [ex (read-export)
         rt (export->runtime ex)
         _ (println "=== centers M =" (count (:centers rt)) " n_draws =" (count (:ws rt)))
         ;; Gate 1: W-Portugal -> Gibraltar, August (doy=232)
         g1-wp [[38.70 -9.42] [37.10 -8.67] [36.00 -6.00] [36.14 -5.35]]
         g1 (route-p rt g1-wp 232 25.0)
         _ (println "GATE1 W-Portugal->Gibraltar Aug(232): median"
                    (format "%.4f" (:median g1)) " 89%CI ["
                    (format "%.4f" (:lo g1)) (format "%.4f" (:hi g1)) "] n-seg" (:n-seg g1))
         ;; Gate 2: short open-Atlantic hop
         g2 (route-p rt [[40.0 -15.0] [40.5 -15.0]] 232 25.0)
         _ (println "GATE2 open-Atlantic hop Aug(232): median"
                    (format "%.5f" (:median g2)) " 89%CI ["
                    (format "%.5f" (:lo g2)) (format "%.5f" (:hi g2)) "] n-seg" (:n-seg g2))
         ;; Gate 3: monotone in length (double the route by appending a return leg)
         g3-long (route-p rt (into g1-wp (reverse (butlast g1-wp))) 232 25.0)
         _ (println "GATE3 monotone: short median" (format "%.4f" (:median g1))
                    " doubled median" (format "%.4f" (:median g3-long))
                    " increased?" (> (:median g3-long) (:median g1)))
         ;; Gate 4: bounded hotspot factor (posterior-mean draw). RR is mean ~1
         ;; over SAILED waters by construction, so the bounded-hotspot factor is
         ;; max RR / mean-RR-over-background (the background being the sailed
         ;; denominator). The grid min/median/max are printed for context (the
         ;; full grid spans large unsailed areas, so its median is well below 1).
         wbar (posterior-mean-w (:ws rt))
         zbar (util/mean (:zs rt))
         rt1 (assoc rt :ws [wbar] :zs [zbar])
         cells (sea-cells)
         rrm (mapv (fn [[la lo]] (first (rr-draws rt1 la lo 232))) cells)
         rr-min (apply min rrm) rr-med (util/quantile rrm 0.5) rr-max (apply max rrm)
         bg-rr (mapv (fn [[la lo doy]] (first (rr-draws rt1 la lo doy)))
                     (background-points))
         rr-sailed-mean (util/mean bg-rr)
         hotspot-factor (/ rr-max rr-sailed-mean)
         _ (println "GATE4 RR over grid doy=232 (mean draw): min"
                    (format "%.4f" rr-min) " median" (format "%.4f" rr-med)
                    " max" (format "%.4f" rr-max))
         _ (println "GATE4 mean RR over sailed background (~1):"
                    (format "%.4f" rr-sailed-mean) " hotspot factor max/sailed-mean:"
                    (format "%.2f" hotspot-factor))
         ;; Gate 5: seasonal movement of the hotspot
         gib-w (first (rr-draws rt1 36.0 -5.5 50))
         gib-s (first (rr-draws rt1 36.0 -5.5 232))
         gal-w (first (rr-draws rt1 43.0 -9.5 50))
         gal-s (first (rr-draws rt1 43.0 -9.5 232))
         _ (println "GATE5 Strait RR winter(50)" (format "%.4f" gib-w)
                    " summer(232)" (format "%.4f" gib-s) " winter>summer?" (> gib-w gib-s))
         _ (println "GATE5 Galicia RR winter(50)" (format "%.4f" gal-w)
                    " summer(232)" (format "%.4f" gal-s) " summer>winter?" (> gal-s gal-w))
         _ (when diags
             (println "GATE6 attr:" (:attr diags) " spatial:" (:spatial diags)))
         results {:gate1 g1 :gate2 g2
                  :gate3 {:short (:median g1) :doubled (:median g3-long)
                          :pass (> (:median g3-long) (:median g1))}
                  :gate4 {:min rr-min :median rr-med :max rr-max
                          :sailed-mean rr-sailed-mean :factor hotspot-factor
                          :pass (< hotspot-factor 12.0)}
                  :gate5 {:strait-winter gib-w :strait-summer gib-s
                          :gal-winter gal-w :gal-summer gal-s
                          :pass (and (> gib-w gib-s) (> gal-s gal-w))}
                  :gate6 diags}]
     (println "GATE1 PASS?" (and (>= (:median g1) 0.03) (<= (:median g1) 0.35)
                                 (> (- (:hi g1) (:lo g1)) 0.02)))
     (println "GATE2 PASS?" (< (:median g2) 0.01))
     (println "GATE3 PASS?" (:pass (:gate3 results)))
     (println "GATE4 PASS?" (:pass (:gate4 results)))
     (println "GATE5 PASS?" (:pass (:gate5 results)))
     results)))

;; ── orchestration ───────────────────────────────────────────────────────────

(defn run-fit!
  "Full pipeline: fit A, fit B, export, run sanity gates. Returns a summary."
  [& _]
  (let [_ (println "── Part A: attribute model ──")
        ad (build-attr-design)
        _ (println "N =" (:N ad) " K =" (:K ad) " layout:" (:layout ad))
        _ (when (seq (:unmappable ad)) (println "UNMAPPABLE:" (:unmappable ad)))
        _ (println "MISSING (imputed to mean/reference):" (:missing ad))
        ac (fit-attr-chains ad)
        da (diagnose-attr ac (:K ad))
        _ (println "ATTR diagnostics:" da)
        _ (println "── Part B: occupancy field ──")
        sd (build-spatial-design)
        _ (println "N =" (:N sd) " M centers =" (:M sd))
        sc (fit-spatial-chains sd)
        ds (diagnose-spatial sc (:M sd))
        _ (println "SPATIAL diagnostics:" ds)
        ex (export! {:attr-design ad :attr-chains ac
                     :spatial-design sd :spatial-chains sc})
        _ (println "EXPORT:" ex)
        res (sanity {:attr da :spatial ds})]
    {:attr da :spatial ds :export ex :sanity res}))
