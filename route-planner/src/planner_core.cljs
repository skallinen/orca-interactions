(ns orca.planner.core
  "Pure-ClojureScript core math for the orca route planner.

   No DOM, no Leaflet, no Reagent — just the posterior arithmetic so it can be
   loaded by Scittle in the browser AND exercised headlessly. The model is the
   published blog calculator's logit (alpha + 8 continuous/binary terms + 4
   category offsets + King-Zeng), extended with the Bayesian spatial RBF offset
   `f` and a Poisson exposure layer for per-segment / per-route risk.

   Single source of truth for the formulas is the route-planner §2 spec.")

;; ── Helpers ────────────────────────────────────────────────────────────────

;; Calculator key -> standardization key in the JSON. autopilot is RAW 0/1 and
;; deliberately absent here (it is never standardized).
(def std-key->json
  {:depth :depth_ord :distance :distance_ord :speed :speed_ord
   :length :boat_length_ord :wind :wind_ord :sea :sea_state_ord})

;; Vector-param layout prefix -> category list key in the JSON.
(def prefix->category
  {"s" :sailing_mode "a" :antifoul "h" :hull_colour "r" :rudder})

(def daylight-adj {"Day" 1.182 "Night" 0.662 "Average" 1.0})

(defn sigmoid [x]
  (/ 1.0 (+ 1.0 (js/Math.exp (- x)))))

(defn z
  "Standardize raw-val for calculator key param-key: (x - mean)/sd."
  [cfg raw-val param-key]
  (let [{:keys [mean sd]} (get-in cfg [:std-params param-key])]
    (/ (- raw-val mean) sd)))

(defn king-zeng
  "King & Zeng (2001) intercept correction against the fetched sample rate."
  [cfg base-rate]
  (- (js/Math.log (/ base-rate (- 1.0 base-rate)))
     (js/Math.log (/ (:sample-rate cfg) (- 1.0 (:sample-rate cfg))))))

;; ── derive-config ───────────────────────────────────────────────────────────

(defn index-maps
  "From the JSON :layout and :categories, build {prefix {level-name draw-idx}}
   for the categorical blocks (s_/a_/h_/r_)."
  [layout categories]
  (reduce
    (fn [acc [i nm]]
      (if-let [[_ pfx off] (re-matches #"([a-z]+)_(\d+)" nm)]
        (if-let [cat-key (prefix->category pfx)]
          (assoc-in acc [pfx (get-in categories [cat-key (js/parseInt off)])] i)
          acc)
        acc))
    {}
    (map-indexed vector layout)))

(defn derive-config
  "Pull sample-rate, std-params, category index maps and the spatial block out
   of the fetched posterior JSON (keywordized)."
  [data]
  (let [std (:standardization data)
        im  (index-maps (:layout data) (:categories data))
        sp  (:spatial data)]
    {:sample-rate (:sample_rate data)
     :std-params  (into {} (map (fn [[k jk]] [k (get std jk)]) std-key->json))
     :sailing (im "s") :antifoul (im "a") :hull (im "h") :rudder (im "r")
     :centers     (:centers sp)
     :lengthscale (:lengthscale sp)
     :col-means   (:col_means sp)
     :coef-start  (:coef_start sp)
     :n-basis     (:n_basis sp)
     :metric      (:metric sp)}))

;; ── Spatial smooth ──────────────────────────────────────────────────────────

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

(defn spatial-basis
  "Vector [B_0 .. B_{M-1}], B_j = exp(-dist_km(point, center_j)^2 / (2*l^2))."
  [cfg lat lon]
  (let [l (:lengthscale cfg)
        denom (* 2.0 l l)]
    (mapv (fn [[clat clon]]
            (let [dkm (haversine-km lat lon clat clon)]
              (js/Math.exp (- (/ (* dkm dkm) denom)))))
          (:centers cfg))))

(defn spatial-weights
  "The M spatial RBF weights of one draw: columns coef-start .. coef-start+M-1."
  [cfg draw]
  (let [start (:coef-start cfg)
        m (:n-basis cfg)]
    (subvec (vec draw) start (+ start m))))

(defn f
  "Spatial offset f = sum_j w_j * (B_j - col_means_j) for spatial weights w."
  [cfg w lat lon]
  (let [bs (spatial-basis cfg lat lon)
        cm (:col-means cfg)]
    (reduce + 0.0
            (map-indexed (fn [j wj]
                           (* wj (- (nth bs j) (nth cm j))))
                         w))))

;; ── predict-transit-p (the unified logit assembler) ─────────────────────────

(defn base-logit
  "Base-30 logit WITHOUT the spatial f term, for one draw. depth-ord/distance-ord
   are raw ordinal ints; boat = {:autopilot :speed :length :sailing :antifoul
   :hull :rudder}; passage = {:wind :sea :base-rate}."
  [cfg draw depth-ord distance-ord boat passage]
  (+ (nth draw 0)                                        ;; alpha
     (* (nth draw 1) (z cfg depth-ord :depth))
     (* (nth draw 5) (z cfg distance-ord :distance))
     (* (nth draw 2) (:autopilot boat))                 ;; raw 0/1
     (* (nth draw 3) (z cfg (:speed boat) :speed))
     (* (nth draw 4) (z cfg (:length boat) :length))
     (* (nth draw 6) (z cfg (:wind passage) :wind))
     (* (nth draw 7) (z cfg (:sea passage) :sea))
     (nth draw (get-in cfg [:sailing (:sailing boat)]))
     (nth draw (get-in cfg [:antifoul (:antifoul boat)]))
     (nth draw (get-in cfg [:hull (:hull boat)]))
     (nth draw (get-in cfg [:rudder (:rudder boat)]))
     (king-zeng cfg (:base-rate passage))))

(defn predict-transit-p
  "Per-draw transit probability at (lat,lon): sigmoid(base-logit + f) scaled by
   the daylight adjustment, capped at 0.9999. passage carries :daylight."
  [cfg draw lat lon depth-ord distance-ord boat passage]
  (let [bl (base-logit cfg draw depth-ord distance-ord boat passage)
        logit (+ bl (f cfg (spatial-weights cfg draw) lat lon))
        adj (get daylight-adj (:daylight passage) 1.0)]
    (min 0.9999 (* (sigmoid logit) adj))))

;; ── Per-draw fast path (geometry + draw-independent scalars hoisted) ──────────
;;
;; The hot route loop evaluates predict-transit-p for every one of ~500 draws at
;; each segment point. Almost everything except the raw `(nth draw idx)` reads is
;; identical across draws: the 50 spatial basis offsets (B_j - col_means_j) are
;; pure geometry, and the standardized z-scores / King-Zeng / category column
;; indices depend only on (cfg, point, boat, passage). We precompute those once
;; per point into a "plan", then each draw is a tight numeric reduction.

(defn point-plan
  "Precompute everything draw-independent for one (lat,lon, depth, distance,
   boat, passage): the spatial basis offsets `bo` = (B_j - col_means_j) as a JS
   array, the category column indices, the standardized continuous-term inputs,
   the King-Zeng correction, the daylight adjustment, and the spatial coef start.
   Returned map is consumed by `plan-transit-p`."
  [cfg lat lon depth-ord distance-ord boat passage]
  (let [l     (:lengthscale cfg)
        denom (* 2.0 l l)
        centers (:centers cfg)
        cm    (:col-means cfg)
        m     (:n-basis cfg)
        bo    (js/Array. m)]
    (dotimes [j m]
      (let [c    (nth centers j)
            clat (nth c 0)
            clon (nth c 1)
            dkm  (haversine-km lat lon clat clon)
            bj   (js/Math.exp (- (/ (* dkm dkm) denom)))]
        (aset bo j (- bj (nth cm j)))))
    {:bo bo
     :coef-start (:coef-start cfg)
     :n-basis m
     :z-depth    (z cfg depth-ord :depth)
     :z-distance (z cfg distance-ord :distance)
     :autopilot  (:autopilot boat)
     :z-speed    (z cfg (:speed boat) :speed)
     :z-length   (z cfg (:length boat) :length)
     :z-wind     (z cfg (:wind passage) :wind)
     :z-sea      (z cfg (:sea passage) :sea)
     :i-sailing  (get-in cfg [:sailing (:sailing boat)])
     :i-antifoul (get-in cfg [:antifoul (:antifoul boat)])
     :i-hull     (get-in cfg [:hull (:hull boat)])
     :i-rudder   (get-in cfg [:rudder (:rudder boat)])
     :king-zeng  (king-zeng cfg (:base-rate passage))
     :adj        (get daylight-adj (:daylight passage) 1.0)}))

(defn plan-transit-p
  "predict-transit-p for one draw using a precomputed `point-plan`. Numerically
   identical to predict-transit-p (same operations, same order for the spatial
   sum) but skips the per-draw geometry and hashmap work."
  [plan draw]
  (let [bo    (:bo plan)
        start (:coef-start plan)
        m     (:n-basis plan)
        bl    (+ (nth draw 0)
                 (* (nth draw 1) (:z-depth plan))
                 (* (nth draw 5) (:z-distance plan))
                 (* (nth draw 2) (:autopilot plan))
                 (* (nth draw 3) (:z-speed plan))
                 (* (nth draw 4) (:z-length plan))
                 (* (nth draw 6) (:z-wind plan))
                 (* (nth draw 7) (:z-sea plan))
                 (nth draw (:i-sailing plan))
                 (nth draw (:i-antifoul plan))
                 (nth draw (:i-hull plan))
                 (nth draw (:i-rudder plan))
                 (:king-zeng plan))
        fsum  (loop [j 0 acc 0.0]
                (if (< j m)
                  (recur (inc j)
                         (+ acc (* (nth draw (+ start j)) (aget bo j))))
                  acc))]
    (min 0.9999 (* (sigmoid (+ bl fsum)) (:adj plan)))))

;; ── Poisson exposure ────────────────────────────────────────────────────────

(defn percentile
  "nth at min(count-1, floor(p*count)) — matches the published calculator."
  [sorted-vec p]
  (let [n (count sorted-vec)]
    (nth sorted-vec (min (dec n) (max 0 (js/Math.floor (* p n)))))))

(defn- lambda-ref [p-transit]
  (- (js/Math.log (- 1.0 p-transit))))

(defn- p-seg [lam-ref nm ref-nm]
  (let [lam-seg (* lam-ref (/ nm ref-nm))]
    (- 1.0 (js/Math.exp (- lam-seg)))))

(defn- summary
  "Percentile summary {:median :lo89 :hi89} over an unsorted seq of probs."
  [ps]
  (let [s (vec (sort ps))]
    {:median (percentile s 0.50)
     :lo89   (percentile s 0.055)
     :hi89   (percentile s 0.945)}))

(defn segment-risk
  "Percentile summary of p_seg(nm) over draws for one segment at one point."
  [cfg draws lat lon depth-ord distance-ord boat passage nm ref-nm]
  (let [plan (point-plan cfg lat lon depth-ord distance-ord boat passage)]
    (summary
      (map (fn [d]
             (p-seg (lambda-ref (plan-transit-p plan d)) nm ref-nm))
           draws))))

(defn route-risk
  "Percentile summary of p_route over draws. segments is a seq of
   {:lat :lon :depth-ord :distance-ord :nm}; boat/passage/ref-nm shared."
  [cfg draws boat passage segments ref-nm]
  ;; Precompute one point-plan per segment (geometry + draw-independent scalars)
  ;; so the per-draw loop is a tight numeric reduction.
  (let [plans (mapv (fn [{:keys [lat lon depth-ord distance-ord nm]}]
                      {:plan (point-plan cfg lat lon depth-ord distance-ord
                                         boat passage)
                       :nm nm})
                    segments)]
    (summary
      (map (fn [d]
             (let [lam (reduce
                         (fn [acc {:keys [plan nm]}]
                           (+ acc (* (lambda-ref (plan-transit-p plan d))
                                     (/ nm ref-nm))))
                         0.0 plans)]
               (- 1.0 (js/Math.exp (- lam)))))
           draws))))

;; ── Heatmap factoring ───────────────────────────────────────────────────────

(defn mean-draw
  "Elementwise mean over all draws (vector length 80). Precompute once."
  [draws]
  (let [n (count draws)
        cols (count (first draws))]
    (mapv (fn [j]
            (/ (reduce (fn [acc d] (+ acc (nth d j))) 0.0 draws) n))
          (range cols))))

(defn heatmap-static
  "Location-only part S: depth/distance terms + spatial f, using the mean draw."
  [cfg md lat lon depth-ord distance-ord]
  (+ (* (nth md 1) (z cfg depth-ord :depth))
     (* (nth md 5) (z cfg distance-ord :distance))
     (f cfg (spatial-weights cfg md) lat lon)))

(defn dynamic-scalar
  "Location-independent part D: alpha + boat/passage terms + King-Zeng, mean draw."
  [cfg md boat passage]
  (+ (nth md 0)
     (* (nth md 2) (:autopilot boat))
     (* (nth md 3) (z cfg (:speed boat) :speed))
     (* (nth md 4) (z cfg (:length boat) :length))
     (* (nth md 6) (z cfg (:wind passage) :wind))
     (* (nth md 7) (z cfg (:sea passage) :sea))
     (nth md (get-in cfg [:sailing (:sailing boat)]))
     (nth md (get-in cfg [:antifoul (:antifoul boat)]))
     (nth md (get-in cfg [:hull (:hull boat)]))
     (nth md (get-in cfg [:rudder (:rudder boat)]))
     (king-zeng cfg (:base-rate passage))))

(defn heatmap-intensity
  "sigmoid(D + S) scaled by the daylight adjustment."
  [d-scalar s-static daylight]
  (* (sigmoid (+ d-scalar s-static)) (get daylight-adj daylight 1.0)))
