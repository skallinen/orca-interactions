#!/usr/bin/env python3
"""
Validate the black antifoul finding end-to-end.

1. Check raw scraped CSVs (before any processing)
2. Check processed modeling_data.csv
3. Look for confounds (is black antifoul correlated with other predictors?)
4. Run a simple frequentist chi-squared test
5. Fit a minimal single-predictor Bayesian model
6. Check for data coding errors
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
print("  ANTIFOUL VALIDATION: End-to-end check")
print("=" * 70)

# ═══════════════════════════════════════════════════════════
# STEP 1: Raw scraped data
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 1: Raw scraped CSVs (before any processing)")
print("-" * 50)

inc = pd.read_csv(os.path.join(RAW_DIR, "incident_reports.csv"))
une = pd.read_csv(os.path.join(RAW_DIR, "uneventful_reports.csv"))

# Find the antifoul column
antifoul_col_inc = [c for c in inc.columns if 'antifoul' in c.lower() or 'anti_foul' in c.lower() or 'anti-foul' in c.lower()]
antifoul_col_une = [c for c in une.columns if 'antifoul' in c.lower() or 'anti_foul' in c.lower() or 'anti-foul' in c.lower()]

print(f"Incident antifoul columns: {antifoul_col_inc}")
print(f"Uneventful antifoul columns: {antifoul_col_une}")

# Show all columns to find the right one
print(f"\nAll incident columns: {list(inc.columns)}")
print(f"\nAll uneventful columns: {list(une.columns)}")

# Try to find the right column name
for col_list, df, name in [(antifoul_col_inc, inc, "INCIDENT"), (antifoul_col_une, une, "UNEVENTFUL")]:
    if col_list:
        col = col_list[0]
        print(f"\n{name} - Column '{col}':")
        print(f"  Total rows: {len(df)}")
        print(f"  Non-null: {df[col].notna().sum()}")
        print(f"  Value counts:")
        vc = df[col].value_counts(dropna=False)
        for val, count in vc.items():
            print(f"    {str(val):25s}: {count:4d}  ({count/len(df)*100:.1f}%)")

# ═══════════════════════════════════════════════════════════
# STEP 2: Processed modeling data
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 2: Processed modeling_data.csv")
print("-" * 50)

df = pd.read_csv(os.path.join(DATA_DIR, "modeling_data.csv"))
with open(os.path.join(DATA_DIR, "metadata.json")) as f:
    meta = json.load(f)

print(f"Total rows: {len(df)}")
print(f"Antifoul categories in metadata: {meta['categories']['antifoul']}")

# Check antifoul_idx mapping
print(f"\nantifoul_idx value counts:")
for idx, cat in enumerate(meta['categories']['antifoul']):
    n = (df['antifoul_idx'] == idx).sum()
    n_inc = ((df['antifoul_idx'] == idx) & (df['interaction'] == 1)).sum()
    n_une = ((df['antifoul_idx'] == idx) & (df['interaction'] == 0)).sum()
    rate = n_inc / n if n > 0 else 0
    print(f"  [{idx}] {cat:15s}: n={n:4d}  incidents={n_inc:3d}  uneventful={n_une:3d}  rate={rate:.1%}")

# ═══════════════════════════════════════════════════════════
# STEP 3: Raw contingency table + chi-squared
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 3: Chi-squared test (Black vs Not-Black)")
print("-" * 50)

is_black = df['antifoul_idx'] == 0  # Black is index 0
interaction = df['interaction'] == 1

# 2x2 contingency table
ct = pd.crosstab(is_black, interaction, margins=True)
ct.index = ['Not Black', 'Black', 'Total']
ct.columns = ['Uneventful', 'Incident', 'Total']
print(ct)

chi2, p_val, dof, expected = stats.chi2_contingency(
    [[ct.iloc[0, 0], ct.iloc[0, 1]],
     [ct.iloc[1, 0], ct.iloc[1, 1]]])
print(f"\nChi-squared = {chi2:.2f}, df = {dof}, p = {p_val:.6f}")

# Fisher's exact test
odds_ratio, fisher_p = stats.fisher_exact(
    [[ct.iloc[0, 0], ct.iloc[0, 1]],
     [ct.iloc[1, 0], ct.iloc[1, 1]]])
print(f"Fisher's exact: OR = {odds_ratio:.3f}, p = {fisher_p:.6f}")

# ═══════════════════════════════════════════════════════════
# STEP 3b: Black vs Coppercoat specifically
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 3b: Black vs Coppercoat (the specific contrast)")
print("-" * 50)

copper_idx = meta['categories']['antifoul'].index('Coppercoat')
mask_bc = df['antifoul_idx'].isin([0, copper_idx])
df_bc = df[mask_bc].copy()

ct2 = pd.crosstab(df_bc['antifoul_idx'] == 0, df_bc['interaction'] == 1)
ct2.index = ['Coppercoat', 'Black']
ct2.columns = ['Uneventful', 'Incident']
print(ct2)

chi2_bc, p_bc, _, _ = stats.chi2_contingency(ct2.values)
or_bc, fisher_p_bc = stats.fisher_exact(ct2.values)
print(f"\nChi-squared = {chi2_bc:.2f}, p = {p_bc:.6f}")
print(f"Fisher's exact: OR = {or_bc:.3f}, p = {fisher_p_bc:.6f}")
print(f"Black rate: {ct2.loc['Black','Incident'] / ct2.loc['Black'].sum():.1%}")
print(f"Coppercoat rate: {ct2.loc['Coppercoat','Incident'] / ct2.loc['Coppercoat'].sum():.1%}")

# ═══════════════════════════════════════════════════════════
# STEP 4: Check for confounds
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 4: Is black antifoul confounded with other variables?")
print("-" * 50)

# Compare key predictors between black and non-black
for var in ['depth_ord_std', 'is_daytime', 'sailing_mode_idx', 'boat_length_ord_std',
            'speed_ord_std', 'autopilot_on', 'distance_ord_std']:
    if var in df.columns:
        black_vals = df.loc[is_black & df[var].notna(), var]
        other_vals = df.loc[~is_black & df[var].notna(), var]
        if len(black_vals) > 5 and len(other_vals) > 5:
            t, p = stats.ttest_ind(black_vals, other_vals)
            print(f"  {var:25s}: Black mean={black_vals.mean():.3f}  Other mean={other_vals.mean():.3f}  diff={black_vals.mean()-other_vals.mean():+.3f}  p={p:.4f}")

# Cross-tab: sailing mode by antifoul
print("\n  Sailing mode distribution by antifoul colour:")
sailing_cats = meta['categories']['sailing_mode']
for af_idx, af_name in enumerate(meta['categories']['antifoul']):
    mask_af = df['antifoul_idx'] == af_idx
    if mask_af.sum() < 10:
        continue
    modes = df.loc[mask_af & df['sailing_mode_idx'].notna(), 'sailing_mode_idx']
    mode_pcts = []
    for s_idx, s_name in enumerate(sailing_cats):
        pct = (modes == s_idx).mean() * 100
        mode_pcts.append(f"{s_name[:3]}:{pct:.0f}%")
    print(f"    {af_name:12s} (n={mask_af.sum():3d}): {', '.join(mode_pcts)}")

# ═══════════════════════════════════════════════════════════
# STEP 5: Spot-check raw data entries
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 5: Spot-check - show 10 random Black antifoul incidents")
print("-" * 50)

black_incidents = df[(df['antifoul_idx'] == 0) & (df['interaction'] == 1)]
sample = black_incidents.sample(min(10, len(black_incidents)), random_state=42)
cols_to_show = ['interaction', 'antifoul_idx', 'sailing_mode_idx', 'depth_ord_std',
                'is_daytime', 'autopilot_on', 'speed_ord_std', 'boat_length_ord_std']
cols_available = [c for c in cols_to_show if c in sample.columns]
print(sample[cols_available].to_string())

print("\n\n█ STEP 5b: Spot-check - show 10 random Black antifoul UNEVENTFUL")
print("-" * 50)
black_uneventful = df[(df['antifoul_idx'] == 0) & (df['interaction'] == 0)]
sample2 = black_uneventful.sample(min(10, len(black_uneventful)), random_state=42)
print(sample2[cols_available].to_string())

# ═══════════════════════════════════════════════════════════
# STEP 6: Single-predictor Bayesian model (antifoul only)
# ═══════════════════════════════════════════════════════════
print("\n\n█ STEP 6: Single-predictor Bayesian model (antifoul only)")
print("-" * 50)

import pymc as pm
import arviz as az

mask_af = df['antifoul_idx'].notna()
df_af = df[mask_af].reset_index(drop=True)
n_antifoul = len(meta['categories']['antifoul'])

with pm.Model() as m_af:
    alpha = pm.Normal("alpha", mu=0, sigma=1)
    a_antifoul = pm.Normal("a_antifoul", mu=0, sigma=0.5, shape=n_antifoul)
    logit_p = alpha + a_antifoul[df_af.antifoul_idx.values.astype(int)]
    y = pm.Bernoulli("y", logit_p=logit_p, observed=df_af.interaction.values)
    trace_af = pm.sample(draws=2000, tune=1000, chains=4, random_seed=42)

print("\nSingle-predictor model (antifoul only, no other controls):")
print(az.summary(trace_af, var_names=["alpha", "a_antifoul"],
                 kind="stats", ci_prob=0.89).to_string())

# Compute contrasts
print("\n\nPairwise contrasts (from single-predictor model):")
samples = trace_af.posterior["a_antifoul"].values.reshape(-1, n_antifoul)
for i, name_i in enumerate(meta['categories']['antifoul']):
    for j, name_j in enumerate(meta['categories']['antifoul']):
        if i < j:
            diff = samples[:, i] - samples[:, j]
            m = diff.mean()
            lo, hi = np.percentile(diff, [5.5, 94.5])
            prob = (diff > 0).mean()
            if abs(m) > 0.3:
                print(f"  {name_i:12s} - {name_j:12s}: {m:+.3f} [{lo:+.3f}, {hi:+.3f}]  P({name_i}>{name_j})={prob:.1%}  OR={np.exp(m):.2f}")

# Black vs Coppercoat
print("\n\nBlack vs Coppercoat (single predictor, no controls):")
diff_bc = samples[:, 0] - samples[:, copper_idx]
print(f"  Difference: {diff_bc.mean():+.3f} [{np.percentile(diff_bc, 5.5):+.3f}, {np.percentile(diff_bc, 94.5):+.3f}]")
print(f"  OR: {np.exp(diff_bc.mean()):.2f}")
print(f"  P(Black > Coppercoat): {(diff_bc > 0).mean():.1%}")

print("\n\n" + "=" * 70)
print("  VALIDATION COMPLETE")
print("=" * 70)
