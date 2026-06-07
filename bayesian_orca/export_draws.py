#!/usr/bin/env python3
"""
Re-fit M3 and export 500 posterior draws as JSON for the browser calculator.
"""
import json, os, warnings
warnings.filterwarnings("ignore")
import numpy as np
import pandas as pd
import pymc as pm
import arviz as az

# Load data
df = pd.read_csv("bayesian_orca/data/modeling_data.csv")
with open("bayesian_orca/data/metadata.json") as f:
    meta = json.load(f)

# Prepare variables (same as original M3)
mask = df[['depth_ord_std','is_daytime','autopilot_on','speed_ord_std',
           'boat_length_ord_std','distance_ord_std','wind_ord_std',
           'sea_state_ord_std','sailing_mode_idx','antifoul_idx',
           'hull_colour_idx','rudder_idx']].notna().all(axis=1)
d = df[mask].reset_index(drop=True)

n_sailing = len(meta['categories']['sailing_mode'])
n_antifoul = len(meta['categories']['antifoul'])
n_hull = len(meta['categories']['hull_colour'])
n_rudder = len(meta['categories']['rudder'])

print(f"Fitting M3 on {len(d)} observations...")
print(f"Categories: sailing={n_sailing}, antifoul={n_antifoul}, hull={n_hull}, rudder={n_rudder}")

with pm.Model() as M3:
    alpha = pm.Normal("alpha", mu=-1, sigma=1)
    # Continuous/ordinal betas
    beta_depth = pm.Normal("beta_depth", mu=0, sigma=1)
    beta_daylight = pm.Normal("beta_daylight", mu=0, sigma=1)
    beta_autopilot = pm.Normal("beta_autopilot", mu=0, sigma=1)
    beta_speed = pm.Normal("beta_speed", mu=0, sigma=0.5)
    beta_length = pm.Normal("beta_length", mu=0, sigma=0.5)
    beta_distance = pm.Normal("beta_distance", mu=0, sigma=0.5)
    beta_wind = pm.Normal("beta_wind", mu=0, sigma=0.5)
    beta_sea = pm.Normal("beta_sea", mu=0, sigma=0.5)
    # Category effects
    a_sailing = pm.Normal("alpha_sailing", mu=0, sigma=0.5, shape=n_sailing)
    a_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=0.5, shape=n_antifoul)
    a_hull = pm.Normal("alpha_hull", mu=0, sigma=0.5, shape=n_hull)
    a_rudder = pm.Normal("alpha_rudder", mu=0, sigma=0.5, shape=n_rudder)

    logit_p = (alpha
               + beta_depth * d.depth_ord_std.values
               + beta_daylight * d.is_daytime.values
               + beta_autopilot * d.autopilot_on.values
               + beta_speed * d.speed_ord_std.values
               + beta_length * d.boat_length_ord_std.values
               + beta_distance * d.distance_ord_std.values
               + beta_wind * d.wind_ord_std.values
               + beta_sea * d.sea_state_ord_std.values
               + a_sailing[d.sailing_mode_idx.values.astype(int)]
               + a_antifoul[d.antifoul_idx.values.astype(int)]
               + a_hull[d.hull_colour_idx.values.astype(int)]
               + a_rudder[d.rudder_idx.values.astype(int)])

    y = pm.Bernoulli("y", logit_p=logit_p, observed=d.interaction.values)
    trace = pm.sample(draws=2000, tune=1000, chains=4, random_seed=42)

print("Sampling complete. Extracting draws...")

# Extract all draws
post = trace.posterior
alpha_v = post["alpha"].values.reshape(-1)
bd = post["beta_depth"].values.reshape(-1)
bday = post["beta_daylight"].values.reshape(-1)
baut = post["beta_autopilot"].values.reshape(-1)
bsp = post["beta_speed"].values.reshape(-1)
blen = post["beta_length"].values.reshape(-1)
bdis = post["beta_distance"].values.reshape(-1)
bwi = post["beta_wind"].values.reshape(-1)
bse = post["beta_sea"].values.reshape(-1)
a_s = post["alpha_sailing"].values.reshape(-1, n_sailing)
a_a = post["alpha_antifoul"].values.reshape(-1, n_antifoul)
a_h = post["alpha_hull"].values.reshape(-1, n_hull)
a_r = post["alpha_rudder"].values.reshape(-1, n_rudder)

n_total = len(alpha_v)
np.random.seed(42)
idx = np.sort(np.random.choice(n_total, size=500, replace=False))

draws = []
for i in idx:
    draw = [round(float(v), 4) for v in [
        alpha_v[i], bd[i], bday[i], baut[i], bsp[i], blen[i], bdis[i], bwi[i], bse[i]
    ]]
    for j in range(n_sailing): draw.append(round(float(a_s[i,j]), 4))
    for j in range(n_antifoul): draw.append(round(float(a_a[i,j]), 4))
    for j in range(n_hull): draw.append(round(float(a_h[i,j]), 4))
    for j in range(n_rudder): draw.append(round(float(a_r[i,j]), 4))
    draws.append(draw)

output = {
    "n": len(draws),
    "layout": [
        "alpha","b_depth","b_daylight","b_autopilot","b_speed",
        "b_length","b_distance","b_wind","b_sea",
        "s_0","s_1","s_2","s_3","s_4",
        "a_0","a_1","a_2","a_3","a_4","a_5","a_6","a_7",
        "h_0","h_1","h_2",
        "r_0","r_1","r_2","r_3","r_4","r_5"
    ],
    "categories": meta['categories'],
    "standardization": meta['standardization'],
    "sample_rate": round(216/654, 6),
    "draws": draws
}

with open("blogpost/posterior_draws.json", "w") as f:
    json.dump(output, f, separators=(',',':'))

fsize = os.path.getsize("blogpost/posterior_draws.json")
print(f"\nExported {len(draws)} draws x {len(draws[0])} params = {len(draws)*len(draws[0])} values")
print(f"File size: {fsize/1024:.0f} KB")

# Verify
arr = np.array(draws)
print(f"\nVerification (exported mean vs full posterior mean):")
for i, name in enumerate(output['layout'][:9]):
    full = [alpha_v, bd, bday, baut, bsp, blen, bdis, bwi, bse][i]
    print(f"  {name:15s}: exported={arr[:,i].mean():+.4f}  full={full.mean():+.4f}")
