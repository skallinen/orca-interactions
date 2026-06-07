#!/usr/bin/env python3
"""
Validate the night/daylight finding end-to-end.
"""
import os, json, warnings
warnings.filterwarnings("ignore")
import numpy as np
import pandas as pd
from scipy import stats

BASE = os.path.dirname(__file__)
RAW_DIR = os.path.join(os.path.dirname(BASE), "orca_data")
DATA_DIR = os.path.join(BASE, "data")

print("=" * 70)
print("  NIGHT vs DAY VALIDATION")
print("=" * 70)

# ═══════════════════════════════════════════════════════════
# STEP 1: Raw scraped data
# ═══════════════════════════════════════════════════════════
print("\n█ STEP 1: Raw scraped CSVs")
print("-" * 50)

inc = pd.read_csv(os.path.join(RAW_DIR, "incident_reports.csv"))
une = pd.read_csv(os.path.join(RAW_DIR, "uneventful_reports.csv"))

# Find daylight column
for label, df in [("INCIDENT", inc), ("UNEVENTFUL", une)]:
    day_cols = [c for c in df.columns if 'dark' in c.lower() or 'daylight' in c.lower() or 'night' in c.lower() or 'light' in c.lower()]
    print(f"\n{label} - daylight-related columns: {day_cols}")
    for col in day_cols:
        print(f"  Column '{col}':")
        vc = df[col].value_counts(dropna=False)
        for val, count in vc.items():
            print(f"    {str(val):30s}: {count:4d}  ({count/len(df)*100:.1f}%)")

# ═══════════════════════════════════════════════════════════
# STEP 2: Processed data
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 2: Processed modeling_data.csv")
print("-" * 50)

df = pd.read_csv(os.path.join(DATA_DIR, "modeling_data.csv"))

print(f"Total rows: {len(df)}")
print(f"is_daytime non-null: {df['is_daytime'].notna().sum()}")
print(f"\nis_daytime value counts:")
for val in sorted(df['is_daytime'].dropna().unique()):
    n = (df['is_daytime'] == val).sum()
    n_inc = ((df['is_daytime'] == val) & (df['interaction'] == 1)).sum()
    n_une = ((df['is_daytime'] == val) & (df['interaction'] == 0)).sum()
    rate = n_inc / n if n > 0 else 0
    label = "Daytime" if val == 1 else "Night"
    print(f"  {label:10s} (is_daytime={int(val)}): n={n:4d}  incidents={n_inc:3d}  uneventful={n_une:3d}  rate={rate:.1%}")

# ═══════════════════════════════════════════════════════════
# STEP 3: Contingency table + tests
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 3: Statistical tests")
print("-" * 50)

mask = df['is_daytime'].notna()
df_d = df[mask]
ct = pd.crosstab(df_d['is_daytime'] == 0, df_d['interaction'] == 1, margins=True)
ct.index = ['Daytime', 'Night', 'Total']
ct.columns = ['Uneventful', 'Incident', 'Total']
print(ct)

chi2, p_val, dof, _ = stats.chi2_contingency(
    [[ct.iloc[0,0], ct.iloc[0,1]], [ct.iloc[1,0], ct.iloc[1,1]]])
print(f"\nChi-squared = {chi2:.2f}, df = {dof}, p = {p_val:.2e}")

odds_ratio, fisher_p = stats.fisher_exact(
    [[ct.iloc[0,0], ct.iloc[0,1]], [ct.iloc[1,0], ct.iloc[1,1]]])
print(f"Fisher's exact: OR = {odds_ratio:.3f}, p = {fisher_p:.2e}")

# ═══════════════════════════════════════════════════════════
# STEP 4: How many night reports are there really?
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 4: Night reports breakdown")
print("-" * 50)

night = df_d[df_d['is_daytime'] == 0]
day = df_d[df_d['is_daytime'] == 1]
print(f"Night reports total: {len(night)}")
print(f"  Night incidents:    {(night['interaction']==1).sum()}")
print(f"  Night uneventful:   {(night['interaction']==0).sum()}")
print(f"\nDaytime reports total: {len(day)}")
print(f"  Day incidents:      {(day['interaction']==1).sum()}")
print(f"  Day uneventful:     {(day['interaction']==0).sum()}")
print(f"\nNight is {len(night)/len(df_d)*100:.1f}% of all reports with daylight info")

# ═══════════════════════════════════════════════════════════
# STEP 5: Confounds - what else differs between night/day?
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 5: Confound check - Night vs Day boats")
print("-" * 50)

for var in ['depth_ord_std', 'sailing_mode_idx', 'speed_ord_std', 
            'autopilot_on', 'distance_ord_std', 'antifoul_idx',
            'boat_length_ord_std', 'wind_ord_std', 'sea_state_ord_std']:
    if var in df.columns:
        night_vals = night[var].dropna()
        day_vals = day[var].dropna()
        if len(night_vals) > 5 and len(day_vals) > 5:
            t, p = stats.ttest_ind(night_vals, day_vals)
            print(f"  {var:25s}: Night mean={night_vals.mean():+.3f}  Day mean={day_vals.mean():+.3f}  diff={night_vals.mean()-day_vals.mean():+.3f}  p={p:.4f}")

# Sailing mode distribution
print("\n  Sailing mode at night vs day:")
with open(os.path.join(DATA_DIR, "metadata.json")) as f:
    meta = json.load(f)
sailing_cats = meta['categories']['sailing_mode']
for label, subset in [("Night", night), ("Day", day)]:
    modes = subset['sailing_mode_idx'].dropna()
    pcts = []
    for s_idx, s_name in enumerate(sailing_cats):
        pct = (modes == s_idx).mean() * 100
        pcts.append(f"{s_name}:{pct:.0f}%")
    print(f"    {label:6s}: {', '.join(pcts)}")

# ═══════════════════════════════════════════════════════════
# STEP 6: Is the effect plausible? What time do passages happen?
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 6: Plausibility check")
print("-" * 50)
print(f"Base interaction rate (all data): {df['interaction'].mean():.1%}")
print(f"Night interaction rate:           {night['interaction'].mean():.1%}")
print(f"Day interaction rate:             {day['interaction'].mean():.1%}")
print(f"Ratio (night/day):                {night['interaction'].mean() / day['interaction'].mean():.1f}x")
print(f"\nNight passages are {len(night)/len(df_d)*100:.1f}% of total")
print(f"But contain {(night['interaction']==1).sum()}/{(df_d['interaction']==1).sum()} = {(night['interaction']==1).sum()/(df_d['interaction']==1).sum()*100:.1f}% of all incidents")

# ═══════════════════════════════════════════════════════════
# STEP 7: Single-predictor Bayesian model
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 7: Single-predictor Bayesian model (daylight only)")
print("-" * 50)

import pymc as pm
import arviz as az

with pm.Model() as m_day:
    alpha = pm.Normal("alpha", mu=0, sigma=1)
    beta_day = pm.Normal("beta_day", mu=0, sigma=1)
    logit_p = alpha + beta_day * df_d.is_daytime.values
    y = pm.Bernoulli("y", logit_p=logit_p, observed=df_d.interaction.values)
    trace_day = pm.sample(draws=2000, tune=1000, chains=4, random_seed=42)

print("\nSingle-predictor model:")
print(az.summary(trace_day, var_names=["alpha", "beta_day"],
                 kind="stats", ci_prob=0.89).to_string())

# Interpret
alpha_s = trace_day.posterior["alpha"].values.flatten()
beta_s = trace_day.posterior["beta_day"].values.flatten()
p_night = 1 / (1 + np.exp(-(alpha_s)))  # is_daytime=0
p_day = 1 / (1 + np.exp(-(alpha_s + beta_s)))  # is_daytime=1
print(f"\nPredicted P(interaction | night): {p_night.mean():.1%} [{np.percentile(p_night, 5.5):.1%}, {np.percentile(p_night, 94.5):.1%}]")
print(f"Predicted P(interaction | day):   {p_day.mean():.1%} [{np.percentile(p_day, 5.5):.1%}, {np.percentile(p_day, 94.5):.1%}]")
print(f"Risk ratio (night/day):           {(p_night/p_day).mean():.1f}x")

print("\n" + "=" * 70)
print("  VALIDATION COMPLETE")
print("=" * 70)
