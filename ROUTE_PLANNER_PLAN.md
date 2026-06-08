# Orca Route Risk Planner — Implementation Plan

A fully Bayesian, no-live-backend route planner: draw a sea passage on a map, configure your
boat, and see the **posterior distribution of orca interaction probability** — per segment
(colour-coded track) and for the whole route — with real-time updates as you edit.

> [!IMPORTANT]
> **This is a standalone web app, not a blog embed.** It lives in its own top-level
> `route-planner/` directory with its own `index.html` entry point and its own committed data
> artifacts. The existing blog (`blogpost/index.html`, `methodology.html`, `posterior_draws.json`)
> is **untouched**. The ClojureScript is **not** inlined into the HTML: it is split into separate
> `.cljs` namespace files under `route-planner/src/` and loaded by Scittle via
> `<script type="application/x-scittle" src="…">`, so the formatter (`standard-clojure-style`),
> `clj-kondo`, and headless tests can run over real source files. (Scittle is not exactly Clojure,
> so a few clj-kondo findings on browser/Reagent interop are expected and tolerated; keep them
> minimal and documented.)

Two layers make this work:

1. A **live risk heatmap** over the orca zone that re-tints in real time as boat/condition
   parameters change.
2. **Route segment + whole-route risk**, each propagating all posterior draws through a
   Poisson exposure model so absolute numbers carry a credible interval.

The headline modelling idea: **position is a first-class predictor**. It enters the model in two
complementary ways — through *abstract* geographic predictors (`depth_ord`, `distance_ord`) and
through a *spatial smooth* `f(lat,lon)` that captures residual hotspots (Strait of Gibraltar, the
Galician coast) **not** explained by depth/distance. Both are estimated jointly and both
propagate full posterior uncertainty.

---

## Implementation status (built 2026-06-07/08) — COMPLETE

All nine phases are implemented and verified. The app is feature-complete; nothing is committed
yet (the project's no-commit-until-asked convention). Built by running one subagent per phase with
the main session orchestrating.

**What shipped**

- Standalone app at `route-planner/`: `index.html` shell + `src/planner_core.cljs`
  (ns `orca.planner.core`, pure math, no DOM) + `src/planner_app.cljs` (ns `orca.planner.app`,
  Leaflet/Reagent UI). The blog is untouched.
- Data artifacts: `route-planner/posterior_planner.json` (500 draws × 80 columns + `spatial` block)
  and `route-planner/geo_grid.json` (34,933 sea cells, 0.1°, integer-tenths keys).
- Tooling under `orca_clj/`: `src/orca/planner_fit.clj` (the spatial fit), `stan/spatial.stan`,
  `scripts/gen_geo_grid.clj` (Babashka geo-grid generator), the `:rbf` block in `config.edn`, and
  two headless gates `test/orca/planner_smoke.clj` + `test/orca/planner_app_smoke.clj` with deps
  aliases `:planner-smoke` and `:app-smoke`.
- Docs: `route-planner/README.md`, plus "Route planner" sections in `orca_clj/README.md` and
  `orca_clj/AGENTS.md`.

**The one load-bearing deviation: the spatial model could not be a joint refit.** The plan assumed
M3 and the RBF smooth could be refit jointly on incident-vs-uneventful data. They cannot: the
**uneventful reports have no coordinates** (their `summary_lat`/`summary_long` are empty for all
rows; only the 216 incidents carry positions). Dropping coordinate-less rows collapses the fit set
to all-incidents (y ≡ 1), which is unidentifiable. So the spatial term is fit **separately** as a
**Bayesian presence/background (pseudo-absence) model**: 216 incident locations (y=1) against 3000
sea-cell pseudo-absences sampled from the geo grid (y=0, seed 42), RBF basis, in `stan/spatial.stan`
(ns `orca.planner-fit`). The 30 base attribute/depth/distance/boat coefficients are **copied
unchanged from `blogpost/posterior_draws.json`**; the spatial `w` columns are appended additively
(layout 30 → 80, `coef_start` 30, `n_basis` 50). The smooth is added as an offset to the M3 logit.
This keeps `posterior_planner.json` in exactly the format Phases 3 to 8 expect (per-draw `w` columns
plus a `spatial` block), so no downstream phase changed. It is documented in the JSON's
`spatial_note` and the in-app caveat. The plan text below (intro line "estimated jointly", §1, §2.2,
Phase 1) describes the original joint-refit intent; read it together with this note.

**Consequence: risk saturates near the real hotspots by design.** The presence/background fit is
faithful, and incidents concentrate almost entirely at the Strait of Gibraltar and the
Galician/Portuguese coast, so the posterior-mean offset there is large (f̄ Gibraltar +8.9,
Galicia +8.4, open Atlantic −4.8/−2.2). Inside those hotspots the per-transit probability pins near
1 regardless of boat choices, which is the honest reading of the data. The heatmap colouring and
risk ordering are correct; the Playwright risk checks (CI ordering, Motoring vs Sailing, antifoul
reactivity) use empirically chosen **moderate-water** routes so the contrasts are non-degenerate;
the in-app `#caveat` states the relative-to-`ref_nm` framing and the saturation.

**Verification.** Two JVM-Playwright headless gates, run from `orca_clj/`, both green:
`clojure -X:planner-smoke` (core math: base-logit parity vs the blog calculator to 4.4e-16, Poisson
round-trip identity, CI ordering, monotone exposure, spatial hotspot > open ocean) and
`clojure -X:app-smoke` (the full §4 suite, Checks #1 to #10, parity #10 within 0.0084%). Both serve
the **repo root** over HTTP (the app fetches `../orca_reportlist.json`) via the JDK
`SimpleFileServer`, modelled on the existing `orca.blog-smoke`.

**Two build notes worth keeping.** (1) Scittle is an interpreter, so hot loops are costly: a
predictor change first measured ~272 to 680 ms. The heatmap re-tint itself is only ~6 ms (because
`S(cell)` is precomputed once into `static-cells` and only the dynamic scalar `D` plus one sigmoid
per cell run on update); the cost was `refresh-route!` recomputing the 50-term RBF basis per draw.
Precomputing a per-point plan once and then looping the 500 draws (`point-plan`/`plan-transit-p` in
`planner_core.cljs`, bit-identical results) brought it to ~38 ms. (2) A Babashka/SCI nested `loop`
inside one large closure stalled `gen_geo_grid.clj` for 67 minutes; hoisting the hot raster loop to
a top-level `defn` fixed it (the algorithm was never the problem).

Per-phase results are recorded inline as **"Built:"** notes under each phase below.

---

## 0. Why this differs from the existing calculator (read first)

The published blog calculator (`blogpost/index.html`) uses `posterior_draws.json` — the
**non-spatial** model `m3.stan` with 30 parameters. We verified its facts against the codebase:

- **30-parameter layout** `["alpha","b_depth","b_autopilot","b_speed","b_length","b_distance","b_wind","b_sea","s_0..s_4","a_0..a_7","h_0..h_2","r_0..r_5"]`.
- **`depth_ord` and `distance_ord` are independent predictors** (separate columns, separately standardized).
- **Standardization** means/sds live in `posterior_draws.json → standardization` (`depth_ord` mean 1.708 sd 0.951; `distance_ord` mean 1.355 sd 1.147; etc.).
- **King–Zeng offset** `logit(base_rate) − logit(sample_rate)`, `sample_rate = 0.327273`.
- **Daylight adjustment** is multiplicative on probability: `{"Day" 1.182, "Night" 0.662, "Average" 1.0}`.
- The calculator's per-draw `logit` matches `m3.stan` exactly, then `p = sigmoid(logit) · daylight_adj`.

### Two problems this plan fixes

**(a) The non-spatial model has no notion of "where".** Geography enters only via depth/distance.
Worse, the fitted coefficients are `b_depth = +0.95` (P>0 = 1.00) and `b_distance = +0.19`
(P>0 = 0.91) — i.e. *deeper* and *further offshore* read as **higher** risk. A heatmap built on
those alone paints the open Atlantic red and the coastal shelves green, the **opposite** of the
real incident geography, and the original plan's "Gibraltar = depth 3 / distance 1 = dangerous"
example is inconsistent with its own model. → **Fixed by adding the spatial smooth** (Phase 1):
the smooth absorbs the true hotspots, the depth/distance slopes re-estimate as residual effects,
and the map reflects reality.

**(b) The model predicts P(incident) per *report*, with no passage length.** The planner needs
per-segment and whole-route risk for routes of arbitrary length. → **Fixed by a Poisson exposure
model** (Phase 3): reinterpret the per-report probability as the interaction probability over a
**reference transit** of `ref_nm` (default 100 nm, user-adjustable), convert to a rate, and add
rates across segments. Absolute numbers are therefore *relative to the reference length* — the UI
must say so. They are **not** independently calibrated absolute probabilities.

### Decisions locked for this plan

| Decision | Choice | Rationale |
|---|---|---|
| App packaging | **Standalone `route-planner/` app** (own `index.html` + `src/*.cljs`) | A separate web app, not a blog page; Scittle loads external `.cljs` so they lint/format/test |
| Spatial model | **Refit `m3.stan` + 2D RBF smooth** → new `posterior_planner.json` | Makes position a real predictor; keeps the published blog file/numbers untouched |
| Spatial basis | **Low-rank Gaussian RBF** (fixed centers + lengthscale + shrinkage prior) | Trivially & reproducibly evaluable in ClojureScript; folds into the fast heatmap update |
| Heatmap draws | **Posterior mean** coefficients (one logit/cell) | A colour layer doesn't need 500 draws; reserve full posterior for route/segment numbers |
| Route/segment draws | **All draws**, Poisson additive λ | Preserves parameter correlations → honest CI |
| Bathymetry pipeline | **GDAL offline → text → Clojure reader** (not netCDF-in-Babashka) | netCDF is binary; no pure-bb reader; GDAL does the binary lifting once |
| Daylight adj | Multiplicative on P (as in calculator) | Exact parity with the published calculator at `segment_nm = ref_nm` |

---

## 1. Open questions (start simple, revisit after first render)

> [!IMPORTANT]
> **Segment subdivision.** Risk is evaluated per waypoint-to-waypoint leg. A long leg crossing the
> shelf edge or a hotspot boundary will be mis-sampled if evaluated only at its midpoint.
> **MVP:** subdivide every leg into ≤ `seg_step_nm` (default 25 nm) sub-segments; each sub-segment
> looks up depth/distance/`f` at its own midpoint. This is cheap (the per-draw math is the same)
> and removes the "one averaged colour per 200 nm leg" problem from the start.

> [!NOTE]
> **Reference transit length.** Default **100 nm**, exposed as a slider. Affects absolute numbers
> only, not relative colouring or ordering.

> [!NOTE]
> **Waypoint editing.** MVP: click to add, click-on-marker to delete. Drag deferred to V2.

> [!NOTE]
> **RBF center placement & lengthscale.** Start with a regular grid over the bbox pruned to centers
> within `~1.5×spacing` of ≥1 data point; lengthscale `ℓ ≈ 1.25×spacing`. These are tunable in
> Phase 1 against LOO / posterior-predictive checks. Keep rank low (~24–40 centers) to avoid
> overfitting 216 incidents.

---

## 2. Mathematical model (single source of truth)

All runtime code (heatmap, segments, route) derives from these definitions. Implement **once** in
the core module (Phase 3) and call everywhere — no second copy.

### 2.1 Standardization & helpers

```
z(x; key)        = (x − mean[key]) / sd[key]          ; keys: depth_ord, distance_ord, speed_ord,
                                                        ;       boat_length_ord, wind_ord, sea_state_ord
                                                        ; autopilot is used RAW (0/1), not standardized
sigmoid(x)       = 1 / (1 + e^−x)
king_zeng(base)  = logit(base) − logit(sample_rate)   ; sample_rate from JSON (0.327273)
daylight_adj     = {Day 1.182, Night 0.662, Average 1.0}
```

### 2.2 Spatial smooth `f(lat,lon)` — the hotspot term

Fixed RBF centers `c_j=(lat_j,lon_j)`, lengthscale `ℓ`, and training column-means `m_j` are stored
in `posterior_planner.json → spatial`. For a draw's spatial weights `w` (extra columns):

```
B_j(lat,lon) = exp( − dist²((lat,lon), c_j) / (2 ℓ²) )      ; dist = haversine (km) or planar-deg, must match Phase 1
f(lat,lon; w) = Σ_j  w_j · ( B_j(lat,lon) − m_j )           ; centered ⇒ sum-to-zero residual smooth
```

`m_j` subtraction reproduces the training-time centering exactly, so `f` is identified relative to
the intercept without needing the training data at runtime.

### 2.3 Per-draw transit probability (THE unified function)

For one posterior draw `d`, at geographic point `(lat,lon)` with grid lookup
`{depth_ord, distance_ord}`, boat params, and passage params:

```
logit_d =  d[alpha]
         + d[b_depth]    · z(depth_ord;   depth_ord)
         + d[b_distance] · z(distance_ord; distance_ord)
         + d[b_autopilot]· autopilot                      ; raw 0/1
         + d[b_speed]    · z(speed;  speed_ord)
         + d[b_length]   · z(length; boat_length_ord)
         + d[b_wind]     · z(wind;   wind_ord)
         + d[b_sea]      · z(sea;    sea_state_ord)
         + d[ sailing_idx(mode)   ]
         + d[ antifoul_idx(colour)]
         + d[ hull_idx(colour)    ]
         + d[ rudder_idx(type)    ]
         + f(lat,lon; d[spatial…])                         ; ← position as predictor (hotspots)
         + king_zeng(base_rate)

p_transit_d = min(0.9999, sigmoid(logit_d) · daylight_adj[daylight])
```

`sailing_idx` etc. are computed from `posterior_planner.json → categories` + `layout`
(e.g. `sailing_idx(mode) = 8 + position(mode in categories.sailing_mode)`).

> **One function, two callers.** `predict-transit-p` is the *only* place the logit is assembled.
> `segment-risk` and `route-risk` both call it. This removes the original plan's divergent
> `predict-segment-p` vs undefined `predict-transit` paths.

### 2.4 Poisson exposure → segment & route risk

```
λ_ref_d        = −ln(1 − p_transit_d)                      ; rate over one reference transit (ref_nm)
λ_seg_d(nm)    = λ_ref_d · (nm / ref_nm)
p_seg_d(nm)    = 1 − e^(−λ_seg_d)                          ; per-draw segment probability

segment_risk(nm)  = percentiles over d of p_seg_d(nm)     ; {median@.50, lo89@.055, hi89@.945}

λ_route_d      = Σ_segments λ_seg_d(nm_segment)            ; Poisson additivity, per draw
p_route_d      = 1 − e^(−λ_route_d)
route_risk     = percentiles over d of p_route_d          ; {median, lo89, hi89}
```

> **Exact-parity invariant (use as a test):** at `nm = ref_nm`,
> `p_seg_d = 1 − e^(−(−ln(1−p_transit_d))) = p_transit_d`. So a single 100 nm segment with
> `ref_nm = 100` reproduces the calculator's per-draw probability **exactly** (up to float). This
> is a hard equality check, not "±20%".

### 2.5 Fast heatmap factoring (the key to real-time updates)

Split the **mean-coefficient** logit into a static per-cell part and one dynamic scalar:

```
STATIC  (precompute once per cell at load; depends only on geography):
  S(cell) = mean[b_depth]    · z(depth_ord_cell;   depth_ord)
          + mean[b_distance] · z(distance_ord_cell; distance_ord)
          + f(lat_cell, lon_cell; mean[spatial…])           ; ← spatial hotspot, static per cell

DYNAMIC (one scalar; recompute only when a boat/condition param changes):
  D = mean[alpha]
    + mean[b_autopilot]·autopilot + mean[b_speed]·z(speed) + mean[b_length]·z(length)
    + mean[b_wind]·z(wind) + mean[b_sea]·z(sea)
    + mean[sailing_idx] + mean[antifoul_idx] + mean[hull_idx] + mean[rudder_idx]
    + king_zeng(base_rate)

INTENSITY (per cell, per update):
  intensity(cell) = sigmoid( D + S(cell) ) · daylight_adj[daylight]
```

So **the geography fixes the spatial pattern `S(cell)`; the predictors collapse into the single
scalar `D` that slides every cell up/down the sigmoid together.** Changing a predictor =
recompute one scalar + one `sigmoid` per cell. ~4,000 sub-sampled cells update in well under a
frame. This is exactly the requested behaviour: *position sets the pattern, predictors change the
intensity.*

> `sigmoid(mean logit) ≠ mean(sigmoid)`; acceptable for a colour layer. The route/segment **numbers**
> use the full posterior (§2.4), not this point estimate. Document this split in-app.

---

## 3. Phased implementation

Phases **1 (spatial refit)** and **2 (geo grid)** are independent and can run in parallel; both
feed **Phase 3 (core math)**. Phases 4–8 build the app on top. Every app phase ends with a
Playwright gate (§4).

```
Phase 1 ─┐
         ├─► Phase 3 ─► Phase 4 ─► Phase 5 ─► Phase 6 ─► Phase 7 ─► Phase 8 ─► Phase 9
Phase 2 ─┘
```

---

### Phase 1 — Spatial model refit (`orca_clj`) → `posterior_planner.json`

**Goal:** add the residual spatial smooth, refit, export an additively-extended posterior file.
**This is the "model position nicely" phase.**

| Step | Action | File(s) |
|---|---|---|
| 1.1 | **Extract coordinates.** Pull `lat`/`long` for every incident **and** uneventful report; join to the existing prepared rows. Report and drop rows missing coords (log the count). | `orca_clj/src/orca/prepare.clj` |
| 1.2 | **Define RBF centers + lengthscale.** Regular grid over `25–50°N, 20°W–5°E` at `spacing` (start 2.5°); prune centers with no data within `1.5×spacing`; set `ℓ = 1.25×spacing`. Make `spacing`/`ℓ` config. | `orca_clj/resources/config.edn`, `prepare.clj` |
| 1.3 | **Build basis.** Compute raw `B[i,j] = exp(−dist²(row_i, c_j)/(2ℓ²))` for all training rows; compute column means `m_j`; center → `Bsp = B − m`. Pick **one** distance metric (haversine-km recommended) and record it. | `prepare.clj` |
| 1.4 | **Extend Stan model.** Add `int M; matrix[N,M] Bsp;` data; `vector[M] w; real<lower=0> tau;` params; `tau ~ normal(0,1) T[0,]; w ~ normal(0, tau);` and `+ Bsp[i] * w` in both `logit_p` and `log_lik`. | `orca_clj/stan/m3_spatial.stan` [NEW, copy of `m3.stan`] |
| 1.5 | **Fit** via the existing CmdStan/nix pipeline. Check divergences, R̂, ESS; compare LOO vs non-spatial `m3` (spatial should improve or match). | `orca_clj/src/orca/model.clj` |
| 1.6 | **Export** `posterior_planner.json`: same blocks as `posterior_draws.json` (`n`, `layout`, `categories`, `standardization`, `sample_rate`, `draws`) **plus** appended `w` columns in `layout`/`draws` **plus** a `spatial` block: `{"metric":"haversine_km","centers":[[lat,lon]…],"lengthscale":ℓ,"col_means":[m_j…],"coef_start":<idx>,"n_basis":M}`. | `model.clj` → `route-planner/posterior_planner.json` [NEW] |

**Done when:** `posterior_planner.json` loads; sampler diagnostics clean; the posterior-mean
spatial surface `f̄(lat,lon)` peaks over known hotspots (Strait of Gibraltar ~36°N 5.5°W; Galician/
Portuguese coast) and is ~0 in the open Atlantic. **Verify with a quick offline heatmap of `f̄`
before building any UI** — this is the make-or-break check for the whole "position" idea.

**Fallback (only if refit is blocked):** an empirical spatial log-density from incident vs
uneventful counts, smoothed with the same RBF kernel, used as a fixed offset (no posterior
uncertainty on the spatial part). Inferior — note it loudly in-app — but unblocks the UI.

> **Built (reworked):** the joint refit was blocked, because the uneventful reports have no
> coordinates (see the Implementation-status note above), and the empirical fallback would have hit
> the same wall (it also needs uneventful positions). Instead the spatial term is a **Bayesian
> presence/background** fit, which keeps full posterior uncertainty on `w`. Files:
> `orca_clj/stan/spatial.stan` (`data {N, M, y, Bsp}`, `params {b0, w, tau}`,
> `y ~ bernoulli_logit(b0 + Bsp·w)`), `orca_clj/src/orca/planner_fit.clj` (ns `orca.planner-fit`),
> `config.edn :rbf {:bbox … :spacing 2.5}`. Presences = 216 incidents; background = 3000 sea cells
> sampled from `geo_grid.json` (seed 42); N = 3216. Centers = a 2.5° grid pruned to within 3.75° of
> an incident → **M = 50**; metric haversine_km; ℓ = 346.875 km; `col_means` centered on the
> background rows. Convergence clean: max R̂ 1.0023, min ESS 3675, 0 divergences. The 30 base
> columns are copied **byte-identical** from `posterior_draws.json`; `w_0..w_49` appended →
> `layout` length 80, `spatial` block `{metric, centers[50], lengthscale 346.875, col_means[50],
> coef_start 30, n_basis 50}` plus a `spatial_note`. Make-or-break surface check PASS: f̄ Gibraltar
> +8.90, Galicia +8.41, mid-Atlantic −4.83, open ocean (44,−15) −2.16.

---

### Phase 2 — Geo grid generation → `route-planner/geo_grid.json`

**Goal:** a static raster of `depth_ord` + `distance_ord` for every 0.1° sea cell in the bbox.
**De-risked:** GDAL (offline, once) does all binary/netCDF work; Clojure only reads text.

Region **25–50°N, 20°W–5°E** at **0.1°** → 250 × 150 = 37,500 *total* cells (land omitted ⇒ fewer
stored). Bins (from `config.edn`, unchanged): depth `≥−20→0, −20..−40→1, −40..−200→2, ≤−200→3`;
distance(nm) `0–2→0, 2–5→1, 5–10→2, >10→3`.

| Step | Action | Tool |
|---|---|---|
| 2.1 | **Get bathymetry subset.** Download a GEBCO 2024 bounding-box subset (small netCDF) from the GEBCO download tool. | browser/curl |
| 2.2 | **netCDF → text, offline.** `gdal_translate -of XYZ gebco_subset.nc gebco.xyz` (lon lat depth per line). GDAL handles the binary format — **not** Babashka. | GDAL CLI |
| 2.3 | **Bin depth → `depth_ord`** per 0.1° cell (sample/average XYZ points falling in each cell). | `orca_clj/scripts/gen_geo_grid.clj` (Babashka — reads plain text only) |
| 2.4 | **Distance → `distance_ord`.** Min haversine distance from each cell center to Natural Earth 1:110m coastline vertices; map to nm bins. Pure Clojure. | `gen_geo_grid.clj` |
| 2.5 | **Emit** compact JSON (land cells dropped): `{"bounds":…,"resolution":0.1,"key":"round(lat*10),round(lon*10)","cells":{"361,-59":{"d":3,"c":1}, …}}`. **Key by integer tenths** (`round(lat*10)`) so generation and runtime lookup are byte-identical — no float-string mismatch. | `gen_geo_grid.clj` |

**Output:** ~sea-cell count × ~20 B ≈ **~700 KB** (gzip → ~120 KB). Static; committed.

**Done when:** spot checks pass — Strait of Gibraltar cell deep (`d=3`) and near-coast (`c≤1`);
mid-Atlantic cell `d=3,c=3`; a Bay-of-Biscay shelf cell shows `d≤2`. **Do not** assert "offshore =
dangerous" here (that's the spatial smooth's job, not the grid's).

> **Built:** `orca_clj/scripts/gen_geo_grid.clj` (Babashka). Bathymetry came from the **NOAA ERDDAP
> ETOPO** CSV over the bbox (no GEBCO download and no GDAL needed; both stayed available as the
> fallback). Distance uses **Natural Earth coastline** GeoJSON, with a per-cell spatial hash so only
> coastline vertices within ~10 nm are tested (distance bins saturate above 10 nm). Output
> `route-planner/geo_grid.json`: **34,933 sea cells**, 0.82 MB, integer-tenths keys. Spot checks
> PASS: mid-Atlantic (38,−18) `{d 3, c 3}`; Biscay shelf (45.5,−3) `{d 2, c 3}`; Gibraltar (36,−5.5)
> `{d 3, c 1}`; central-Spain land (40,−4) absent. The original 67-minute stall was an SCI nested-loop
> pathology, fixed by hoisting the raster loop to a top-level `defn` (runtime ~0.5 s).

---

### Phase 3 — Core math module (pure ClojureScript, headless-testable)

**Goal:** implement §2 once in its **own namespace** `orca.planner.core` (file
`route-planner/src/planner_core.cljs`), with no DOM/Leaflet/Reagent dependency, runnable headless.
This file must format with `standard-clojure-style` and lint clean under `clj-kondo`.

| Step | Action |
|---|---|
| 3.1 | `derive-config` from `posterior_planner.json`: build `sailing/antifoul/hull/rudder` name→layout-index maps, standardization accessor, `sample_rate`, and parse the `spatial` block (centers, ℓ, `col_means`, `coef_start`, `n_basis`, metric). |
| 3.2 | `spatial-basis` `(cfg lat lon) → [B_j…]` and `f` `(cfg w lat lon)`; precompute `mean-draw` (elementwise mean of `draws`) for the heatmap. |
| 3.3 | `predict-transit-p` (§2.3) — the single unified logit assembler. |
| 3.4 | `segment-risk`, `route-risk` (§2.4) with shared `percentile`. |
| 3.5 | `heatmap-static` `(cfg mean-draw cell)→S` and `heatmap-intensity` `(D, S, daylight)` (§2.5); `dynamic-scalar` `(cfg mean-draw boat passage)→D`. |

**Done when (headless asserts):**
- **Parity:** single 100 nm segment, `ref_nm=100`, identical params ⇒ `segment_risk.median` equals the §0 calculator formula on the *same 30 base columns* to < 1e−6 (run the spatial term to 0 by using a point with `f≈0`, or compare per-draw `p_transit` directly). 
- **CI ordering:** `lo89 < median < hi89` for random inputs.
- **Monotone exposure:** `route_risk` increases with added segments; `segment_risk` increases with `nm`.
- **Spatial effect present:** two points with identical depth/distance but one at a hotspot vs open ocean give different `p_transit` (hotspot higher).

> **Built:** `route-planner/src/planner_core.cljs` (ns `orca.planner.core`): `z`, `sigmoid`,
> `king-zeng`, `derive-config`, `haversine-km`, `spatial-basis`, `spatial-weights`, `f`,
> `base-logit`, `predict-transit-p`, `percentile`, `segment-risk`, `route-risk`, `mean-draw`,
> `heatmap-static`, `dynamic-scalar`, `heatmap-intensity` (later: `point-plan`/`plan-transit-p`
> fast path, see Phase 9). Headless harness: `route-planner/test/core_test.html` (Scittle loads the
> core + runs asserts, sets `document.title`) driven by `orca_clj/test/orca/planner_smoke.clj`
> (ns `orca.planner-smoke`, alias `:planner-smoke`, serves the repo root). All asserts PASS:
> base-logit parity vs the blog calculator **max-abs-diff 4.4e-16**, Poisson round-trip identity,
> CI ordering, monotone in nm and on append, spatial hotspot (Gibraltar p 0.999 > open ocean
> 0.0018). clj-kondo clean given `route-planner/.clj-kondo/config.edn` (adds `:namespace-name-mismatch
> :off` because Scittle loads by path).

---

### Phase 4 — App skeleton + data load (`route-planner/index.html`)

Standalone app under `route-planner/`. `index.html` is the HTML shell only; the ClojureScript is in
external files loaded by Scittle: `<script type="application/x-scittle" src="src/planner_core.cljs">`
(Phase 3 core, no DOM) then `<script type="application/x-scittle" src="src/planner_app.cljs">`
(`orca.planner.app`, the DOM/Leaflet/Reagent layer). CDN deps (no build step): **React 18**,
**Scittle 0.6.15 + reagent plugin** (versions matching `blogpost/index.html`), **Leaflet 1.9**,
**Leaflet.heat**, Google Fonts (Inter + JetBrains Mono).

| Step | Action |
|---|---|
| 4.1 | HTML scaffold (`route-planner/index.html`): map container + sidebar; dark nautical theme (§5); a `#status` element; the two `<script type="application/x-scittle" src=…>` tags. |
| 4.2 | `ns orca.planner.app` (in `src/planner_app.cljs`, requiring `orca.planner.core`); Reagent atoms: `posterior-data`, `incidents`, `geo-grid`, `coastline?`, `boat-params`, `passage-params`, `routes`, `active-route`, `drawing?`. |
| 4.3 | Fetch `posterior_planner.json`, `../orca_reportlist.json` (incidents from `reports.incident.*.{lat,long}`), `geo_grid.json` (all relative to `route-planner/`); on all-resolved set `#status` text to `Ready` and reveal `#status-loaded`. |

**Done when:** Playwright check #1–#2 pass (no console `error`; `#status-loaded` reads "Ready").

> **Built:** `route-planner/index.html` (dark nautical shell, CDN tags React 18 / Leaflet 1.9.4 /
> leaflet.heat 0.2.0 / Scittle 0.6.15 + reagent, then the two `x-scittle src=` tags) +
> `route-planner/src/planner_app.cljs` (ns `orca.planner.app`). All three JSONs load via
> `Promise.all` (geo-grid kept with **string** cell keys, not keywordized); on resolve `#status` and
> `#status-loaded` read "Ready". Gate added to `orca_clj/test/orca/planner_app_smoke.clj`
> (ns `orca.planner-app-smoke`, alias `:app-smoke`) as a `checks` vector for later phases to extend.
> Checks #1 to #2 PASS (0 console/page errors; "Ready"); console log confirms 500 draws / 216
> incidents / 34,933 cells.

---

### Phase 5 — Map + layers

| Step | Action |
|---|---|
| 5.1 | Leaflet map, CartoDB Dark Matter tiles, centered on the bbox. |
| 5.2 | **Historical incident heatmap** (`Leaflet.heat`) from incident lat/lons. Toggle (default on). |
| 5.3 | **Live risk heatmap:** sub-sample grid (every 3rd cell ≈ 0.3°); precompute `S(cell)` once (§2.5); render `[[lat lon intensity]]`. Toggle (default on). |
| 5.4 | Layer-toggle controls; incident-points layer (default off). |

**Done when:** Playwright check #3 passes (Leaflet canvas non-empty, no console errors); the live
heatmap visibly concentrates over the spatial hotspots, **not** uniformly over the open ocean.

> **Built:** Leaflet map on `#map`, CartoDB Dark Matter tiles, centred ~37.5°N/−7.5°E zoom 5. Three
> layers: historical incident heatmap (216 points, warm ramp, default on), live risk heatmap
> (sub-sampled every 3rd cell → **3,812 `static-cells`** with `S` precomputed once, green→red ramp,
> `{:max 1.0}`, default on), incident points (default off), plus sidebar checkbox toggles. Check #3
> PASS: 2 heat canvases, real non-empty pixel read; tiles loaded headless. The live heatmap
> concentrates over Gibraltar and the Galician/Portuguese coast as intended.

---

### Phase 6 — Route drawing + segment/route risk

| Step | Action |
|---|---|
| 6.1 | `map.on("click")` → append waypoint to active route; circle markers; click-marker → delete. `#waypoint-count`. |
| 6.2 | Build segments; subdivide each leg into ≤ `seg_step_nm` sub-segments (§1); look up `{depth_ord,distance_ord}` + `(lat,lon)` at each midpoint. |
| 6.3 | `segment-risk` per sub-segment → `L.polyline` colour-coded by median; expose `#segment-risk-0`. |
| 6.4 | `route-risk` → total panel `#route-total-risk` + `#ci-lo`/`#risk-median`/`#ci-hi`. |
| 6.5 | Hover tooltip: `"Seg 3 — 4.2% [2.8–6.1% 89%CI]  Depth 200m+  Dist >10nm  Hotspot +0.4"`. |

**Colour map** (`risk→colour`): HSL 120°(green)→0°(red), `hue = max(0, 120·(1 − p/p_max))`,
`p_max` from a sensible default (e.g. 0.15) exposed later if needed.

**Done when:** Playwright checks #5–#8, #10 pass — waypoint add increments count; second waypoint
yields `#segment-risk-0`; `ci-lo<median<ci-hi`; Motoring > Sailing risk; and the §2.4 parity check
(single 100 nm segment, ref 100) matches the in-app calculator value.

> **Built:** map-click adds waypoints (circle markers; click-marker deletes), legs subdivide into
> ≤ `seg-step-nm` sub-segments, each looked up in the geo grid (integer-tenths key, nearest-sea
> fallback), `segment-risk` per sub-segment colours an `L.polyline` (`hue = max(0,120·(1−p/0.15))`),
> with a hover tooltip and `#segment-risk-0`; `route-risk` fills `#route-total-risk` / `#risk-median`
> / `#ci-lo` / `#ci-hi`. `window.__planner` exposes test hooks (`addWaypoint`, `clearRoute`,
> `setParam`, `routeMedian`, …) so Playwright drives real code paths instead of synthesising pixel
> clicks. Because the model saturates at hotspots, the risk checks use an empirically chosen
> **moderate-water** route `(38,−11)→(38,−12)` (median ~0.54). Checks #5 to #8 + #10 PASS, notably
> **#10 parity app 0.39809 vs calculator 0.39812, rel 0.0084%** (at point P (39.6,−12.3) where
> |f̄| ≈ 0.06, single ~100 nm segment, `ref_nm` 100).

---

### Phase 7 — Controls + reactivity

| Step | Action |
|---|---|
| 7.1 | Vessel controls (antifoul, hull, rudder, mode, autopilot, speed, length), Conditions (time-of-day, wind, sea), Model (base-rate, ref-nm) — selects + sliders bound to atoms. |
| 7.2 | **Reactivity wiring:** any boat/condition change ⇒ recompute the **single scalar `D`** (§2.5) ⇒ re-tint heatmap (map over precomputed `S(cell)`) **and** recompute route/segment risks. Debounce slider drags (~80 ms). Heatmap never recomputes `S(cell)`. |

**Done when:** Playwright check #4 passes — changing antifoul to Coppercoat changes
`#route-total-risk` and visibly shifts heatmap intensity, with no console errors; interaction stays
smooth during slider drags.

> **Built:** Vessel / Conditions / Model control panels (selects + sliders) bound to
> `boat-params`/`passage-params`, select options derived from the loaded `categories`. Any change
> calls `recompute!` = `refresh-risk-heat!` (recompute the single scalar `D`, re-map the precomputed
> `static-cells`; **`S(cell)` is never recomputed**) + `refresh-route!`. Slider drags debounced
> ~80 ms per control; selects/toggles immediate. Check #4 PASS: antifoul **Black 53.83% →
> Coppercoat 37.18%**, heat-canvas pixel signature changed, 0 errors.

---

### Phase 8 — Multiple routes + polish

| Step | Action |
|---|---|
| 8.1 | Route tabs `[Route A ×] … [+ Add]`; independent waypoints; shared boat/condition params ⇒ shared heatmap. |
| 8.2 | Sidebar comparison: side-by-side total risk + CI for all routes. |
| 8.3 | Theme polish (§5); in-app caveat note: "risk is relative to a `{ref_nm} nm` reference passage; spatial term shows residual hotspots; heatmap colour is a posterior-mean point estimate, route numbers carry the full 89% CI." |

**Done when:** Playwright check #9 passes (two route tabs; no console errors).

> **Built:** "Route Variants" tab strip (`Route A ×` … `#add-route-btn`), per-route independent
> waypoints under `[:routes idx :points]`, tab switch clears the old route's Leaflet layers then
> redraws the active one (shared heatmap, never rebuilt on switch). Sidebar comparison panel lists
> every route's median + 89% CI; `#caveat` interpolates the live `ref-nm`. Hooks `addRoute`,
> `routeCount`, `selectRoute` added. Check #9 PASS (tabs 1→2, 0 errors).

---

### Phase 9 — Final verification & docs

Run the full Playwright suite (§4) headlessly; perf-check heatmap update latency under slider
drag; confirm the in-app caveats render; update `MEMORY.md`/README notes for the new
`posterior_planner.json` and `geo_grid.json` artifacts.

> **Built:** both gates green (`clojure -X:planner-smoke` and `clojure -X:app-smoke`, Checks #1 to
> #10). Perf: a predictor change first measured ~272 to 680 ms; profiling showed the heatmap re-tint
> is only ~6 ms and the cost was `refresh-route!` recomputing the 50-term RBF basis per draw, fixed
> by precomputing a per-point plan once then looping the 500 draws (`point-plan`/`plan-transit-p`,
> bit-identical results) → **~38 ms**. Full-page screenshot confirms map tiles, hotspot-concentrated
> heatmap, colour-coded route with markers, and the full sidebar. `#caveat` renders with the live
> `ref-nm`. Docs: `route-planner/README.md` created; "Route planner" sections added to
> `orca_clj/README.md` and `orca_clj/AGENTS.md`; the session memory index updated. Nothing committed.

---

## 4. Verification harness (Playwright JVM)

> [!IMPORTANT]
> **Scittle errors print to the browser console, not the page.** Every check must capture and
> assert on console output. A silent Scittle failure looks like a blank/frozen UI.

> [!NOTE]
> **Server root.** The app fetches `../orca_reportlist.json`, so the static server must be rooted at
> the **repo root** (not `route-planner/`); navigate to `/route-planner/index.html`. Reuse the JDK
> `SimpleFileServer` + JVM Playwright (`com.microsoft.playwright`) pattern from the existing
> `orca.blog-smoke` test.

```clojure
(require '[com.microsoft.playwright :as pw])
(defn with-page [f]
  (let [pw* (pw/Playwright/create)
        browser (-> pw* .chromium (.launch))
        page (.newPage browser)]
    (.onConsoleMessage page
      (fn [msg]
        (println "[CONSOLE" (.type msg) "]" (.text msg))
        (when (= "error" (.type msg))
          (throw (ex-info "Console error" {:text (.text msg)})))))
    (try (f page) (finally (.close browser) (.close pw*)))))
```

| # | Check | Action | Pass condition | Gate phase |
|---|---|---|---|---|
| 1 | No Scittle errors on load | navigate, wait 3s | zero `error` console msgs | 4 |
| 2 | All JSON loaded | wait `#status-loaded` | text contains "Ready" | 4 |
| 3 | Heatmap renders | wait Leaflet canvas | canvas non-empty; no errors | 5 |
| 4 | Predictor change re-tints | select Coppercoat | `#route-total-risk` changes; heatmap shifts; no errors | 7 |
| 5 | Add waypoint | click (36.1,−5.7) | `#waypoint-count` increments | 6 |
| 6 | Segment risk appears | click 2nd point | `#segment-risk-0` numeric | 6 |
| 7 | CI ordering | read CI fields | `ci-lo < median < ci-hi` | 6 |
| 8 | Motoring > Sailing | toggle mode | Motoring risk > Sailing risk | 6 |
| 9 | Add second route | `#add-route-btn` | two tabs; no errors | 8 |
| 10 | **Exact parity vs calculator** | Motoring+Black+autopilot off; one 100 nm segment; `ref_nm=100` | median within < 1% of `index.html` calculator at same base params (spatial term ≈ 0 point) | 6 |

**Console rule:** any `error` ⇒ STOP and fix. Scittle-related `warn` ⇒ investigate before
proceeding. Typical Scittle failures: `Uncaught cljs$core$ExceptionInfo`,
`TypeError: Cannot read properties of undefined`, `No such namespace`.

---

## 5. UI layout & theme

```
┌─────────────────────────────────────┬──────────────────────────┐
│                                     │  🌊 Orca Route Planner   │
│      LEAFLET MAP (full height)      │  ┌─ Route Variants ────┐ │
│  [Incident heatmap] [Risk heatmap]  │  │ [Route A] [+ Add]   │ │
│  [Drawing mode on/off]              │  └────────────────────┘ │
│  Route: colour-coded segments       │  ┌─ Total Risk ────────┐ │
│  Green → Yellow → Red               │  │  12.4%              │ │
│  Waypoints ○—○—○ (click add,        │  │  [8.1–17.3% 89%CI]  │ │
│  click-marker delete)               │  └────────────────────┘ │
│  Live risk heatmap re-tints with    │  ┌─ Vessel ───────────┐ │
│  predictors; hotspots from f(lat,lon)│  │ Antifoul/Hull/Rudder││
│                                     │  │ Mode/Autopilot      │ │
│                                     │  │ Speed/Length        │ │
│                                     │  ├─ Conditions ────────┤ │
│                                     │  │ Time/Wind/Sea       │ │
│                                     │  ├─ Model ─────────────┤ │
│                                     │  │ Base rate / Ref nm  │ │
│                                     │  └────────────────────┘ │
└─────────────────────────────────────┴──────────────────────────┘
```

**Map layers:** Historical incidents (on) · Live risk heatmap (on) · Route polyline (on) ·
Incident points (off).

**Dark nautical theme:** bg `#0d1117`; CartoDB Dark Matter tiles; sidebar
`rgba(13,17,23,0.92)` glass `backdrop-filter: blur`; accent `#00a8cc`; danger `#e63946`; safe
`#2dc653`; Inter (UI) + JetBrains Mono (numbers); incident heatmap yellow/orange.

---

## 6. File layout

```
route-planner/                 [NEW — standalone web app, separate from the blog]
├── index.html                 [NEW — HTML shell only; loads the .cljs via Scittle src=]
├── src/
│   ├── planner_core.cljs       [NEW — Phase 3, ns orca.planner.core; pure math, no DOM]
│   └── planner_app.cljs        [NEW — Phase 4+, ns orca.planner.app; Leaflet/Reagent/DOM]
├── geo_grid.json              [NEW — Phase 2, depth_ord + distance_ord per sea cell]
└── posterior_planner.json     [NEW — Phase 1, m3_spatial draws + spatial block]

blogpost/                      [existing — UNTOUCHED by this plan]
├── posterior_draws.json       [existing — non-spatial, used by index.html]
├── index.html                 [existing — blog calculator]
└── methodology.html           [existing]

orca_reportlist.json           [existing — incident/uneventful lat/long, fetched as ../]

orca_clj/
├── stan/m3_spatial.stan       [NEW — m3 + RBF spatial smooth]
├── src/orca/prepare.clj       [EDIT — extract lat/lon, build RBF basis + col_means]
├── src/orca/model.clj         [EDIT — fit m3_spatial, export route-planner/posterior_planner.json]
├── resources/config.edn       [EDIT — RBF spacing/lengthscale; bbox]
└── scripts/gen_geo_grid.clj   [NEW — reads GDAL XYZ + NE coastline → route-planner/geo_grid.json]
```

`gen_geo_grid.clj` and the GDAL step run once; outputs committed.
```

---

## 7. Iteration 2 — corrections (model rework, editing, heatmap, commits)

After the first build (Phases 1 to 9, all gates green) three problems surfaced in real use. This
section refines them into concrete work. The model rework (I2.4) is the load-bearing one.

### I2.1 — Commit everything in semantically logical chunks

The whole route-planner build is still uncommitted (the project's no-commit-until-asked rule). Land
it as a sequence of focused commits on `main`, in dependency order, each one self-contained and
green, mirroring the existing 1-concern-per-commit history:

1. `feat(orca_clj): RBF spatial smooth fit (presence/background) + config` — `stan/spatial.stan`,
   `src/orca/planner_fit.clj`, `config.edn :rbf`.
2. `feat(orca_clj): geo-grid generator` — `scripts/gen_geo_grid.clj`.
3. `feat(route-planner): standalone Scittle/Reagent route-risk app` — `route-planner/` (index.html,
   `src/*.cljs`, README) and the two committed data artifacts (`posterior_planner.json`,
   `geo_grid.json`).
4. `test(orca_clj): headless Playwright gates for the planner` — `test/orca/planner_smoke.clj`,
   `test/orca/planner_app_smoke.clj`, `core_test.html`, deps aliases.
5. `docs: route-planner plan + README/AGENTS notes`.

`tmp_geo/` stays untracked (gitignore it if needed). Commit messages follow the house style (no em
dashes, plain and direct). Do the bug-fix and model-rework work (I2.2 to I2.4) as further commits on
top. Confirm `clojure -X:app-smoke` and `-X:planner-smoke` are green before each commit that touches
their inputs.

### I2.2 — Waypoint editing: delete on click, drag to move

Current editing is awkward. Required behaviour:
- **Left-click (or ctrl-click) on a waypoint marker deletes it** (and the route + risk recompute).
  Make the marker hit target comfortable; stop the click from also dropping a new waypoint
  (`L.DomEvent.stopPropagation` on the marker handler).
- **Drag a waypoint to a new location.** Use Leaflet draggable markers (`{:draggable true}`); on
  `dragend`, write the new lat/lng into that waypoint and recompute segments/colours/total. Debounce
  the recompute during an active drag so it stays smooth (reuse the Phase-7 debounce).
- Keep map-click-to-append for empty space. Update the test hooks (`window.__planner`) with
  `moveWaypoint(i, lat, lon)` so a Playwright check can assert a drag updates the route risk.

**Done when:** clicking a marker removes it and the count/total update; dragging a marker moves it
and the risk recomputes; a new Playwright check (delete-then-readd, and move-changes-risk) passes
with no console errors.

### I2.3 — Risk heatmap rendering (replace the point-heat hack with a real field renderer)

**Symptoms:** the live risk layer shows regular stripes, reads as uniformly green, and vanishes at
low zoom.

**Diagnosis:** `Leaflet.heat` is a *point-density* renderer (each point deposits a fixed
pixel-radius blob, then the blobs are summed and normalized). We are misusing it to draw a
*continuous scalar field* sampled on a 0.3° lattice. That produces exactly these artifacts: the
lattice spacing beats against the fixed pixel radius into **stripes/moire**; the radius is in screen
pixels so at low zoom the points spread apart and each blob's contribution falls below the alpha
floor, so the layer **disappears**; and `Leaflet.heat`'s per-pixel max-normalization plus our
already-low intensities collapse the colour to the **green** (low) end of the ramp.

**Fix:** render the risk field as an actual raster, not points. Options:
- **(recommended) Canvas `L.GridLayer`**: for each map tile, paint the sub-sampled cells as filled
  rectangles (or bilinearly interpolate) coloured by `intensity` through the full green→yellow→red
  ramp on a **fixed domain** (not per-frame auto-normalized), with constant alpha. Scales correctly
  with zoom (it redraws per tile in geographic space) and shows no lattice stripes.
- **(simpler) `L.imageOverlay` from an offscreen canvas**: rasterize the whole bbox grid once into a
  canvas (cell -> coloured pixel), set it as an image overlay over the bbox; on a param change,
  repaint the canvas and swap the overlay URL/elem. One image, no per-point blobs, no stripes,
  correct at all zooms.
- Keep `Leaflet.heat` only for the **historical incident** layer (that one is a genuine point
  density and is fine).
Pick the colour ramp domain so the field reads as a field: clamp intensity to a sensible display
range and map it across the full ramp so hotspots are visibly red and quiet water green, not all one
hue. Re-tinting must stay cheap (still only the dynamic scalar `D` + one sigmoid per cell, §2.5).

**Done when:** the risk layer renders as a smooth coloured field with red over the hotspots and
green offshore, no stripes, and it stays visible across zoom levels; Playwright check #3 still
passes (non-empty canvas, no console errors) and a new low-zoom check confirms the field is still
painted.

### I2.4 — Model and method rework (the big one)

**The failure.** A passage from western Portugal to the Strait of Gibraltar returns **100%, 89% CI
[100, 100]**. A Fermi-style estimate for such a passage should be **order a few percent**, not a
certainty. The numbers are not just imprecise, they are meaningless.

**Why it happens.** The first build (forced by the no-coordinates-on-uneventful-reports blocker,
see the Implementation-status note) fit the spatial term as a **presence/background logistic** of
216 incident locations against a *uniform* sample of all sea cells, including the vast empty open
Atlantic that nobody sails. That produces an enormous relative-intensity contrast (posterior-mean
offset f̄ ≈ +8.9 at Gibraltar vs ≈ −4.8 in mid-ocean, about **13 logit units**), and that offset is
**added on top of M3's already base-rate-calibrated logit**. So near a hotspot the per-transit
probability pins at ~0.9999; the Poisson route accumulation `1 − exp(−Σλ)` over several such
segments then saturates to 1.0 with a degenerate interval. Three compounding errors: the spatial
contrast is **unbounded** (no amplitude regularization against an unrealistic background), it is
**not normalized to where boats actually sail**, and it **double-counts the base level** that M3
already carries.

**What Statistical Rethinking offers (the study).** Two ideas from McElreath apply directly:
- **Poisson regression with an exposure offset** (SR 2nd ed, Ch 11, the monastery-manuscripts
  example): model counts as `λ = exp(α + βx + log(exposure))`, where `log(exposure)` enters with a
  fixed coefficient of 1. This is the Fermi move: it makes the rate *per unit of exposure*
  (yacht-distance or yacht-time) the estimand, so totals are calibrated and scale honestly with
  passage length. The blog's existing time-of-day Poisson model already does this (incidents per
  1000 yacht-hours); the planner should inherit that calibrated rate rather than invent a new level.
- **A Gaussian process for spatial autocorrelation** (SR Ch 14.5, the Oceanic-tools model): a
  varying intercept `k ~ MVNormal(0, K)` with a squared-exponential kernel
  `K_ij = η²·exp(−ρ²·D²_ij) + δ_ij·σ²`, where the deviations `exp(k)` are **mean-1 multipliers**
  and the priors on the amplitude `η²` and length-scale `ρ²` make the GP **strongly regularizing**
  (McElreath notes it ends up with *fewer effective parameters* than a fixed-effects version). That
  amplitude regularization is exactly the bound our presence/background fit was missing: a GP
  hotspot is pulled toward the overall mean, so it cannot run to 13 logit units.

**Redesign options (to choose among).**
- **Option A — Fermi recalibration + bounded, normalized spatial relative-risk (no new data,
  recommended).** Keep M3 as the source of *relative attribute* effects. Replace the additive
  spatial offset with a **spatial relative-risk multiplier** `RR(lat,lon) ≥ 0`: a kernel/GP-smoothed
  incident-density field, **normalized so its mean over realistically-sailed waters is 1** and
  **amplitude-bounded** (GP-style regularizing prior, or an explicit cap such as 0.1x to ~8x). Anchor
  the absolute level with a **Fermi exposure rate** `h0` (hazard per nautical mile) calibrated so a
  reference passage through average orca-zone waters reproduces the validated base-rate per-passage
  probability (a few percent). Route hazard `λ = Σ_seg h0 · m_attr(seg) · RR(seg) · nm_seg`,
  `P = 1 − exp(−λ)`, CI from posterior draws. Result: a typical passage stays a few percent; a
  hotspot passage is higher but bounded (order 10 to 40 percent), and intervals are non-degenerate.
  This keeps the per-draw `w`/`spatial` JSON contract, so the app math barely changes.
- **Option B — Real sailing-effort surface via geocoded passages (better data).** The uneventful
  reports carry free-text `where_passage_commenced`/`where_passage_ended`. Geocode them (offline
  gazetteer or an approved service) to build an approximate **effort/availability surface**, then
  fit a proper **presence/effort** model (incidents vs actual passages, both located) or a Poisson
  point process with that effort as the exposure offset. This fixes the inflated contrast at its
  root (incident density *relative to where boats actually go*, not relative to empty ocean) and
  yields properly calibrated per-area rates. Cost: a geocoding step and its precision/licensing
  caveats.
- **Option C — Full spatial Poisson GP with explicit exposure (most principled, data-limited).**
  Counts per cell `~ Poisson(λ)`, `log λ = α + log(exposure_cell) + attribute terms + k_cell`,
  `k ~ GP(0, K_SE)` per SR Ch 14. This is the textbook-correct model but needs the effort surface
  from Option B (or external AIS / Cruising-Association traffic statistics). Flag as the ideal;
  implement only if B lands.
- **Option D — Honest minimal (relative-only).** Show the spatial pattern as **relative** risk on
  the map (no per-cell absolute probability) and report a single whole-route number that is just the
  base-rate calculator's per-passage probability scaled by length (pure Fermi, no spatial inflation).
  Least ambitious, guaranteed sensible, least spatially informative.

**Recommendation.** Implement **Option A** now (it removes the nonsense numbers, is grounded in the
SR exposure-offset and GP-regularization ideas, and preserves the app/data contract), and treat
**Option B** as the follow-up upgrade if better spatial fidelity is wanted and geocoding is
acceptable. Re-fit/re-derive `posterior_planner.json` accordingly and update `planner_core.cljs`'s
spatial term from "additive logit offset" to "normalized bounded RR multiplier with a Fermi-anchored
baseline hazard".

**Sanity gates (the model is only fixed when these hold).**
- A western-Portugal-to-Gibraltar passage with default vessel/conditions reads **single-digit to low
  tens of percent**, not 100 percent, with a **non-degenerate** 89% CI.
- A short open-Atlantic passage reads well under a percent.
- The whole-route number scales sensibly with passage length and ref-nm (monotone, no saturation
  except in genuinely extreme cases).
- The exact-parity check (single reference-length segment in average waters) still matches the
  base-rate calculator within tolerance.
- Spatial ordering preserved: a hotspot leg is riskier than an equivalent-length offshore leg, but
  by a **bounded** factor.
Add these as explicit Playwright/headless assertions (extend §4) so the failure can never silently
return.

---

### I2.5 — DECISION (locked): rebuild the model from scratch as B → C

We are **not** reusing M3. We rebuild the spatial+attribute model from scratch as a
**presence/effort spatial model** (Option B feeding Option C). We may later fold what we learn back
into a fresh M3, but the planner gets its own coherent model. Two data facts unlock this (verified
against `orca_data/all_reports_detailed.json`):

- The **uneventful** passages already carry `depth` (417/438) and `distance_off_land` (427/438) as
  the same ordinal bins as incidents, plus boat/condition attributes and `length_of_passage` and
  timestamps. So the **attribute model is identified for both classes** with no coordinates needed.
- Every uneventful passage has a **start and end harbor** (`where_passage_commenced`/`_ended`,
  438/438). Harbors are a bounded, mostly-clean gazetteer (heavy repeats: Gibraltar, Cadiz, Tarifa,
  Lagos, Portimao, La Linea), so they can be geocoded and routed into approximate tracks. That gives
  the **effort/exposure surface (where boats actually sail)**, the denominator whose absence caused
  the runaway contrast.

**The effort surface (two complementary sources).**
- **External prior: EMODnet Human Activities Vessel Density.** Free AIS-derived GeoTIFF, 1×1 km,
  hours per km² per month, by ship type including **Pleasure Craft** and **Sailing**, covering
  Iberia/Gibraltar/Med. Download once offline, reproject/bin to the 0.1° grid with GDAL (same
  offline-GDAL precedent as the GEBCO step). Use as the broad effort prior and a cross-check.
  Source: `emodnet.ec.europa.eu/en/human-activities`.
- **Sample-matched: reconstructed harbor-to-harbor tracks.** Geocode each uneventful passage's start
  and end harbor, route them into a plausible sea track, rasterize the tracks onto the grid. This
  matches the Cruising-Association reporting population (the right denominator for our incident
  numerator). Use `searoute` (`pip install searoute`; purpose-built for plausible visualization
  tracks, avoids land, snaps land points to sea, returns GeoJSON) run offline once, like GDAL.
  Source: `pypi.org/project/searoute`, `github.com/genthalili/searoute-py`.
The two are blended/validated against each other; if they agree, confidence is high; where the
harbor tracks are sparse, EMODnet fills in.

**Data-prep pipeline (documented + coded so it re-runs on new data).**
1. **Extract distinct harbor strings** from the uneventful reports (a Babashka script). Cache the
   resolved set so only *new/unknown* strings need work next time.
2. **LLM geocoding (repeatable, cached).** A Babashka script calls the Claude API to map each
   harbor string to `{:lat :lon :confidence :note}`, reasoning over messy strings ("Rota, just
   North of Cadiz" -> Rota; "Falmouth England, approx near Cadiz on 17th..." -> the near-Cadiz
   waypoint). Results are written to a committed table `route-planner/data/harbor_coords.edn`,
   appended incrementally (only unresolved names are sent), so re-running on a new data dump
   geocodes just the additions. The prompt and confidence policy are documented in the script.
   The uncertainty is acceptable and explicit (the `:confidence`/`:note` ride along).
3. **Track guessing.** For each uneventful passage, route its geocoded `(start,end)` with `searoute`
   into a GeoJSON LineString; commit the tracks. (Optionally nudge tracks toward EMODnet
   high-density corridors; start without.)
4. **Effort raster.** Rasterize the tracks (passage-miles per cell), optionally blended with the
   EMODnet pleasure-craft density, to an exposure field `E(cell)` on the 0.1° grid.

**The model (from scratch, SR-grounded, Ch 11 exposure + Ch 14 GP).**
- Incidents are points (have coords); the effort surface `E` is the exposure. Fit either a
  **presence/effort logistic** (incidents=1 vs background sampled **proportional to `E`**, not
  uniform, which is the core fix) or a **spatial Poisson GP**:
  `incidents_cell ~ Poisson(λ)`, `log λ = α + log(E_cell) + β·attributes + k_cell`,
  `k ~ MVNormal(0, K)`, `K_ij = η²·exp(−ρ²·D²_ij) + δσ²`, with regularizing priors on `η²` (amplitude)
  and `ρ²` (length-scale) so hotspots are **bounded/shrunk** (SR Ch 14: a GP ends up with few
  effective parameters). Attributes (`depth`, `distance`, boat, conditions) are included and are
  available for both classes.
- **The absolute rate falls out of the real incident:effort ratio** (no assumed 2.5% base rate). A
  candidate route's expected interactions = the line integral of the fitted per-distance rate along
  the track; `P(≥1) = 1 − exp(−integral)`; CI from posterior draws. This is the honest Fermi number.
- Export a fresh `route-planner/posterior_planner.json` (new parameter layout + GP/spatial block +
  the calibration constants). The planner core's spatial term changes from "additive logit offset"
  to a **bounded relative effect on a calibrated baseline rate**.

**Tooling note.** `searoute` (Python), the EMODnet GeoTIFF, and GDAL are **offline one-time data
steps**, consistent with the GEBCO/GDAL precedent (Python is allowed for these prep steps; the JVM
analysis and the app stay Clojure). LLM geocoding runs via the Claude API from a Babashka script and
is cached, so it is cheap and repeatable.

**Sanity gates (same as I2.4, restated as the acceptance test):** western-Portugal-to-Gibraltar in
the low-single-digit to low-tens-of-percent range with a non-degenerate 89% CI; open-Atlantic short
hop well under a percent; monotone in length and `ref_nm`; bounded hotspot factor; the effort
surface visibly tracks the known corridors (Gibraltar approaches, Portuguese coast) and not the
empty ocean. Wire these into the headless suite.

**Open sub-decisions deferred to implementation** (pick during the build, document the choice):
EMODnet-as-exposure vs. reconstructed-tracks-as-exposure vs. blend; presence/effort logistic vs.
full Poisson-GP; whether attribute effects are global or also spatially varying.

---

### I2.6 — Movement-aware spatial model and the pod-occupancy prior (refines I2.5)

The interactions are not "orcas in general." They are **one critically endangered subpopulation** with
a well-studied movement ecology, and that ecology is a real, informative prior. (Ecology facts below
are from GTOA / orcaiberica.org, the Esteban et al. Strait-of-Gibraltar conservation-status work, the
"French connection" Bay-of-Biscay re-sightings paper, and a published Iberian/Biscay habitat-suitability
model; collect citations in the build.)

**What we know about them (the prior).**
- A small, closed, genetically distinct group: ~39 individuals (1999-2011 census), of which ~15 (11
  juveniles + 4 adult females) take part in interactions. Two behavioural types (tuna-fishery
  depredators and not).
- They follow **Atlantic bluefin tuna** on a known annual cycle: Gulf of Cádiz / Strait of Gibraltar
  in winter-spring, **north along Portugal and Galicia in summer**, fanning into deeper water and the
  **Bay of Biscay in autumn**, then back south. Most sightings **June to November**. Movement is
  **diffuse** (subgroups move progressively, not one tight pod).
- They cover **>160 km/day**; the seasonal range is the whole Atlantic Iberian coast plus Biscay.
- Live presence data exists: GTOA keeps 1000+ sightings since 2020, a reporting app, and a real-time
  recommendation map (valid ~24h) at `orcaiberica.org`.

**Modelling consequences (fold into I2.5's build).**
1. **Reframe the spatial term as the pod's moving occupancy, not static hotspots.**
   `risk(location, month, boat) ≈ occupancy(location | month) · interaction_propensity(boat, conditions) · exposure`.
   Because it is **one** diffuse group, "orca presence" is a single field that **moves seasonally** and
   integrates to about one group's worth. Building that normalization in **structurally bounds total
   risk** (it fixes the runaway-everywhere failure at the generative level, not by an ad-hoc cap): the
   hotspot is high somewhere because the pod is there, and correspondingly low elsewhere at that time.
2. **Set the GP length-scale prior from how far they swim.** Use an ecologically-motivated spatial
   correlation scale on the order of the daily/short-range movement (~tens to ~150 km) rather than an
   arbitrary value. This is the literal answer to "use what we know of how far they swim as our prior".
3. **Condition the spatial field on month/season via a smooth seasonal drift (DECISION: locked).**
   The hotspot **migrates** (Strait in spring, north in summer/autumn), and we have dates for both
   incidents and uneventful passages, so the planner answers "risk for a passage **this month**". We do
   **not** fit independent per-season fields (216 incidents split across months is too thin and would
   overfit). Instead we fit **one** canonical occupancy field whose position **drifts smoothly with
   day-of-year**, a few parameters, robust, and ecologically faithful to the known north-south cycle.
   - Parameterization: the occupancy is a single GP/kernel shape evaluated at the location **relative to
     a seasonally-shifting reference** `μ(doy)`:
     `occupancy(lat, lon, doy) = field(lat − μ_lat(doy), lon − μ_lon(doy))`,
     with the drift a smooth **periodic** function of day-of-year, e.g.
     `μ_lat(doy) = lat0 + A_lat · sin(2π(doy − φ)/365)` (north in summer, south in winter) and an
     optional smaller offshore term `μ_lon(doy)`. Few parameters (`lat0`, amplitude `A_lat`, phase `φ`,
     optional `μ_lon`), each with an ecology-informed prior (peak-north in late summer/autumn; amplitude
     on the order of the Cádiz-to-Galicia/Biscay span). A cyclic spline on `doy` is an acceptable
     alternative to the sinusoid if the data warrant more shape, but start with the sinusoid.
   - This keeps the spatial **shape** (and the movement-informed length-scale of I2.6.2) shared across
     the year while letting **where** that shape sits track the season. The blog already restricts to a
     May-November season; this makes the season **spatial**.
4. **Bring in orca presence data, not only interactions.** Interactions are a biased subset (they need
   boat **and** orca to overlap). GTOA sightings observe orca presence more directly and could enter as
   an extra likelihood / prior on the occupancy field (a later data step; respect GTOA's terms). The
   published habitat-suitability model is a cross-check.

**Future feature (documented, OUT OF SCOPE for the current build): short-horizon orca forecast.**
Seed a movement / state-space model (daily displacement ~100-160 km, biased by the seasonal climatology
above and by bathymetry / tuna corridors) with recent confirmed positions from the GTOA live feed
(`orcaiberica.org`), and forward-simulate the pod's likely location over the next few days to produce a
**forward-looking** risk map ("where are they likely to be when I sail next week"). This turns the static
seasonal climatology of I2.6.3 into a live nowcast/forecast. Note data-use/licensing terms before
consuming the GTOA feed. Not built now; recorded so the model design (a moving-occupancy field) is
forecast-ready rather than needing a rewrite later.

