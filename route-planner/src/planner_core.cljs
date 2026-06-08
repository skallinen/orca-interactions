(ns orca.planner.core
  "Pure-ClojureScript core math for the orca route planner (presence-effort-seasonal).

   No DOM, no Leaflet, no Reagent — just the posterior arithmetic so it can be
   loaded by Scittle in the browser AND exercised headlessly.

   The model combines two independent Bayesian logistic fits at runtime
   (see route-planner/data/POSTERIOR_SCHEMA.md, the single source of truth):

     - attr  (Part A): relative vessel/condition multipliers. Per draw,
       attr_adj = sum_k beta_k (x_k - x_ref_k) over standardized ordinals and
       indicator-minus-reference categoricals; attr_mult = exp(attr_adj). The
       reference vessel (ordinals at training mean, each categorical at its
       reference level) has attr_mult = 1. NOTE: the depth ordinal is
       intentionally EXCLUDED from attr_mult (it is a spurious location proxy
       that double-counts the spatial field; see `attr-x-ref`).

     - spatial (Part B): a seasonally-drifting occupancy field. The point is
       drifted by the day-of-year cycle, an RBF basis is evaluated at the
       drifted coords, f = sum_j w_j (B_j - col_means_j), and the relative risk
       RR = exp(f)/Z is mean ~1 over sailed waters (bounded).

   Absolute level is anchored by a Fermi exposure rate h0 = -ln(1-base_rate)/ref_nm
   (hazard per nautical mile). base_rate is calibrated by REFERENCE ROUTE: it is
   chosen so a fixed W-Portugal->Gibraltar reference passage reads ~2.5% (not by
   matching a population mean over all passages, which was dominated by short
   coastal hops and forced the per-nm hazard too high). The exposure layer accumulates lambda = sum
   hazard_per_nm * nm, the EXPECTED interaction count (additive across segments).
   lambda is then turned into P(>=1 interaction) by `count->prob`: a clustered
   (negative-binomial) aggregation on an effective, saturating exposure rather than
   the naive Poisson 1-exp(-lambda) — see that fn for why (orca interactions cluster
   by pod/day, so miles are not independent trials and long routes must not saturate
   to certainty). Percentiles over draws give the 89% credible interval.

   Daylight is carried as a documented multiplicative factor on the hazard. It is
   inherited from the blog time-of-day analysis and is orthogonal to this spatial
   model.")

;; ── Helpers ────────────────────────────────────────────────────────────────

;; Ordinal calculator key -> standardization/layout key in the JSON.
(def std-key->json
  {:depth :depth_ord :distance :distance_ord :speed :speed_ord
   :length :boat_length_ord :wind :wind_ord :sea :sea_state_ord})

;; Boat/passage param key -> categorical key in the JSON :categories/:reference.
(def cat-key->json
  {:sailing :sailing :antifoul :antifoul :hull :hull :rudder :rudder})

(def daylight-adj {"Day" 1.182 "Night" 0.662 "Average" 1.0})

(defn sigmoid [x]
  (/ 1.0 (+ 1.0 (js/Math.exp (- x)))))

(defn z
  "Standardize raw-val for ordinal calculator key param-key: (x - mean)/sd."
  [cfg raw-val param-key]
  (let [{:keys [mean sd]} (get-in cfg [:std-params param-key])]
    (/ (- raw-val mean) sd)))

;; ── derive-config ─────────────────────────────────────────────────────────

(defn cat-index-maps
  "From the attr :layout, build {cat-json-key {level-name beta-col-idx}} for the
   one-hot categorical columns named \"cat=level\"."
  [layout]
  (reduce
   (fn [acc [i nm]]
     (if-let [[_ catg lvl] (re-matches #"([a-z]+)=(.+)" nm)]
       (assoc-in acc [(keyword catg) lvl] i)
       acc))
   {}
   (map-indexed vector layout)))

(defn ordinal-index
  "Column index of an ordinal in the attr :layout (ordinals come first)."
  [layout ord-name]
  (first (keep-indexed (fn [i nm] (when (= nm ord-name) i)) layout)))

(defn derive-config
  "Pull the attr (vessel/condition) and spatial (occupancy) blocks out of the
   fetched posterior JSON (keywordized), into a flat config map."
  [data]
  (let [attr (:attr data)
        sp   (:spatial data)
        std  (:standardization attr)
        layout (:layout attr)
        ord-idx (into {} (map (fn [[k jk]]
                                [k (ordinal-index layout (name jk))])
                              std-key->json))]
    {:std-params  (into {} (map (fn [[k jk]] [k (get std jk)]) std-key->json))
     :ord-idx     ord-idx
     :cat-idx     (cat-index-maps layout)
     :reference   (:reference attr)
     :attr-draws  (:draws attr)
     ;; spatial
     :centers     (:centers sp)
     :lengthscale (:lengthscale_km sp)
     :col-means   (:col_means sp)
     :n-basis     (:n_basis sp)
     :drift       (:drift sp)
     :spatial-draws (:draws sp)
     ;; calibration defaults
     :base-rate-default (:base_rate_default data)
     :ref-nm-default    (:ref_nm_default data)
     :n-draws           (:n_draws data)}))

;; ── Seasonal drift + spatial smooth ─────────────────────────────────────────

(defn haversine-km
  "Great-circle distance in km (R = 6371)."
  [lat1 lon1 lat2 lon2]
  (let [r 6371.0
        rad (/ js/Math.PI 180.0)
        dlat (* (- lat2 lat1) rad)
        dlon (* (- lon2 lon1) rad)
        a (+ (* (js/Math.sin (/ dlat 2.0)) (js/Math.sin (/ dlat 2.0)))
             (* (js/Math.cos (* lat1 rad)) (js/Math.cos (* lat2 rad))
                (js/Math.sin (/ dlon 2.0)) (js/Math.sin (/ dlon 2.0))))]
    (* r 2.0 (js/Math.atan2 (js/Math.sqrt a) (js/Math.sqrt (- 1.0 a))))))

(defn mu-lat
  "Seasonal latitude drift mu_lat(doy) = a_lat*sin(2pi(doy-phi)/period)."
  [cfg doy]
  (let [{:keys [a_lat phi period]} (:drift cfg)]
    (* a_lat (js/Math.sin (/ (* 2.0 js/Math.PI (- doy phi)) period)))))

(defn mu-lon
  "Seasonal longitude drift mu_lon = a_lon (constant)."
  [cfg _doy]
  (:a_lon (:drift cfg)))

(defn spatial-basis
  "Vector [B_0 .. B_{M-1}] at the DRIFTED point: the point is shifted by the
   day-of-year drift, then B_j = exp(-dist_km^2 / (2*l^2)) to each center."
  [cfg lat lon doy]
  (let [l (:lengthscale cfg)
        denom (* 2.0 l l)
        lat' (- lat (mu-lat cfg doy))
        lon' (- lon (mu-lon cfg doy))]
    (mapv (fn [[clat clon]]
            (let [dkm (haversine-km lat' lon' clat clon)]
              (js/Math.exp (- (/ (* dkm dkm) denom)))))
          (:centers cfg))))

(defn f-draw
  "Spatial offset f = sum_j w_j * (B_j - col_means_j) for spatial-draw weights w."
  [cfg w basis]
  (let [cm (:col-means cfg)]
    (reduce + 0.0
            (map-indexed (fn [j wj] (* wj (- (nth basis j) (nth cm j)))) w))))

(defn rr-draw
  "Relative risk RR = exp(f)/Z for one spatial draw {:w :Z}."
  [cfg sdraw basis]
  (/ (js/Math.exp (f-draw cfg (:w sdraw) basis)) (:Z sdraw)))

;; ── Support taper ────────────────────────────────────────────────────────────
;;
;; The RBF occupancy field reverts to a NON-zero constant (-sum w_j*col_mean_j)
;; far from every center, so the deep open Atlantic and the Mediterranean — where
;; there are no incidents and no sailing effort — float up to a misleading
;; "neutral" RR (~0.38) instead of ~0. We gate the field by support: coverage =
;; sum_j B_j (the un-weighted RBF sum) is high near data and ->0 in the void, so a
;; taper 1 - exp(-coverage/cov-scale) fades the used/shown RR to ~0 where the model
;; has no support, without touching the well-sailed waters (coverage > ~1.5 there).

(def ^:private cov-scale 0.45)

(defn coverage
  "Un-weighted RBF support sum_j B_j for an already-computed basis vector."
  [basis]
  (reduce + 0.0 basis))

(defn support-mask
  "Taper in [0,1]: ~1 where the occupancy model has RBF support, ->0 in the void."
  [basis]
  (- 1.0 (js/Math.exp (- (/ (coverage basis) cov-scale)))))

;; ── Attribute multiplier ─────────────────────────────────────────────────────

(defn attr-x-ref
  "Build, once per (cfg,boat,passage), the contribution-per-column inputs for the
   attr design: a map of {beta-col-idx (x_k - x_ref_k)}.

   - Ordinals are standardized (z), so x_ref = training mean => contribution = z.
   - Categoricals are indicator-minus-reference: the chosen level's column gets
     +1; if the chosen level IS the reference (dropped) level, no column fires, so
     the reference vessel has attr_adj = 0 (attr_mult = 1)."
  [cfg boat passage]
  (let [oi (:ord-idx cfg)
        ci (:cat-idx cfg)
        ref (:reference cfg)
        ;; DEPTH INTENTIONALLY OMITTED. The fitted :depth ordinal beta (~+0.9,
        ;; "deeper => riskier") is a spurious LOCATION proxy: depth is a property
        ;; of where you are, not of the vessel, so including it in the attr
        ;; (vessel/condition) multiplier double-counts the spatial RBF occupancy
        ;; field. It is dropped here and will be reintroduced PROPERLY on the
        ;; spatial side in a later phase. (depth_ord stays in the posterior JSON
        ;; layout; it is simply unused by the vessel multiplier now.)
        ord-contribs
        {(get oi :distance) (z cfg (:distance-ord passage) :distance)
         (get oi :speed)    (z cfg (:speed boat) :speed)
         (get oi :length)   (z cfg (:length boat) :length)
         (get oi :wind)     (z cfg (:wind passage) :wind)
         (get oi :sea)      (z cfg (:sea passage) :sea)}
        cat-pairs {:sailing (:sailing boat)
                   :antifoul (:antifoul boat)
                   :hull (:hull boat)
                   :rudder (:rudder boat)}
        cat-contribs
        (reduce
         (fn [acc [catg lvl]]
           (let [jk (get cat-key->json catg)]
             (if (= lvl (get ref jk))
               acc                                       ;; reference => 0
               (if-let [col (get-in ci [jk lvl])]
                 (assoc acc col 1.0)
                 acc))))                                 ;; unknown level => 0
         {}
         cat-pairs)]
    (merge ord-contribs cat-contribs)))

(defn attr-adj-draw
  "attr_adj for one attr-draw beta vector given the precomputed x-ref contribs."
  [contribs beta]
  (reduce-kv (fn [acc col x] (+ acc (* (nth beta col) x))) 0.0 contribs))

;; ── h0 / hazard ──────────────────────────────────────────────────────────────

(defn h0
  "Fermi exposure hazard per nautical mile: -ln(1-base-rate)/ref-nm."
  [base-rate ref-nm]
  (/ (- (js/Math.log (- 1.0 base-rate))) ref-nm))

;; ── Count → probability: clustered (overdispersed) aggregation ────────────────
;;
;; The exposure layer gives lambda = sum_seg hazard_per_nm * nm: the EXPECTED
;; number of interactions on the route (a Poisson MEAN — additive across segments,
;; the correct quantity to accumulate). Turning that mean into P(>=1 interaction)
;; is where the old `1 - exp(-lambda)` went wrong: it assumes every nautical mile
;; is an INDEPENDENT Bernoulli trial, so a long route through a hotspot drives
;; lambda >> 1 and P saturates to ~100% — which is absurd (a single passage cannot
;; be near-certain). Two facts about Iberian-orca interactions break the Poisson
;; independence assumption, and each has a standard statistical correction:
;;
;;   1. CLUSTERING / OVERDISPERSION. Interactions come from ~2 pods / ~15 named
;;      animals and cluster heavily by pod and by day. The textbook model for
;;      clustered counts is the gamma-mixed Poisson = NEGATIVE BINOMIAL, for which
;;      P(0) = (1 + lambda/r)^(-r) > exp(-lambda), so P(>=1) = 1-(1+lambda/r)^(-r)
;;      is strictly BELOW the Poisson value and the gap widens as lambda grows
;;      (r -> inf recovers Poisson). The dispersion r is small under strong
;;      clustering; r = `dispersion-r` ~ 0.4 reflects the ~2-pod reality. (See e.g.
;;      Linden & Mantyniemi 2011, Ecology; the gamma-Poisson mixture.)
;;
;;   2. WITHIN-ROUTE SPATIAL CORRELATION (effective encounters << miles). Sailing
;;      more miles through one pod's daily range is the SAME encounter opportunity,
;;      not many independent ones, so independent hazard must not accumulate without
;;      bound. We map raw exposure to an EFFECTIVE exposure that saturates at
;;      `eff-lambda-max` (the most independent encounter opportunities a single
;;      passage can credibly represent): lambda_eff = Lmax*(1-exp(-lambda/Lmax)),
;;      which is ~identity for small lambda (short legs / open water are untouched)
;;      and -> Lmax for a very long hotspot transit (effective-sample-size cap).
;;
;; Net effect: short/typical passages read essentially as before (single digits),
;; while long hotspot routes settle to a defensible double-digit ceiling instead of
;; saturating to certainty. The transform is monotone in lambda, so per-draw
;; percentile (CI) orderings are preserved.

(def ^{:doc "Negative-binomial dispersion r (gamma-Poisson clustering). Small =>
  strong pod/day clustering => P(>=1) pulled well below the Poisson value.
  Calibrated to 0.3 (softened from 0.4): combined with eff-lambda-max=0.15 it
  gives a gentle saturation whose ceiling 1-(1+0.15/0.3)^(-0.3) ~ 11.5% lets long
  hotspot routes settle to a defensible single-digit/low-double-digit value
  instead of the old hard softcap that clipped every long route to one level."}
  dispersion-r 0.3)

(def ^{:doc "Effective-exposure ceiling: the most independent expected encounters a
  single planned passage can credibly accumulate (within-route correlation cap).
  Calibrated to 0.15 (softened from 0.5). Together with dispersion-r=0.3 this is
  the GENTLE softcap from the Phase-1 recalibration: short hops read <1%, the
  W-Portugal->Gibraltar reference route ~2.5%, a ~900nm circumnavigation ~6%, and
  the absolute ceiling on a saturated route is ~11.5% (no route approaches the
  old ~30%)."}
  eff-lambda-max 0.15)

(defn effective-lambda
  "Saturating map from raw expected-count lambda to EFFECTIVE independent exposure:
   Lmax*(1-exp(-lambda/Lmax)). ~identity for lambda << Lmax, -> Lmax as lambda -> inf."
  [lambda]
  (* eff-lambda-max (- 1.0 (js/Math.exp (- (/ lambda eff-lambda-max))))))

(defn count->prob
  "P(>=1 interaction) from an expected-count lambda under the clustered model:
   negative-binomial on the effective exposure, 1 - (1 + lam_eff/r)^(-r). Reduces
   to the old Poisson 1-exp(-lambda) when there is no clustering (r -> inf) and no
   saturation (lambda << eff-lambda-max)."
  [lambda]
  (let [le (effective-lambda lambda)]
    (- 1.0 (js/Math.pow (+ 1.0 (/ le dispersion-r)) (- dispersion-r)))))

;; ── Per-point fast path (drifted basis + draw-independent contribs hoisted) ───
;;
;; The hot route loop combines, for ~500 draws at each segment point, the spatial
;; RR (depends only on geometry + doy via the per-draw w/Z) and the attr_mult
;; (depends only on the per-draw beta via the precomputed contribs). We precompute
;; the drifted basis and the attr contributions once per point, then each draw is
;; a tight numeric reduction.

(defn point-plan
  "Precompute everything draw-independent for one (lat,lon,doy, boat, passage):
   the drifted spatial basis, the per-draw column contribution map for attr, the
   hazard scalar h0, and the daylight factor. Consumed by `plan-hazard-draw`."
  [cfg lat lon doy boat passage base-rate ref-nm]
  (let [basis (spatial-basis cfg lat lon doy)]
    {:basis    basis
     :mask     (support-mask basis)
     :contribs (attr-x-ref cfg boat passage)
     :h0       (h0 base-rate ref-nm)
     :adj      (get daylight-adj (:daylight passage) 1.0)}))

(defn plan-hazard-draw
  "hazard_per_nm for one paired (spatial-draw, attr-draw) using a point-plan:
   h0 * mask * RR_d * attr_mult_d, scaled by the daylight factor. The support
   mask tapers the rate to ~0 in waters with no RBF support (see `support-mask`)."
  [cfg plan sdraw beta]
  (let [rr   (rr-draw cfg sdraw (:basis plan))
        mult (js/Math.exp (attr-adj-draw (:contribs plan) beta))]
    (* (:h0 plan) (:adj plan) (:mask plan) rr mult)))

;; ── Poisson exposure / percentiles ───────────────────────────────────────────

(defn percentile
  "nth at min(count-1, floor(p*count)) — matches the published calculator."
  [sorted-vec p]
  (let [n (count sorted-vec)]
    (nth sorted-vec (min (dec n) (max 0 (js/Math.floor (* p n)))))))

(defn- summary
  "Percentile summary {:median :lo89 :hi89} over an unsorted seq of probs."
  [ps]
  (let [s (vec (sort ps))]
    {:median (percentile s 0.50)
     :lo89   (percentile s 0.055)
     :hi89   (percentile s 0.945)}))

(defn draw-pairs
  "Pair the spatial and attr draws of cfg by index: [[sdraw beta] ...]."
  [cfg]
  (map vector (:spatial-draws cfg) (:attr-draws cfg)))

(defn segment-risk
  "Percentile summary of p_seg(nm) over draws for one segment at one point.
   passage carries :depth-ord :distance-ord :wind :sea :daylight; boat the
   vessel attributes; doy the day-of-year; base-rate/ref-nm the calibration."
  [cfg lat lon doy boat passage nm base-rate ref-nm]
  (let [plan (point-plan cfg lat lon doy boat passage base-rate ref-nm)]
    (summary
     (map (fn [[sdraw beta]]
            (let [lam (* (plan-hazard-draw cfg plan sdraw beta) nm)]
              (count->prob lam)))
          (draw-pairs cfg)))))

(defn route-risk
  "Percentile summary of p_route over draws. segments is a seq of
   {:lat :lon :depth-ord :distance-ord :nm}; boat/passage shared. Each segment's
   passage inherits the shared wind/sea/daylight plus its own depth/distance."
  [cfg boat passage segments doy base-rate ref-nm]
  (let [plans (mapv (fn [{:keys [lat lon depth-ord distance-ord nm]}]
                      {:plan (point-plan cfg lat lon doy boat
                                         (assoc passage
                                                :depth-ord depth-ord
                                                :distance-ord distance-ord)
                                         base-rate ref-nm)
                       :nm nm})
                    segments)]
    (summary
     (map (fn [[sdraw beta]]
            (let [lam (reduce
                       (fn [acc {:keys [plan nm]}]
                         (+ acc (* (plan-hazard-draw cfg plan sdraw beta) nm)))
                       0.0 plans)]
              (count->prob lam)))
          (draw-pairs cfg)))))

;; ── Heatmap factoring (posterior-mean point estimate) ─────────────────────────
;;
;; The live-risk field is a posterior-MEAN point estimate. We split it so the two
;; kinds of edit recompute different parts:
;;
;;   - location+season STATIC part (per cell): depends on cell lat/lon, cell
;;     depth/distance, and the current doy + base-rate + ref-nm. This holds the
;;     mean-draw RR(cell,doy) and the depth/distance portion of attr_mult, plus
;;     the h0*ref_nm scalar. Rebuilt only when doy/base-rate/ref-nm change.
;;   - dynamic vessel SCALAR (one number): the rest of attr_mult (speed, length,
;;     sailing, antifoul, hull, rudder + wind/sea conditions), from the mean draw.
;;     Recomputed on every boat/condition change — cheap.
;;
;; Display intensity = 1 - exp(-(h0*ref_nm) * RR(cell,doy) * attr_mult), with the
;; attr_mult split into static (depth/distance) and dynamic (vessel) factors.

(defn mean-spatial-draw
  "Elementwise mean of the spatial draws: {:w mean-w :Z mean-Z}."
  [cfg]
  (let [draws (:spatial-draws cfg)
        n (count draws)
        m (count (:w (first draws)))
        w (mapv (fn [j]
                  (/ (reduce (fn [acc d] (+ acc (nth (:w d) j))) 0.0 draws) n))
                (range m))
        zsum (reduce (fn [acc d] (+ acc (:Z d))) 0.0 draws)]
    {:w w :Z (/ zsum n)}))

(defn mean-attr-draw
  "Elementwise mean over the attr beta draws (length-20 vector)."
  [cfg]
  (let [draws (:attr-draws cfg)
        n (count draws)
        cols (count (first draws))]
    (mapv (fn [j]
            (/ (reduce (fn [acc d] (+ acc (nth d j))) 0.0 draws) n))
          (range cols))))

(defn heatmap-static
  "Per-cell location+season static part:
   {:rr mean-RR(cell,doy) :static-mult exp(beta_dist*z) :scale h0*ref_nm}.
   Uses the posterior-mean draws. Rebuild when doy/base-rate/ref-nm change.

   The depth ordinal term is intentionally OMITTED here too (see `attr-x-ref`):
   it is a spurious location proxy double-counting the spatial RBF field. Only
   the distance-from-coast ordinal contributes to the static multiplier; the
   depth-ord argument is retained in the signature but unused."
  [cfg mean-sd mean-attr lat lon doy depth-ord distance-ord base-rate ref-nm]
  (let [basis (spatial-basis cfg lat lon doy)
        rr    (* (rr-draw cfg mean-sd basis) (support-mask basis))
        oi    (:ord-idx cfg)
        smult (js/Math.exp
               (* (nth mean-attr (get oi :distance))
                  (z cfg distance-ord :distance)))]
    {:rr rr :static-mult smult :scale (* (h0 base-rate ref-nm) ref-nm)}))

(defn dynamic-scalar
  "Dynamic vessel scalar: the non-spatial, non-depth/distance part of attr_mult
   (speed, length, sailing, antifoul, hull, rudder, wind, sea) times the daylight
   factor, from the posterior-mean attr draw. Recomputed on boat/condition edits."
  [cfg mean-attr boat passage]
  (let [oi  (:ord-idx cfg)
        ci  (:cat-idx cfg)
        ref (:reference cfg)
        adj (+ (* (nth mean-attr (get oi :speed)) (z cfg (:speed boat) :speed))
               (* (nth mean-attr (get oi :length)) (z cfg (:length boat) :length))
               (* (nth mean-attr (get oi :wind)) (z cfg (:wind passage) :wind))
               (* (nth mean-attr (get oi :sea)) (z cfg (:sea passage) :sea))
               (reduce
                (fn [acc [catg lvl]]
                  (let [jk (get cat-key->json catg)]
                    (if (or (= lvl (get ref jk))
                            (nil? (get-in ci [jk lvl])))
                      acc
                      (+ acc (nth mean-attr (get-in ci [jk lvl]))))))
                0.0
                {:sailing (:sailing boat)
                 :antifoul (:antifoul boat)
                 :hull (:hull boat)
                 :rudder (:rudder boat)}))]
    (* (js/Math.exp adj) (get daylight-adj (:daylight passage) 1.0))))

(defn heatmap-intensity
  "Display intensity for one cell: P(>=1) over a reference-length exposure through
   the cell, under the same clustered `count->prob` aggregation as the route, so the
   field and the route stay on one consistent scale."
  [d-scalar {:keys [rr static-mult scale]}]
  (count->prob (* scale rr static-mult d-scalar)))
