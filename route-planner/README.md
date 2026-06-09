# Orca Route Planner

An interactive transit-risk planner for the Iberian orca interaction zone. It
puts the Bayesian model from the blog onto a map: click waypoints to lay a
route, set vessel attributes and passage conditions, and read per-segment and
whole-route interaction risk with an 89% credible interval. A live heatmap tints
the sea by model risk and updates as you change predictors.

The app is a static ClojureScript/Scittle page (no build step):

- `index.html`: the page shell, loads Scittle, Reagent, Leaflet, Leaflet.heat.
- `src/planner_core.cljs` (`orca.planner.core`): pure risk math.
- `src/planner_app.cljs` (`orca.planner.app`): the Leaflet/Reagent UI.

## Opening it

The page fetches `../orca_reportlist.json` from the repo root, so it must be
served over HTTP from the **repo root** (not from inside `route-planner/`, and
not via `file://`). From the repository root:

```bash
python3 -m http.server 8000
# then open http://localhost:8000/route-planner/index.html
```

The headless tests use the JDK `SimpleFileServer` instead
(`jwebserver` / `SimpleFileServer.createFileServer`), which works the same way.

## Data files

| File | What it is |
|------|------------|
| `posterior_planner.json` | The `presence-effort-seasonal` model: 500 draws of an `attr` block (relative vessel effects) and a `spatial` block (occupancy field + seasonal drift). Schema documented in `data/POSTERIOR_SCHEMA.md`. |
| `geo_grid.json`          | Per-cell continuous depth (`m`, metres +down) and distance ordinal for 34,933 sea cells over 25-50°N, 20°W-5°E at 0.1°. Depths are from EMODnet DTM 2024 (see Data sources). |
| `data/*`                 | Data-prep inputs to the fit: geocoded harbors, the prepared report dataset, the sailing-effort grid, and the effort-weighted background sample. |
| `../orca_reportlist.json`| The incident report list (repo root), used for the historical-incident heatmap and the spatial term's incident locations. |

These files are produced by the `orca_clj` tooling project
(`src/orca/planner_fit.clj`, `stan/attr_logit.stan`, `stan/spatial.stan`, and the
`scripts/` Babashka generators).

## Data sources

- **Bathymetry / depth covariate: EMODnet Digital Bathymetry (DTM 2024)**,
  ~115 m native resolution, CC-BY 4.0. The depth covariate for the spatial fit
  and the per-cell grid depths are derived from EMODnet, resampled to a 0.01°
  (~1.1 km) **hybrid** grid (EMODnet inside lon −11..0 / lat 35..47; ETOPO
  fallback outside that box, since the fit footprint is wider than the EMODnet
  data box). This is a 0.01° gridded resample, **not** literal 115 m
  point-sampling.

  Attribution: *Bathymetry derived from EMODnet Digital Bathymetry (DTM 2024),
  EMODnet Bathymetry Consortium, CC-BY 4.0.* (also shown in the in-app map
  credit).

  **Honesty caveat:** the runtime `geo_grid` is still 0.1° (~11 km), so even
  with EMODnet values it cannot draw a tight ~20 m nearshore contour. What the
  upgrade delivers is (a) a materially finer depth covariate for the fit and
  (b) more accurate per-cell grid `m` depths. A literal ~20 m contour needs the
  optional finer-grid refinement or a separate vector-contour overlay — a
  separate workstream, out of scope here.

## Tests

Both headless gates live in `orca_clj` and run in a separate JVM with Playwright:

```bash
cd orca_clj
clojure -X:planner-smoke   # pure-core math (route-planner/test/core_test.html)
clojure -X:app-smoke       # full app suite + sanity/season checks, headless Chromium
```

If Chromium is missing, run `clojure -X:app-smoke orca.planner-app-smoke/install`
once.

## Modelling caveats

- **Risk is a Poisson hazard over the route.** Per nautical mile the hazard is
  `h0 * RR(location, day-of-year) * attr_mult(vessel)`; a segment's probability
  is `1 - exp(-hazard * nm)` and the whole-route probability adds the per-segment
  rates. The absolute level `h0 = -ln(1-base_rate)/ref_nm` is anchored to the
  base-rate over a reference passage (defaults shown in the in-app caveat), so
  treat the numbers as calibrated-but-approximate, best read as comparisons.
- **The spatial term is a bounded, season-drifting occupancy field.** `RR` is the
  relative risk of orca presence, normalized to mean ~1 over waters boats
  actually sail, so hotspots are higher but bounded (no runaway). Its centre
  drifts north in summer and back to the Strait in late winter, following the
  pod's tuna cycle — so set the **Month** control for the passage you mean.
- **Vessel/condition effects are relative.** They come from a logistic of
  incident vs uneventful reports (depth, distance, sailing mode, antifoul, hull,
  rudder, speed, length, wind, sea). Autopilot is not a predictor (incident
  reports lack the field), so that control is shown disabled.
- **The heatmap colour is a posterior-mean point estimate.** It is a fast
  single-number tint of each cell at the current month/vessel. The route numbers
  carry the full 89% credible interval (low / median / high), so trust those for
  any actual comparison.
