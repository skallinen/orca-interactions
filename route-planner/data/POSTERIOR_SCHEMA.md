# posterior_planner.json schema (presence-effort-seasonal)

Written by `orca.planner-fit/export!`. Two independent Bayesian logistic
models combined at runtime (see ROUTE_PLANNER_PLAN.md I2.5/I2.6).

## Top level
- `model`: "presence-effort-seasonal"
- `n_draws`: 500 posterior draws.
- `base_rate_default`: 0.008 (P of >=1 interaction over `ref_nm_default` nm at RR=1, attr_mult=1).
  **REFERENCE-ROUTE anchored (Phase 1):** calibrated so a fixed W-Portugal->Gibraltar
  reference passage (the smoke-test `wpg-route`, reference vessel, doy=232) reads ~2.5%.
  The old value (0.025) was anchored on the population MEAN over all 438 passages, but
  that population is dominated by short coastal hops (median ~51 nm), which forced the
  per-nm hazard too high for any real hotspot transit; reference-route anchoring fixes
  the absolute level directly on a believable route.
- `ref_nm_default`: 100.0 nm.

## attr (Part A: relative vessel/condition effects)
- `ordinals`: ["depth_ord" "distance_ord" "speed_ord" "boat_length_ord" "wind_ord" "sea_state_ord"] (standardized z=(x-mean)/sd).
  **`depth_ord` is intentionally NOT used in the runtime vessel multiplier (Phase 1):**
  its fitted beta (~+0.9, "deeper => riskier") is a spurious LOCATION proxy that
  double-counts the spatial RBF occupancy field — depth is a property of where you are,
  not of the vessel. It stays in the layout (so the JSON is unchanged) but is dropped
  from `attr_mult` in `planner_core.cljs` (`attr-x-ref`, `heatmap-static`); it will be
  reintroduced PROPERLY on the spatial side in a later phase.
- `standardization`: per-ordinal {mean, sd} from the combined 654 rows (mappable values only).
- `categories`: non-reference levels per category: {:sailing ["Hove-to" "Motoring" "Motorsailing"], :antifoul ["Blue" "Coppercoat" "Green" "Other" "Red" "White"], :hull ["Dark colour"], :rudder ["Full skeg" "Keel hung" "Semi skeg" "Twin rudder"]}
- `reference`: dropped reference level per category (defines multiplier=1): {:sailing "Sailing", :antifoul "Black", :hull "White/light", :rudder "Spade"}
- `layout`: the 20 design columns, ordinals then one-hot "cat=level".
- `draws`: 500 rows, each a length-20 beta vector (alpha discarded).

## spatial (Part B: seasonally-drifting occupancy field)
- `metric`: haversine_km. `lengthscale_km`: 150.0.
- `centers`: 84 RBF center [lat,lon] pairs. `n_basis`: 84.
- `col_means`: length-84 background column means (subtract from raw basis).
- `drift`: {a_lat, phi, a_lon, period}; mu_lat(doy)=a_lat*sin(2pi(doy-phi)/period), mu_lon=a_lon.
- `draws`: 500 of {w: length-84, Z: per-draw normalizer, b0: fit intercept (unused at runtime)}.

## Runtime combine (per draw d, point (lat,lon), day doy, vessel x)
```
lat' = lat - a_lat*sin(2pi(doy-phi)/period); lon' = lon - a_lon
B_j  = exp(-haversine_km((lat',lon'),center_j)^2 / (2*lengthscale_km^2))
f_d  = sum_j w_dj * (B_j - col_means_j)
RR_d = exp(f_d) / Z_d           ; mean 1 over sailed waters => bounded
attr_adj_d  = sum_k beta_dk * (x_k - x_ref_k)   ; z / indicator-minus-ref
                                                ; (depth_ord EXCLUDED: location proxy)
attr_mult_d = exp(attr_adj_d)
h0          = -ln(1 - base_rate_default) / ref_nm_default
hazard_per_nm_d = h0 * RR_d * attr_mult_d
; lambda accumulates the EXPECTED interaction count (additive over segments):
;   segment nm: lambda_seg_d = hazard_per_nm_d * nm
;   route:      lambda_d     = sum_segs lambda_seg_d
; count -> P(>=1) is the CLUSTERED aggregation (NOT naive Poisson 1-exp(-lambda),
; which saturates long hotspot routes to ~100%). Orca interactions come from ~2
; pods / ~15 animals and cluster by pod/day, so miles are not independent trials:
;   lambda_eff = Lmax * (1 - exp(-lambda/Lmax))      ; effective-exposure cap
;   p          = 1 - (1 + lambda_eff / r)^(-r)        ; negative-binomial (gamma-
;                                                     ;   mixed Poisson) overdisp.
; Lmax = eff-lambda-max (0.15), r = dispersion-r (0.3) are constants in
; planner_core.cljs (see `count->prob`). These are the SOFTENED Phase-1 values
; (was 0.5 / 0.4): the gentle softcap has ceiling 1-(1+0.15/0.3)^(-0.3) ~ 11.5%,
; so no route runs away toward the old ~30%. p reduces to 1-exp(-lambda) as r->inf
; and lambda<<Lmax, so short legs / open water are unchanged; long hotspot routes
; settle to a defensible single-digit/low-double-digit value instead of certainty.
; pct over d. Reference profile under Phase-1 calibration: short hop <1%,
; W-Port->Gib ~2.5%, ~900nm circumnavigation ~6%, 438-passage p95 ~2.6%, max ~7%.
```
x_ref: ordinals at training mean (z=0), each categorical at its reference
level, so the reference vessel has attr_mult=1.
