# Handover plan — upgrade the route-planner depth data to EMODnet (workstream "1b")

**Purpose.** Replace the coarse ETOPO-15-arc-sec → 0.1° binned bathymetry that currently
feeds the orca route-planner with **EMODnet Digital Bathymetry DTM 2024 (~115 m, CC-BY 4.0)**,
re-fit the spatial risk model on the finer depth, recalibrate, re-tune the heatmap display,
and re-validate with the existing synthetic-recovery harness. This fixes the root cause behind
"our depth data is not detailed enough" — the current pipeline cannot represent nearshore shelf
structure (and therefore cannot support the GTOA "stay inside the ~20 m contour" advice).

**This document is written to be executed from a CLEAN context.** Read it top to bottom, then
work the phases in order. It assumes no memory of the session that produced it.

---

## 0. How to run this (the working method)

This repo is driven **coordinator-style**: the main conversation context should only PLAN and
DISPATCH; every concrete task runs in a **subagent** (the `Agent` tool) with a tight, self-contained
prompt, so no single context bloats. Rules of engagement:

- **One subagent per phase** below (some phases note sub-tasks that can run in PARALLEL — dispatch
  those in a single message with multiple `Agent` calls).
- Each subagent prompt must be self-contained: name the exact files, commands, success criteria,
  and "report back X". Subagents do NOT share your context — restate what they need.
- After a subagent returns, the coordinator reviews its report, updates this plan's checkboxes
  mentally, and dispatches the next phase. **Clear/compact context between major phases** if it
  grows large.
- **Every phase ends at a GATE** (a test/convergence/smoke check). If a gate fails, do NOT proceed —
  dispatch a fix subagent, iterate until green. "Validate it works; if not, go back and try again"
  is the standing rule.
- Long Stan fits can kill a subagent's socket (~30–60 min). Keep fit phases scoped to ONE fit each
  where possible, and have the subagent persist results to disk immediately so a death loses nothing.

### Environment facts (verify, don't trust blindly)
- Repo root: `/Users/samikallinen/common/projects/orca-interactions`
- Two subprojects: `route-planner/` (Scittle/Leaflet app + data-prep scripts + geo_grid) and
  `orca_clj/` (JVM Clojure + CmdStan: the model fit, the validation harness, the smoke gates).
- CmdStan: built once into a writable cache at `$HOME/.cache/orca-cmdstan` (nix cmdstan can't compile
  in the read-only store on macOS). Stan commands run inside nix with `CMDSTAN` exported:
  `cd orca_clj && nix develop ../ --command bash -c 'export CMDSTAN=$HOME/.cache/orca-cmdstan; <cmd>'`
- **GDAL** (for the EMODnet raster work) is NOT assumed present — get it via nix per task, e.g.
  `nix-shell -p gdal --run '<gdal cmd>'` or `nix shell nixpkgs#gdal --command bash -c '<cmd>'`.
- Clojure tests: `clojure -X:test` (from `orca_clj/`). Format Clojure with
  `npx @chrisoakman/standard-clojure-style fix <files>` — **NEVER run it on `.stan` files** (it
  mangles them). Lint with `clj-kondo`. cljs lints from `route-planner/`, clj from `orca_clj/`.

### Current model state you are starting from (as of this handover)
The model was recently overhauled (memory: `orca-route-planner`, `orca-synthetic-validation`):
- Spatial occupancy model (`orca_clj/stan/spatial.stan`): logistic of incident locations vs
  effort-weighted background; linear predictor `b0 + Bsp*w + b_d1*z + b_d2*z2`, where `Bsp` is an
  RBF design (squared-exponential, ℓ=150 km, haversine, column-centered by background means),
  `z`,`z2` = standardized `log10(depth)` and its square (peaked shelf/slope preference).
- RBF centers are **constrained to shelf/slope depths ≤ 1500 m** (`center-depth-cap-m` in
  `planner_fit.clj`) → currently **39 active centers** (was 84; removing abyssal centers fixed a
  field-leak that made open ocean read high-risk).
- `base_rate_default = 0.00358` (anchors absolute risk so the W-Portugal→Gibraltar reference route
  reads ~2.5%). Lives in **5 places** — see Phase 3.
- Heatmap display: `risk-lo 0.008`, `risk-hi 0.045`, `risk-gamma 1.0` (no gamma lift), honest
  probability legend, in `route-planner/src/planner_app.cljs`.
- A synthetic-data / track-simulation / parameter-recovery harness exists in `orca_clj`
  (`orca.synth`, `orca.simtracks`, `orca.recover`, `orca.priorpred`; plan in
  `orca_clj/SYNTHETIC_VALIDATION_PLAN.md`). It PROVED depth is recoverable and the RBF field does
  not steal it. `orca.simtracks/real-centers` reads centers from `orca.planner-fit`, so re-running
  `orca.recover/recover-all-mimic` after a re-fit automatically validates the NEW geometry.
- ⚠️ The working tree currently has UNCOMMITTED changes from that overhaul plus a backup file
  `route-planner/posterior_planner.json.prefix-bak`. Decide whether to commit a baseline (see
  Phase 6) BEFORE starting, so this EMODnet work is a clean, revertable diff.

### The two depth consumers you must keep consistent
1. **The FIT covariate.** `orca_clj/src/orca/planner_fit.clj` samples bathymetry at each incident and
   background POINT to build `z`,`z2` (functions `load-bathy`/`sample-depth`/`logdepth`). This is
   where finer depth helps MOST — the covariate is point-sampled, not grid-limited.
   - The bathy source it loads is a JSON/array file (find it: grep `load-bathy`/`bathy` in
     `planner_fit.clj`; likely `route-planner/data/bathy.json` or similar). This is the ETOPO product
     to replace.
2. **The runtime grid.** `route-planner/geo_grid.json` (~34.9k sea cells keyed
   `"round(lat*10),round(lon*10)"` → `{m: depth_metres_+down, c: distance_ord}`) is read by the cljs
   app (`geo-lookup`) for the heatmap and the route depth readout. Generated by
   `route-planner/scripts/gen_geo_grid.clj` (Babashka; currently samples NOAA ERDDAP ETOPO + Natural
   Earth coastline). The `m` values should also come from EMODnet for consistency.

**Resolution caveat (be honest in the plan and UI).** geo_grid is on a 0.1° (~11 km) lattice. Even
with EMODnet-sourced values, the GRID still can't render a tight 20 m nearshore band at 0.1°. So 1b
delivers: (a) a materially better FIT depth covariate (point-sampled at 115 m), and (b) more accurate
grid `m` values — but to actually DRAW/resolve the 20 m contour you'd also need either the finer grid
refinement (optional Phase 5) or the separate "1a" vector-contour overlay (out of scope here). Keep
this distinction explicit so nobody over-claims.

---

## Phase 0 — Acquire the EMODnet DTM (USER-ASSISTED + a verify subagent)

**Goal:** get an EMODnet DTM 2024 GeoTIFF covering the Iberian-Atlantic bbox onto disk and verified.

**Bounding box** (covers Galicia, Portugal, Gulf of Cádiz, Strait of Gibraltar, Bay of Biscay, with
margin): **lon −11 to 0, lat 35 to 47** (EPSG:4326).

**Option A (programmatic, try first).** EMODnet Bathymetry exposes OGC services. A subagent should
discover the exact coverage from the WCS capabilities and fetch the bbox:
- WCS capabilities: `https://ows.emodnet-bathymetry.eu/wcs?service=WCS&request=GetCapabilities`
- Identify the DTM 2024 coverage id, then `GetCoverage` for the bbox as GeoTIFF (subagent: parse caps,
  build the request, save to `route-planner/tmp_geo/emodnet_dtm.tif`). `tmp_geo/` is gitignored.
- If WCS is flaky/oversized, fall back to the tiled GeoTIFF downloads or the custom-area export from
  the viewer: `https://emodnet.ec.europa.eu/geoviewer/` and `https://emodnet.ec.europa.eu/en/bathymetry`.

**Option B (manual, USER does this if Option A fails / needs a browser).** Download the bbox area as
GeoTIFF from the EMODnet geoviewer custom-area export and drop it at
`route-planner/tmp_geo/emodnet_dtm.tif`. (The viewer download is interactive; a headless subagent may
not manage it — this is the step most likely to need YOU.)

**Verify subagent** (after the file exists): run under nix-shell with GDAL:
`nix-shell -p gdal --run 'gdalinfo route-planner/tmp_geo/emodnet_dtm.tif'`
Report: pixel size (expect ~0.00139° ≈ 115 m), CRS, value range, NoData, and the **sign/datum of
depth** (EMODnet is metres relative to LAT, typically **negative = below sea level**). Note the sign —
the fit/grid use **positive = deeper**, so the resampling step must flip/handle sign correctly.

**GATE 0:** a valid GeoTIFF at ~115 m covering the bbox, sign/datum documented. License note recorded:
attribution string *"Bathymetry derived from EMODnet Digital Bathymetry (DTM 2024), EMODnet Bathymetry
Consortium, CC-BY 4.0."* must be added somewhere user-visible (map credit / README) in Phase 6.

---

## Phase 1 — Rebuild the bathy source + geo_grid from EMODnet (1 subagent; 2 sub-tasks)

**Goal:** swap the depth SOURCE from ETOPO to the EMODnet GeoTIFF for BOTH consumers, schema unchanged.

**Sub-task 1.1 — the FIT bathy file.** Find how `planner_fit.clj` loads bathy (`load-bathy`/
`sample-depth`). Regenerate that bathy product by sampling the EMODnet GeoTIFF (positive-down metres)
on the same lat/lon footprint the current bathy.json covers. Two ways:
- preferred: a small Babashka/Clojure step that shells to GDAL (`gdallocationinfo` / `gdalwarp -tr`
  to a regular array) and writes the same JSON shape the loader expects; OR
- resample the GeoTIFF to the bathy grid with `gdalwarp` and convert. Keep land/zero handling
  consistent with the current loader (the fit floors depth at 1 m before `log10`).
Confirm `sample-depth` returns sensible values at a few known points (a shelf point shallow, an
abyssal point deep), and that nearshore points are now finer/shallower than the old ETOPO bin.

**Sub-task 1.2 — geo_grid.json.** Update `route-planner/scripts/gen_geo_grid.clj` to source cell
depth `m` from the EMODnet GeoTIFF (sample at each 0.1° cell centre) instead of NOAA ERDDAP ETOPO.
Keep the cell key format and the `c` (distance_ord, from Natural Earth) computation unchanged.
Regenerate: `cd route-planner && bb scripts/gen_geo_grid.clj` (or however the script is invoked —
check its header). Output stays at the current geo_grid path (confirm: `route-planner/geo_grid.json`).
Sanity: cell count unchanged (~34.9k sea cells), `m` values sane, nearshore cells shallower than before.

**Notes / gotchas:**
- Handle the EMODnet sign (negative-down → positive-down) once, centrally.
- The `center-depth-cap-m = 1500` eligibility in `planner_fit.clj` uses bathy too — it will re-apply
  on the new source in Phase 2; the active-center count may shift from 39 (that's fine and expected).
- Keep both consumers on the SAME EMODnet raster so the runtime grid and the fit covariate agree.

**GATE 1:** new bathy source + regenerated geo_grid, both spot-checked; `clojure -X:test` still green
(pure tests shouldn't break). Old `geo_grid.json` / bathy backed up (`cp … *.etopo-bak`).

---

## Phase 2 — Re-fit the spatial model on EMODnet depth (1 subagent, ONE fit)

**Goal:** re-fit so the depth covariate uses the finer EMODnet depth; re-export the posterior.

- Back up the current posterior first: `cp route-planner/posterior_planner.json
  route-planner/posterior_planner.json.preemodnet-bak`.
- Run the fit under nix:
  `cd orca_clj && nix develop ../ --command bash -c 'export CMDSTAN=$HOME/.cache/orca-cmdstan; clojure -X:planner-fit'`
  (generous timeout, ~1.2M ms). This re-derives the depth standardization constants
  (`logdepth_mean`/`logdepth_sd`/`z_bg_mean`/`z2_bg_mean`), re-selects depth-eligible centers, re-fits
  `spatial.stan` + `attr_logit.stan`, and re-writes `route-planner/posterior_planner.json`.
- **CONVERGENCE GATE:** report Rhat (<1.01), min ESS (>400), divergences (0) for b0, b_d1, b_d2, tau,
  and the w block. Report new `n_basis` (center count), the new depth standardization constants, and
  new `b_d1`/`b_d2` (expect still a peaked shelf preference, roughly b_d1≈+1.2…1.3, b_d2≈−1.5…−1.7 —
  confirm depth wasn't destabilized by the finer source).
- Spot-check: the previously-flagged deep cells (~(45.3,−10.2), ~(40,−10)) should still read low f_rbf.

**GATE 2:** clean convergence; depth coeffs still a sane peaked shelf/slope response. If divergent or
Rhat bad, report honestly and dispatch a fix (do not tweak to force a pass).

---

## Phase 3 — Recalibrate `base_rate_default` (1 subagent)

The finer depth will shift the field amplitude, so the absolute anchor needs re-tuning (same cascade
as last time).

- Use the existing calibrator in `route-planner/tmp_sim/` (gitignored): `sim_harness.js` replays the
  438 real passages + reference routes; `calibrate_depth.js` 1-D root-finds `base_rate` so the
  W-Portugal→Gibraltar reference route reads ~0.025. Point it at the NEW posterior and solve.
- Report the solved value, the reference-route probability + 89% CI, and the 438-passage spread
  (target profile: p50 ~0.2%, p95 ~1.3%, max ~4%, reference ~2.5%, long circumnavigation > reference).
- Update `base_rate_default` in **all 5 locations** (verify each by grep):
  1. `orca_clj/src/orca/planner_fit.clj` — `(def base-rate-default …)` (source of truth)
  2. `route-planner/src/planner_app.cljs` — `:base-rate …` (runtime UI default)
  3. `route-planner/posterior_planner.json` — `"base_rate_default": …` (scalar only)
  4. `route-planner/data/POSTERIOR_SCHEMA.md`
  5. `route-planner/test/core_test.html` — the test fixture `base …`
- Run the core gate: `cd orca_clj && nix develop ../ --command bash -c 'clojure -X:planner-smoke'`.

**GATE 3:** `planner-smoke` PASS (GATE1 reference route in (0.03,0.35); spatial hotspot>open; bounded;
seasonal; depth-peaked shelf>abyssal; count→prob limits). Console/page errors = 0.

---

## Phase 4 — Re-tune heatmap display + re-validate the model (2 subagents, can be PARALLEL)

**4a — heatmap display.** Re-measure the field distribution on the new posterior with
`route-planner/tmp_sim/heatmap_diagnose.js` (point it at the new posterior + geo_grid). Re-pick
`risk-lo`/`risk-hi` (keep `risk-gamma = 1.0`) so deep/open water reads green (deep&far non-green ~0–2%)
and shelf/slope hotspots stay legible. Update those constants + the comment block + legend thresholds
in `route-planner/src/planner_app.cljs`. Then run the app gate:
`cd orca_clj && nix develop ../ --command bash -c 'clojure -X:app-smoke'`.
**GATE 4a:** `app-smoke` PASS, console clean (benign Canvas2D willReadFrequently warnings are OK).

**4b — re-validate recovery on the new geometry.** Because `orca.simtracks/real-centers` reads centers
from `planner-fit`, re-running the harness now tests the EMODnet-fitted model. Run:
`cd orca_clj && nix develop ../ --command bash -c 'export CMDSTAN=$HOME/.cache/orca-cmdstan; clojure -M -e "(require (quote orca.recover)) (orca.recover/recover-all-mimic)"'`
(persists to `orca_clj/out/recovery/mimic/`). Report b_d1/b_d2/z* recovered-vs-true, surface corr,
R̂/ESS/divergences, and the two-arm Arm1→Arm2 gap.
**GATE 4b:** depth still recovers (b_d1/b_d2 covered, z* brackets truth, 0 div) and two-arm PASS (field
doesn't steal depth). Surface corr ~0.87–0.88 is the known benign near-miss on the strict 0.90 gate —
not a depth failure.

---

## Phase 5 (OPTIONAL) — Refine grid resolution near the coast (only if you want the 20 m band visible)

1b's grid stays 0.1°. If, after seeing 1b, you want the heatmap/contour to actually resolve nearshore:
refine `gen_geo_grid.clj` to a finer lattice (e.g. 0.02° within ~10 km of the coastline, 0.1° offshore)
sampled from the same EMODnet raster. This enlarges geo_grid.json and the cljs `geo-lookup`/static-cell
precompute — measure the app perf impact (Scittle is an interpreter; keep static-cells precomputed).
Treat as a separate mini-plan with its own app-smoke gate. **Recommend doing the separate "1a" 20 m
vector-contour overlay (EMODnet DTM → `gdal_contour -fl 20` → GeoJSON → Leaflet polyline) instead/also**
if the goal is specifically to draw the advisory line — see the research summary in the conversation
that produced this handover. Out of scope for 1b itself.

---

## Phase 6 — Docs, memory, attribution, commit (1 subagent)

- Update `route-planner/data/POSTERIOR_SCHEMA.md` (depth block now from EMODnet; new standardization
  constants), `route-planner/README.md` / `orca_clj/README.md` (data source = EMODnet DTM 2024,
  ~115 m, CC-BY 4.0; add the attribution string to the map credit in the app UI).
- Update the memory files (`orca-route-planner`, `orca-synthetic-validation`) with the EMODnet upgrade
  + new fit numbers.
- Clean up backups (`*.etopo-bak`, `*.preemodnet-bak`, the older `posterior_planner.json.prefix-bak`)
  once satisfied.
- If committing: branch off `main`, stage the EMODnet upgrade as a focused set of commits (data-prep
  change; re-fit posterior; recalibration; display tune; docs). Do NOT commit `tmp_sim/` / `tmp_geo/`
  (gitignored). End commit messages with the project's Co-Authored-By line. Only commit/push when the
  user asks.

**GATE 6:** both smoke gates green, `clojure -X:test` green, clj-kondo clean, docs + attribution
updated, memory updated.

---

## Quick reference (commands)

```bash
# GDAL via nix (Phase 0/1)
nix-shell -p gdal --run 'gdalinfo route-planner/tmp_geo/emodnet_dtm.tif'

# regenerate geo_grid (Phase 1)
cd route-planner && bb scripts/gen_geo_grid.clj

# re-fit + re-export posterior (Phase 2)
cd orca_clj && nix develop ../ --command bash -c 'export CMDSTAN=$HOME/.cache/orca-cmdstan; clojure -X:planner-fit'

# smoke gates (Phase 3 / 4a)
cd orca_clj && nix develop ../ --command bash -c 'clojure -X:planner-smoke'
cd orca_clj && nix develop ../ --command bash -c 'clojure -X:app-smoke'

# re-validate recovery (Phase 4b)
cd orca_clj && nix develop ../ --command bash -c 'export CMDSTAN=$HOME/.cache/orca-cmdstan; clojure -M -e "(require (quote orca.recover)) (orca.recover/recover-all-mimic)"'

# tests / format / lint
cd orca_clj && clojure -X:test
npx @chrisoakman/standard-clojure-style fix <files>   # NEVER on .stan
```

## Risks / gotchas checklist
- [ ] EMODnet depth SIGN/DATUM (negative-down LAT) handled centrally; fit/grid use positive-down.
- [ ] Both depth consumers (fit bathy + geo_grid) sourced from the SAME EMODnet raster.
- [ ] Recalibration cascade: `base_rate_default` updated in ALL 5 places.
- [ ] Never run standard-clojure-style on `.stan`.
- [ ] Stan fits are long → one fit per subagent, persist results, generous timeouts.
- [ ] CC-BY attribution added to the app UI + README.
- [ ] 0.1° grid still can't draw a tight 20 m line — don't over-claim; that's Phase 5 / "1a".
- [ ] Commit a clean baseline of the PRE-EMODnet state first if you want a revertable diff.
