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
| `posterior_planner.json` | 500 posterior draws: the 30 base M3 attribute columns (copied unchanged from the blog's `posterior_draws.json`) plus the fitted spatial block. |
| `geo_grid.json`          | Depth and distance ordinals for 34,933 sea cells over 25-50°N, 20°W-5°E at 0.1°. |
| `../orca_reportlist.json`| The incident report list (repo root), used for the historical-incident heatmap and the spatial term's incident locations. |

The two `.json` files in this directory are produced by the `orca_clj` tooling
project (`src/orca/planner_fit.clj`, `stan/spatial.stan`,
`scripts/gen_geo_grid.clj`).

## Tests

Both headless gates live in `orca_clj` and run in a separate JVM with Playwright:

```bash
cd orca_clj
clojure -X:planner-smoke   # pure-core math (route-planner/test/core_test.html)
clojure -X:app-smoke       # full app suite, Checks #1-#10, headless Chromium
```

If Chromium is missing, run `clojure -X:app-smoke orca.planner-app-smoke/install`
once.

## Modelling caveats

- **Risk is relative to a reference passage**, not an independently calibrated
  absolute probability. The numbers compare routes and vessel configurations
  against a fixed-length reference leg (default 100 nm), shown in the in-app
  caveat.
- **Risk saturates near the hotspots.** The spatial term shows where incidents
  concentrate (Strait of Gibraltar, Galician/Portuguese coast) on top of
  depth/distance, so near those areas the modelled risk pins close to 1. That is
  the model being faithful to the data, not a defect.
- **The heatmap colour is a posterior-mean point estimate.** It is a fast
  single-number tint of each cell. The route numbers carry the full 89% credible
  interval (low / median / high), so trust those for any actual comparison.
- **The spatial term is fit separately** from the base attribute coefficients
  and added as an offset to the M3 logit. The uneventful-passage reports have no
  coordinates, so location is learned by a presence/background smoother (incident
  locations vs sea-cell pseudo-absences), while the attribute columns are copied
  unchanged from the blog posterior.
