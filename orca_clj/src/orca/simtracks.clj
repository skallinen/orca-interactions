(ns orca.simtracks
  "Part-B point-process / track simulator (SYNTHETIC_VALIDATION_PLAN B3, PURE).

   The heart of the depth-vs-field recovery question. We build a KNOWN intensity
   surface on the REAL geo grid (real 84 RBF centers, real ETOPO bathymetry) and
   simulate location / passage data from it, so a later recovery harness can fit
   the UNCHANGED `stan/spatial.stan` and check whether it reconstructs the known
   truth — in particular whether the flexible RBF field (lengthscale ell=150 km)
   steals the continuous depth effect.

   Ground-truth intensity (mirrors spatial.stan's linear predictor):

     log lambda(s) = b0 + f_rbf(s; w_true) + b1*z(s) + b2*z(s)^2

   where f_rbf(s) = sum_j w_true_j * (B_j(drifted s) - col_means_j) is the RBF
   field at KNOWN weights w_true on the real centers, and z(s) is the
   standardized log10 depth from the real bathy grid (pf/sample-depth ->
   pf/logdepth -> standardize). This is exactly the surface the model fits, so
   recovery is a fair test.

   Tracks are simulated as a MIXTURE over route corridors with SETTABLE mixing
   probabilities pi_r (this is the route-choice / availability distribution).
   Two modes are supported:
     - :mimic   reuse the REAL pf/background-points as the availability sample
                (matches the real data's spatial+depth distribution), and
     - :synthetic draw background ~ effort along configured corridors with
                arbitrary, KNOWN route-choice + depth distributions.
   Incident (use, y=1) points are drawn by use-availability thinning: a
   candidate site is accepted with probability proportional to
   lambda(s)/effort(s), i.e. exp(f_rbf + depth) relative to its background
   density, so y=1 concentrates where lambda exceeds the available effort.

   Output is schema-compatible with pf/fit-spatial-chains: {:N :M :y :Bsp :z :z2}
   plus :truth carrying the generating parameters and the per-cell lambda for
   surface scoring.

   ── Separability (the crux). ──────────────────────────────────────────────
   A smooth RBF field with ell=150 km can absorb a smooth depth effect, so a
   naive truth where the depth signal lives at the SAME spatial scale as the
   field is unidentifiable (spatial confounding). `separable-config` makes the
   truth recoverable two ways at once:
     1. SHARPER scale — z(s) is the real shelf/slope log-depth, which varies on
        a much finer scale than ell=150 km (the continental shelf break turns
        z from deep-ocean to shelf within tens of km). We quantify this in
        `depth-vs-field-scale` and assert it in tests.
     2. DECORRELATION — w_true is chosen so the field f_rbf is (numerically)
        ORTHOGONAL to z over the grid: we regress the raw field on z across all
        cells and subtract the z-aligned component (`decorrelate-field`). With
        cov(f_rbf, z) ~ 0 the field carries no linear depth information, so the
        only way to fit the depth quadratic is the genuine b1,b2 — the field
        cannot stand in for it. Together these make depth PROVABLY recoverable
        rather than confounded with the field.

   Determinism: every random draw flows through a single seeded
   `java.util.Random` (default seed 42), matching the existing RNG convention."
  (:require
   [orca.planner-fit :as pf]
   [orca.util :as util]))

;; ── defaults / knobs ─────────────────────────────────────────────────────────

(def default-seed 42)

;; True generating parameters (defaults). b0 is the use-availability intercept
;; (rare events); b1>0 / b2<0 give the peaked shelf/slope preference the real
;; fit shows (z* = -b1/(2 b2) is the preferred standardized depth).
(def default-truth
  {:b0 -1.0
   :tau-true 0.4
   :b1-true 1.3
   :b2-true -1.6})

(def n-bg-default 1500)
(def n-pres-default 250)

;; ── real geometry (reuse pf, do not reinvent) ────────────────────────────────

(defn real-centers
  "The SAME 84 RBF centers the real fit uses: candidate grid over all sailed
   points, pruned to those within center-keep-deg of an anchor."
  []
  (let [pres (pf/incident-points)
        bg (pf/background-points)
        anchors (concat pres bg)
        cand (pf/candidate-centers anchors pf/center-spacing)]
    (pf/prune-centers cand anchors pf/center-keep-deg)))

(defn grid-cells
  "Sea-cell centers [[lat lon] ...] from the real geo grid (~34.9k cells). These
   are the candidate sites where the known lambda surface is evaluated and from
   which presences/background are sampled."
  []
  (pf/sea-cells))

;; ── known RBF weights ────────────────────────────────────────────────────────

(defn known-weights
  "KNOWN true RBF weights. Either an explicit vector via {:w [..]} or drawn
   w_j ~ Normal(0, tau-true). Returns {:w w-true :tau tau-true}. Deterministic
   given `rng`."
  [rng centers {:keys [w tau-true]}]
  (let [tau (double (or tau-true (:tau-true default-truth)))]
    (if w
      {:w (mapv double w) :tau tau}
      {:w (mapv (fn [_] (* tau (.nextGaussian ^java.util.Random rng)))
                (range (count centers)))
       :tau tau})))

;; ── standardized depth on the grid ───────────────────────────────────────────

(defn depth-context
  "Build the depth standardization context over a set of [lat lon ...] points:
   log10(max(depth_m,1)) sampled from real bathy, then mean/sd. Returns
   {:bathy :logdepth-mean :logdepth-sd}. Standardizing over the same points the
   design later uses keeps z's scale consistent with pf/build-spatial-design."
  [points]
  (let [bathy (pf/load-bathy)
        lds (mapv (fn [[la lo]] (pf/logdepth (pf/sample-depth bathy (double la) (double lo))))
                  points)
        m (util/mean lds)
        sd (util/pstdev lds)]
    {:bathy bathy :logdepth-mean m :logdepth-sd sd}))

(defn z-at
  "Standardized log10 depth z(s) at (lat,lon) from a depth-context."
  ^double [{:keys [bathy logdepth-mean logdepth-sd]} ^double lat ^double lon]
  (/ (- (pf/logdepth (pf/sample-depth bathy lat lon)) (double logdepth-mean))
     (double logdepth-sd)))

;; ── raw (uncentered) RBF field and centering ─────────────────────────────────

(defn col-means-over
  "Background col_means: per-center mean of the RAW (drifted) RBF basis over a
   set of [lat lon doy] background points. Mirrors pf/build-spatial-design's
   col-means so the centered field matches the fitted design convention."
  [centers ell background]
  (let [n (count background)
        m (count centers)
        rows (mapv (fn [[la lo doy]] (pf/basis-at centers ell la lo doy)) background)]
    (mapv (fn [j] (/ (reduce + (map #(nth % j) rows)) (double n))) (range m))))

(defn field-at
  "Centered RBF field f_rbf(s) = sum_j w_j * (B_j(drifted s) - col_means_j) at
   (lat,lon,doy). doy=0.0 evaluates with no seasonal drift applied."
  [centers ell col-means w lat lon doy]
  (let [b (pf/basis-at centers ell lat lon doy)]
    (reduce + (map (fn [wj bj mj] (* (double wj) (- (double bj) (double mj))))
                   w b col-means))))

;; ── decorrelation (separability enforcement) ─────────────────────────────────

(defn- cov-over
  "Population covariance of two equal-length numeric seqs."
  ^double [a b]
  (let [n (count a)
        ma (util/mean a) mb (util/mean b)]
    (/ (reduce + (map (fn [ai bi] (* (- (double ai) ma) (- (double bi) mb))) a b))
       (double n))))

(defn decorrelate-field
  "Return adjusted weights w' such that the field they induce is EXACTLY
   (linearly) ORTHOGONAL to z over `cells`, i.e. cov(f',z)=0.

   The map w -> f = B'·w (centered RBF field at the cells) is LINEAR in w. Take
   the per-center standardized depth vector z_c (z at each center's location) as
   a probe `weight` vector; the field it induces is f_z = B'·z_c. Because both
   f = B'·w and f_z = B'·z_c live in the same linear span, subtracting the right
   multiple of z_c from w subtracts the corresponding multiple of f_z from f:
       w' = w - beta * z_c   =>   f' = f - beta * f_z .
   Choosing beta = cov(f,z) / cov(f_z,z) gives, by linearity of covariance,
       cov(f',z) = cov(f,z) - beta*cov(f_z,z) = 0 exactly,
   so the TRUTH's field carries NO linear depth information and the depth
   quadratic is the only way to fit z — the field cannot stand in for it. This
   removing-the-z-footprint trick needs no change to z or the basis. Returns
   {:w w' :beta beta :cov-before :cov-after}."
  [centers col-means w cells dctx]
  (let [doy 0.0
        zc (mapv (fn [[la lo]] (z-at dctx (double la) (double lo))) centers)
        f (mapv (fn [[la lo]] (field-at centers pf/lengthscale-km col-means w la lo doy)) cells)
        fz (mapv (fn [[la lo]] (field-at centers pf/lengthscale-km col-means zc la lo doy)) cells)
        zs (mapv (fn [[la lo]] (z-at dctx (double la) (double lo))) cells)
        cov-fz-z (cov-over fz zs)
        beta (if (zero? cov-fz-z) 0.0 (/ (cov-over f zs) cov-fz-z))
        w' (mapv (fn [wj zcj] (- (double wj) (* beta (double zcj)))) w zc)
        f' (mapv (fn [[la lo]] (field-at centers pf/lengthscale-km col-means w' la lo doy)) cells)]
    {:w w' :beta beta :cov-before (cov-over f zs) :cov-after (cov-over f' zs)}))

;; ── known intensity surface ──────────────────────────────────────────────────

(defn log-lambda-at
  "Ground-truth log lambda(s) = b0 + f_rbf(s) + b1*z + b2*z^2 at (lat,lon,doy).
   `surface` carries {:centers :ell :col-means :w :b0 :b1 :b2 :dctx}."
  [{:keys [centers ell col-means w b0 b1 b2 dctx]} lat lon doy]
  (let [f (field-at centers (double ell) col-means w lat lon doy)
        z (z-at dctx lat lon)]
    (+ (double b0) f (* (double b1) z) (* (double b2) z z))))

(defn build-surface
  "Assemble the KNOWN intensity surface map ready for evaluation/sampling.
   Inputs: real centers, the background points used for col_means, the depth
   context, true {b0 b1 b2}, and true weights w. Returns
   {:centers :ell :col-means :w :b0 :b1 :b2 :dctx}."
  [{:keys [centers col-means dctx w b0 b1 b2]}]
  {:centers centers :ell pf/lengthscale-km :col-means col-means
   :w w :b0 (double b0) :b1 (double b1) :b2 (double b2) :dctx dctx})

;; ── route corridors + availability (effort) sampling ─────────────────────────

(defn route-corridors
  "Default corridors: a vector of {:waypoints [[lat lon]...] :width-km}. These
   trace the main sailed lanes (Galicia coast, Portuguese coast, Gulf of Cadiz /
   Strait approach). Mixing probabilities pi are supplied separately so the
   route-choice distribution is settable."
  [_config]
  [{:waypoints [[43.5 -9.5] [43.0 -9.0] [42.0 -9.0]] :width-km 40.0}
   {:waypoints [[42.0 -9.0] [40.0 -9.3] [38.7 -9.5]] :width-km 40.0}
   {:waypoints [[38.7 -9.4] [37.1 -8.7] [36.1 -6.0] [36.1 -5.4]] :width-km 45.0}])

(defn- corridor-point
  "Sample one [lat lon] near a corridor: pick a leg uniformly by length, a point
   along it, and jitter perpendicular by up to ~width-km (expressed in degrees)."
  [rng {:keys [waypoints width-km]}]
  (let [legs (vec (partition 2 1 waypoints))
        [[la1 lo1] [la2 lo2]] (nth legs (.nextInt ^java.util.Random rng (count legs)))
        t (.nextDouble ^java.util.Random rng)
        la (+ la1 (* t (- la2 la1)))
        lo (+ lo1 (* t (- lo2 lo1)))
        jit-deg (/ (double width-km) 111.0)
        jla (* jit-deg (- (.nextDouble ^java.util.Random rng) 0.5))
        jlo (* jit-deg (- (.nextDouble ^java.util.Random rng) 0.5))]
    [(+ la jla) (+ lo jlo)]))

(defn- pick-index
  "Index drawn from a categorical pmf `probs` using rng (inverse-CDF)."
  ^long [rng probs]
  (let [u (.nextDouble ^java.util.Random rng)]
    (loop [i 0 acc 0.0]
      (let [acc' (+ acc (double (nth probs i)))]
        (if (or (<= u acc') (= i (dec (count probs)))) i (recur (inc i) acc'))))))

(defn- normalize-pi [pi n]
  (let [pi (if pi (mapv double pi) (vec (repeat n (/ 1.0 n))))
        s (reduce + pi)]
    (mapv #(/ % s) pi)))

(defn sample-background
  "Availability (effort) sample [[lat lon doy] ...].

   :mimic mode (default) passes the REAL pf/background-points through, so the
   synthetic data inherits the real route-choice + depth distribution.

   :synthetic mode draws n-bg points from the corridor MIXTURE: a corridor is
   chosen per point from pi (settable route-choice distribution), a point is
   sampled along it (effort ∝ corridor density), and a day-of-year is drawn
   uniformly so the seasonal drift is exercised. Deterministic given rng."
  [rng {:keys [mode corridors pi n-bg doy-fixed]}]
  (case (or mode :mimic)
    :mimic (pf/background-points)
    :synthetic
    (let [corrs (or corridors (route-corridors {}))
          pis (normalize-pi pi (count corrs))
          n (long (or n-bg n-bg-default))]
      (mapv (fn [_]
              (let [r (pick-index rng pis)
                    [la lo] (corridor-point rng (nth corrs r))
                    doy (double (or doy-fixed
                                    (* 365.0 (.nextDouble ^java.util.Random rng))))]
                [la lo doy]))
            (range n)))))

;; ── presence (use) sampling by use-availability thinning ─────────────────────

(defn sample-presences
  "Draw incident (y=1) points by use-availability thinning over the realized
   background (the availability sample). Each candidate background site s is
   accepted with probability proportional to exp(f_rbf(s)+depth(s)) — i.e. the
   non-intercept part of lambda(s) relative to the effort that placed it there —
   normalized so the most attractive site accepts with probability 1. This is
   rejection sampling of use ∝ lambda/effort. Returns [[lat lon doy] ...] of
   length ~ n-pres. Deterministic given rng."
  [rng {:keys [surface background n-pres]}]
  (let [{:keys [centers ell col-means w b1 b2 dctx]} surface
        scored (mapv (fn [[la lo doy]]
                       (let [f (field-at centers (double ell) col-means w
                                         (double la) (double lo) (double doy))
                             z (z-at dctx (double la) (double lo))]
                         (+ f (* (double b1) z) (* (double b2) z z))))
                     background)
        mx (apply max scored)
        n (long (or n-pres n-pres-default))]
    (loop [acc (transient []) guard 0]
      (if (or (>= (count acc) n) (> guard (* 200 n)))
        (persistent! acc)
        (let [i (.nextInt ^java.util.Random rng (count background))
              p (Math/exp (- (double (nth scored i)) mx))]
          (if (< (.nextDouble ^java.util.Random rng) p)
            (recur (conj! acc (nth background i)) (inc guard))
            (recur acc (inc guard))))))))

;; ── design assembly (schema-compatible with spatial.stan) ────────────────────

(defn build-sim-design
  "Reproduce pf/build-spatial-design's column-centering + depth z,z2 standard-
   ization on the SIMULATED points. presences (y=1) then background (y=0).

   Returns the SAME map shape pf/fit-spatial-chains consumes —
   {:N :M :y :Bsp :z :z2 ...} — PLUS :truth carrying the generating params, the
   per-cell ground-truth lambda, and the depth peak z* = -b1/(2 b2)."
  [{:keys [presences background centers surface cells]}]
  (let [ell pf/lengthscale-km
        {:keys [w b0 b1 b2 dctx col-means]} surface
        m (count centers)
        n-bg (count background)
        pres-raw (mapv (fn [[la lo doy]] (pf/basis-at centers ell la lo doy)) presences)
        bg-raw (mapv (fn [[la lo doy]] (pf/basis-at centers ell la lo doy)) background)
        center-rows (fn [rows]
                      (mapv (fn [row]
                              (mapv (fn [j] (- (double (nth row j))
                                               (double (nth col-means j))))
                                    (range m)))
                            rows))
        bsp (into (center-rows pres-raw) (center-rows bg-raw))
        y (into (vec (repeat (count pres-raw) 1)) (vec (repeat n-bg 0)))
        ;; depth covariate, standardized over the COMBINED sim points
        ld (fn [[la lo]] (pf/logdepth (pf/sample-depth (:bathy dctx) (double la) (double lo))))
        pres-ld (mapv ld presences)
        bg-ld (mapv ld background)
        all-ld (into pres-ld bg-ld)
        ld-mean (util/mean all-ld)
        ld-sd (util/pstdev all-ld)
        ->z (fn [v] (/ (- (double v) ld-mean) ld-sd))
        z (into (mapv ->z pres-ld) (mapv ->z bg-ld))
        z2 (mapv #(* (double %) (double %)) z)
        lambda-cells (mapv (fn [[la lo]]
                             (Math/exp (log-lambda-at surface (double la) (double lo) 0.0)))
                           cells)
        z-of-cell (mapv (fn [[la lo]] (z-at dctx (double la) (double lo))) cells)
        z-star (/ (- (double b1)) (* 2.0 (double b2)))]
    {:N (count bsp) :M m :y y :Bsp bsp :z z :z2 z2
     :centers centers :ell ell
     :logdepth-mean ld-mean :logdepth-sd ld-sd
     ;; the generating surface, kept so the recovery harness can reconstruct
     ;; lambda-hat per cell with the SAME field math (log-lambda-at) but the
     ;; FITTED parameters swapped in (reuse, do not re-derive the formula).
     :surface surface
     :truth {:b0 (double b0) :b1 (double b1) :b2 (double b2)
             :w-true w :tau (:tau surface)
             :z-star z-star
             :lambda-true-per-cell lambda-cells
             :z-of-cell z-of-cell
             :cells cells}}))

;; ── scale diagnostic (separability evidence) ─────────────────────────────────

(defn depth-vs-field-scale
  "Quantify that z(s) varies at a FINER spatial scale than the RBF field. For a
   random sample of nearby cell pairs (within ~`near-km`), report the mean
   absolute z-difference vs the mean absolute field-difference, each normalized
   by the variable's own sd over the grid. A larger normalized short-range
   variation for z than for the field means depth carries fine-scale structure
   the ell=150 km field cannot represent. Returns
   {:z-rough :field-rough :sharper? :cov-fz}."
  [rng {:keys [centers col-means w cells dctx near-km n-pairs]}]
  (let [near (double (or near-km 60.0))
        npairs (long (or n-pairs 2000))
        doy 0.0
        zs (mapv (fn [[la lo]] (z-at dctx (double la) (double lo))) cells)
        fs (mapv (fn [[la lo]] (field-at centers pf/lengthscale-km col-means w la lo doy)) cells)
        z-sd (util/pstdev zs)
        f-sd (let [s (util/pstdev fs)] (if (pos? s) s 1.0))
        nc (count cells)
        pairs (loop [acc (transient []) g 0]
                (if (or (>= (count acc) npairs) (> g (* 50 npairs)))
                  (persistent! acc)
                  (let [i (.nextInt ^java.util.Random rng nc)
                        j (.nextInt ^java.util.Random rng nc)
                        [lai loi] (nth cells i)
                        [laj loj] (nth cells j)
                        d (pf/haversine-km (double lai) (double loi) (double laj) (double loj))]
                    (if (and (not= i j) (<= d near))
                      (recur (conj! acc [i j]) (inc g))
                      (recur acc (inc g))))))
        absdiff (fn [xs] (mapv (fn [[i j]]
                                 (abs (- (double (nth xs i)) (double (nth xs j)))))
                               pairs))
        z-rough (/ (util/mean (absdiff zs)) z-sd)
        f-rough (/ (util/mean (absdiff fs)) f-sd)
        zbar (util/mean zs) fbar (util/mean fs)
        cov-fz (/ (reduce + (map (fn [fi zi] (* (- fi fbar) (- zi zbar))) fs zs)) (double nc))]
    {:z-rough z-rough :field-rough f-rough
     :sharper? (> z-rough f-rough) :cov-fz cov-fz}))

;; ── top-level configs ────────────────────────────────────────────────────────

(defn- assemble
  "Shared pipeline: centers, depth-context over availability, weights, surface,
   background, presences, design. `decorrelate?` toggles the separability
   enforcement (orthogonalizing the truth field against z)."
  [{:keys [seed truth bg-spec n-pres decorrelate?] :as _opts}]
  (let [rng (java.util.Random. (long (or seed default-seed)))
        centers (real-centers)
        cells (grid-cells)
        background (sample-background rng (or bg-spec {:mode :mimic}))
        dctx (depth-context (mapv (fn [[la lo _]] [la lo]) background))
        col-means (col-means-over centers pf/lengthscale-km background)
        t (merge default-truth truth)
        {:keys [w tau]} (known-weights rng centers t)
        w (if decorrelate?
            (:w (decorrelate-field centers col-means w cells dctx))
            w)
        surface (assoc (build-surface {:centers centers :col-means col-means :dctx dctx
                                       :w w :b0 (:b0 t) :b1 (:b1-true t) :b2 (:b2-true t)})
                       :tau tau)
        presences (sample-presences rng {:surface surface :background background
                                         :n-pres (or n-pres n-pres-default)})]
    (build-sim-design {:presences presences :background background
                       :centers centers :surface surface :cells cells})))

(defn mimic-config
  "Simulate from the known surface using the REAL data's availability
   distribution (pass-through pf/background-points). Field is NOT decorrelated
   from z, so this mirrors the real, potentially-confounded setting."
  [opts]
  (assemble (merge {:bg-spec {:mode :mimic} :decorrelate? false} opts)))

(defn separable-config
  "THE CRUX. A sim config where depth is PROVABLY recoverable, not absorbed by
   the field. Two enforcements (see ns docstring): (1) z(s) is the real
   shelf/slope log-depth, which varies at a SHARPER scale than ell=150 km, and
   (2) the true field weights are decorrelated from z over the grid so the
   field carries no linear depth information (cov(f_rbf,z) ~ 0). A later two-arm
   experiment can then show the depth quadratic survives adding the RBF field."
  [opts]
  (assemble (merge {:bg-spec {:mode :mimic} :decorrelate? true} opts)))

(defn simulate
  "Top-level entry. `opts` keys:
     :seed         RNG seed (default 42)
     :config       :mimic (default) | :separable
     :truth        override {:b0 :b1-true :b2-true :tau-true}
     :bg-spec      availability spec (see sample-background); default :mimic mode
     :n-pres       number of incident points
   Returns {:data {:N :M :y :Bsp :z :z2} :truth {...} :full <full design map>}."
  [opts]
  (let [cfg (case (:config opts :mimic)
              :separable (separable-config opts)
              (mimic-config opts))]
    {:data (select-keys cfg [:N :M :y :Bsp :z :z2])
     :truth (:truth cfg)
     :full cfg}))
