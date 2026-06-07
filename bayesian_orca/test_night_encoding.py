#!/usr/bin/env python3
"""
Test how the night effect changes under different encodings of is_daytime.

Encoding A (current): multi-period passages containing "Day" -> daytime
Encoding B (new):     any passage containing "Night" -> night

Then refit M3 under both and compare the night coefficient.
"""
import json, warnings
warnings.filterwarnings("ignore")
import numpy as np
import pandas as pd
import pymc as pm
import arviz as az

# Load raw data
RAW_DIR = "orca_data"
inc = pd.read_csv(f"{RAW_DIR}/incident_reports.csv")
une = pd.read_csv(f"{RAW_DIR}/uneventful_reports.csv")

print("=" * 70)
print("  ENCODING COMPARISON: Night definition")
print("=" * 70)

# Show the raw distribution
print("\n█ Uneventful 'darkness_or_daylight' values containing 'Night':")
night_mask = une['darkness_or_daylight'].str.contains('Night', na=False)
print(f"  Passages containing 'Night': {night_mask.sum()}")
for val, count in une.loc[night_mask, 'darkness_or_daylight'].value_counts().items():
    print(f"    {val:40s}: {count}")

print(f"\n  Passages NOT containing 'Night': {(~night_mask).sum()}")
print(f"  NaN: {une['darkness_or_daylight'].isna().sum()}")

# Load modeling data
df = pd.read_csv("bayesian_orca/data/modeling_data.csv")
with open("bayesian_orca/data/metadata.json") as f:
    meta = json.load(f)

print(f"\n█ Current encoding (A):")
print(f"  Night (is_daytime=0): {(df['is_daytime']==0).sum()}")
print(f"  Day   (is_daytime=1): {(df['is_daytime']==1).sum()}")

# Create encoding B: if the raw text contains "Night" -> is_daytime=0
# We need to map back from modeling_data to raw data
# The modeling data was created by merging incident + uneventful
# Let's re-derive is_daytime_B from the raw darkness_or_daylight column

# Rebuild the combined dataframe to get the raw column
inc_raw = inc.copy()
inc_raw['interaction'] = 1
une_raw = une.copy()
une_raw['interaction'] = 0

# For incidents: single values
# Night -> 0, else -> 1
inc_daytime_A = inc_raw['darkness_or_daylight'].map(
    lambda x: 0 if str(x).strip() == 'Night' else 1 if pd.notna(x) else np.nan)
inc_daytime_B = inc_daytime_A  # Same for incidents (they have single values)

# For uneventful: 
# Encoding A (current): contains "Day" -> 1, else check for Night only
# Encoding B (new): contains "Night" -> 0
une_daytime_A = une_raw['darkness_or_daylight'].map(
    lambda x: 0 if str(x).strip() == 'Night' else 1 if pd.notna(x) else np.nan)
une_daytime_B = une_raw['darkness_or_daylight'].map(
    lambda x: 0 if 'Night' in str(x) else 1 if pd.notna(x) else np.nan)

print(f"\n█ Encoding B (any 'Night' mention -> night):")
print(f"  Uneventful reclassified from day to night: {int((une_daytime_A == 1).sum() - (une_daytime_B == 1).sum())}")

# Apply encoding B to the modeling dataframe
# We need to match rows. The modeling data has the same row order as the combined raw data.
daytime_B = pd.concat([inc_daytime_B, une_daytime_B], ignore_index=True)

# But modeling_data may have dropped some rows during processing.
# Let's just modify the existing modeling_data directly.
# The is_daytime column was derived from the raw data in the same order.

# Actually, let's just rebuild is_daytime_B from the raw column that should be in modeling_data
# or we can look at the original darkness_or_daylight

# Simpler approach: find the darkness_or_daylight column in raw data,
# create a merged dataset with both encodings

combined = pd.concat([inc_raw, une_raw], ignore_index=True)
combined['is_daytime_A'] = combined['darkness_or_daylight'].map(
    lambda x: 0 if str(x).strip() in ['Night', 'Dawn'] else 1 if pd.notna(x) else np.nan)
combined['is_daytime_B'] = combined['darkness_or_daylight'].map(
    lambda x: 0 if 'Night' in str(x) else 1 if pd.notna(x) else np.nan)

# Now we need to check what the current encoding actually is
print(f"\n█ Comparing encodings on the full combined data ({len(combined)} rows):")
print(f"  Encoding A - Night: {(combined['is_daytime_A']==0).sum()}, Day: {(combined['is_daytime_A']==1).sum()}")
print(f"  Encoding B - Night: {(combined['is_daytime_B']==0).sum()}, Day: {(combined['is_daytime_B']==1).sum()}")
print(f"  Rows that change: {((combined['is_daytime_A'] != combined['is_daytime_B']) & combined['is_daytime_A'].notna()).sum()}")

# Show the changed rows by interaction status
changed = (combined['is_daytime_A'] != combined['is_daytime_B']) & combined['is_daytime_A'].notna()
print(f"\n  Changed rows breakdown:")
print(f"    Incidents reclassified:  {(changed & (combined['interaction']==1)).sum()}")
print(f"    Uneventful reclassified: {(changed & (combined['interaction']==0)).sum()}")

# Now compare the rates
for label, col in [("Encoding A (current)", "is_daytime_A"), ("Encoding B (Night-inclusive)", "is_daytime_B")]:
    print(f"\n█ {label}:")
    for val, name in [(0, "Night"), (1, "Day")]:
        mask = combined[col] == val
        n = mask.sum()
        n_inc = (mask & (combined['interaction']==1)).sum()
        n_une = (mask & (combined['interaction']==0)).sum()
        rate = n_inc/n if n > 0 else 0
        print(f"  {name:6s}: n={n:4d}  incidents={n_inc:3d}  uneventful={n_une:3d}  rate={rate:.1%}")

# ═══════════════════════════════════════════════════════════
# Refit M3 with encoding B
# ═══════════════════════════════════════════════════════════
print("\n\n█ Refitting M3 with encoding B...")

# Apply encoding B to modeling data
# We need to update is_daytime in the modeling dataframe
# The modeling_data rows correspond to combined rows (same order)
df['is_daytime_B'] = combined['is_daytime_B'].iloc[:len(df)].values

# Actually, the modeling data may have been filtered differently.
# Let's check the length
print(f"  Modeling data rows: {len(df)}, Combined rows: {len(combined)}")

# The modeling data IS the combined data, just with extra computed columns
# So we can directly assign
df['is_daytime_B'] = combined.loc[df.index, 'is_daytime_B'].values if len(df) == len(combined) else combined['is_daytime_B'].iloc[:len(df)].values

mask = df[['depth_ord_std','is_daytime_B','autopilot_on','speed_ord_std',
           'boat_length_ord_std','distance_ord_std','wind_ord_std',
           'sea_state_ord_std','sailing_mode_idx','antifoul_idx',
           'hull_colour_idx','rudder_idx']].notna().all(axis=1)
d = df[mask].reset_index(drop=True)

n_sailing = len(meta['categories']['sailing_mode'])
n_antifoul = len(meta['categories']['antifoul'])
n_hull = len(meta['categories']['hull_colour'])
n_rudder = len(meta['categories']['rudder'])

print(f"  Fitting on {len(d)} observations...")

with pm.Model() as M3_B:
    alpha = pm.Normal("alpha", mu=-1, sigma=1)
    beta_depth = pm.Normal("beta_depth", mu=0, sigma=1)
    beta_daylight = pm.Normal("beta_daylight", mu=0, sigma=1)
    beta_autopilot = pm.Normal("beta_autopilot", mu=0, sigma=1)
    beta_speed = pm.Normal("beta_speed", mu=0, sigma=0.5)
    beta_length = pm.Normal("beta_length", mu=0, sigma=0.5)
    beta_distance = pm.Normal("beta_distance", mu=0, sigma=0.5)
    beta_wind = pm.Normal("beta_wind", mu=0, sigma=0.5)
    beta_sea = pm.Normal("beta_sea", mu=0, sigma=0.5)
    a_sailing = pm.Normal("alpha_sailing", mu=0, sigma=0.5, shape=n_sailing)
    a_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=0.5, shape=n_antifoul)
    a_hull = pm.Normal("alpha_hull", mu=0, sigma=0.5, shape=n_hull)
    a_rudder = pm.Normal("alpha_rudder", mu=0, sigma=0.5, shape=n_rudder)

    logit_p = (alpha
               + beta_depth * d.depth_ord_std.values
               + beta_daylight * d.is_daytime_B.values  # <-- encoding B
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
    trace_B = pm.sample(draws=2000, tune=1000, chains=4, random_seed=42)

# Also refit with encoding A for direct comparison
print("\n█ Refitting M3 with encoding A (current)...")
mask_A = df[['depth_ord_std','is_daytime','autopilot_on','speed_ord_std',
             'boat_length_ord_std','distance_ord_std','wind_ord_std',
             'sea_state_ord_std','sailing_mode_idx','antifoul_idx',
             'hull_colour_idx','rudder_idx']].notna().all(axis=1)
dA = df[mask_A].reset_index(drop=True)

with pm.Model() as M3_A:
    alpha = pm.Normal("alpha", mu=-1, sigma=1)
    beta_depth = pm.Normal("beta_depth", mu=0, sigma=1)
    beta_daylight = pm.Normal("beta_daylight", mu=0, sigma=1)
    beta_autopilot = pm.Normal("beta_autopilot", mu=0, sigma=1)
    beta_speed = pm.Normal("beta_speed", mu=0, sigma=0.5)
    beta_length = pm.Normal("beta_length", mu=0, sigma=0.5)
    beta_distance = pm.Normal("beta_distance", mu=0, sigma=0.5)
    beta_wind = pm.Normal("beta_wind", mu=0, sigma=0.5)
    beta_sea = pm.Normal("beta_sea", mu=0, sigma=0.5)
    a_sailing = pm.Normal("alpha_sailing", mu=0, sigma=0.5, shape=n_sailing)
    a_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=0.5, shape=n_antifoul)
    a_hull = pm.Normal("alpha_hull", mu=0, sigma=0.5, shape=n_hull)
    a_rudder = pm.Normal("alpha_rudder", mu=0, sigma=0.5, shape=n_rudder)

    logit_p = (alpha
               + beta_depth * dA.depth_ord_std.values
               + beta_daylight * dA.is_daytime.values  # <-- encoding A
               + beta_autopilot * dA.autopilot_on.values
               + beta_speed * dA.speed_ord_std.values
               + beta_length * dA.boat_length_ord_std.values
               + beta_distance * dA.distance_ord_std.values
               + beta_wind * dA.wind_ord_std.values
               + beta_sea * dA.sea_state_ord_std.values
               + a_sailing[dA.sailing_mode_idx.values.astype(int)]
               + a_antifoul[dA.antifoul_idx.values.astype(int)]
               + a_hull[dA.hull_colour_idx.values.astype(int)]
               + a_rudder[dA.rudder_idx.values.astype(int)])

    y = pm.Bernoulli("y", logit_p=logit_p, observed=dA.interaction.values)
    trace_A = pm.sample(draws=2000, tune=1000, chains=4, random_seed=42)

# Compare
print("\n" + "=" * 70)
print("  COMPARISON: Night coefficient (beta_daylight)")
print("  Note: negative = daylight is protective, i.e. night is riskier")
print("=" * 70)

sumA = az.summary(trace_A, var_names=["beta_daylight"], kind="stats", ci_prob=0.89)
sumB = az.summary(trace_B, var_names=["beta_daylight"], kind="stats", ci_prob=0.89)

print(f"\n  Encoding A (current):        mean={sumA['mean'].iloc[0]:+.3f}  SD={sumA['sd'].iloc[0]:.3f}  89% HDI=[{sumA['eti89_lb'].iloc[0]:+.3f}, {sumA['eti89_ub'].iloc[0]:+.3f}]")
print(f"  Encoding B (Night-inclusive): mean={sumB['mean'].iloc[0]:+.3f}  SD={sumB['sd'].iloc[0]:.3f}  89% HDI=[{sumB['eti89_lb'].iloc[0]:+.3f}, {sumB['eti89_ub'].iloc[0]:+.3f}]")

or_A = np.exp(-sumA['mean'].iloc[0])  # flip sign: night vs day
or_B = np.exp(-sumB['mean'].iloc[0])
print(f"\n  Odds ratio (night/day) A: {or_A:.2f}x")
print(f"  Odds ratio (night/day) B: {or_B:.2f}x")

# Also compare all other coefficients
print("\n█ All coefficients comparison:")
vars_to_compare = ["alpha", "beta_depth", "beta_daylight", "beta_autopilot",
                   "beta_speed", "beta_distance", "beta_wind", "beta_sea"]
sA = az.summary(trace_A, var_names=vars_to_compare, kind="stats", ci_prob=0.89)
sB = az.summary(trace_B, var_names=vars_to_compare, kind="stats", ci_prob=0.89)

print(f"  {'Parameter':20s}  {'Encoding A':>10s}  {'Encoding B':>10s}  {'Change':>8s}")
for idx in sA.index:
    mA = sA.loc[idx, 'mean']
    mB = sB.loc[idx, 'mean']
    print(f"  {idx:20s}  {mA:+10.3f}  {mB:+10.3f}  {mB-mA:+8.3f}")

print("\n" + "=" * 70)
print("  DONE")
print("=" * 70)
