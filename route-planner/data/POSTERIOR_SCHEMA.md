# posterior_planner.json schema (presence-effort-seasonal)

Written by `orca.planner-fit/export!`. Two independent Bayesian logistic
models combined at runtime (see ROUTE_PLANNER_PLAN.md I2.5/I2.6).

## Top level
- `model`: "presence-effort-seasonal"
- `n_draws`: 500 posterior draws.
- `base_rate_default`: 0.025 (P of >=1 interaction over `ref_nm_default` nm at RR=1, attr_mult=1).
- `ref_nm_default`: 100.0 nm.

## attr (Part A: relative vessel/condition effects)
- `ordinals`: ["depth_ord" "distance_ord" "speed_ord" "boat_length_ord" "wind_ord" "sea_state_ord"] (standardized z=(x-mean)/sd).
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
attr_mult_d = exp(attr_adj_d)
h0          = -ln(1 - base_rate_default) / ref_nm_default
hazard_per_nm_d = h0 * RR_d * attr_mult_d
; segment nm: lambda_seg_d = hazard_per_nm_d*nm; p=1-exp(-lambda_seg_d)
; route: lambda_d = sum_segs; p_route_d = 1-exp(-lambda_d); pct over d.
```
x_ref: ordinals at training mean (z=0), each categorical at its reference
level, so the reference vessel has attr_mult=1.
