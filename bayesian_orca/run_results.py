#!/usr/bin/env python3
"""
Run just the model comparison and results steps using already-fitted models.
Re-fits models (fast) since traces weren't saved before the crash.
"""
import os, json, sys, warnings
warnings.filterwarnings("ignore")

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns
import pymc as pm
import arviz as az

sys.path.insert(0, os.path.dirname(__file__))
from models import (load_data, build_model_3, build_model_4,
                     OUTPUT_DIR, RANDOM_SEED, N_CHAINS, N_DRAWS, N_TUNE, TARGET_ACCEPT)

os.makedirs(OUTPUT_DIR, exist_ok=True)

print("Loading data...")
df, meta = load_data()

# Re-fit M3 and M4 (they're fast)
print("\n" + "="*60)
print("RE-FITTING M3 and M4 for comparison and results...")
print("="*60)

m3, df3 = build_model_3(df, meta)
with m3:
    trace3 = pm.sample(draws=N_DRAWS, tune=N_TUNE, chains=N_CHAINS,
                        random_seed=RANDOM_SEED, target_accept=TARGET_ACCEPT)

m4, df4 = build_model_4(df, meta)
with m4:
    trace4 = pm.sample(draws=N_DRAWS, tune=N_TUNE, chains=N_CHAINS,
                        random_seed=RANDOM_SEED, target_accept=TARGET_ACCEPT)

# ═══════════════════════════════════════════════════════════════
# MODEL COMPARISON
# ═══════════════════════════════════════════════════════════════
print("\n" + "="*60)
print("MODEL COMPARISON (PSIS-LOO)")
print("="*60)

try:
    compare_dict = {"M3_primary": trace3, "M4_full": trace4}
    comparison = az.compare(compare_dict)
    print(comparison.to_string())
except Exception as e:
    print(f"  az.compare failed: {e}")
    print("  Computing LOO individually...")
    for name, trace in [("M3_primary", trace3), ("M4_full", trace4)]:
        try:
            loo = az.loo(trace)
            print(f"\n  {name}: ELPD = {loo.elpd_loo:.1f} ± {loo.se:.1f}")
            if hasattr(loo, 'pareto_k'):
                k = loo.pareto_k.values if hasattr(loo.pareto_k, 'values') else loo.pareto_k
                n_bad = (np.array(k) > 0.7).sum()
                print(f"    Pareto k > 0.7: {n_bad}")
        except Exception as e2:
            print(f"  LOO for {name} failed: {e2}")

# ═══════════════════════════════════════════════════════════════
# COEFFICIENT ANALYSIS (M3 — primary model)
# ═══════════════════════════════════════════════════════════════
print("\n" + "="*60)
print("COEFFICIENT ANALYSIS — M3 (Primary Model)")
print("="*60)

slope_vars = [v for v in trace3.posterior.data_vars if v.startswith("beta_")]
summary3 = az.summary(trace3, var_names=slope_vars, ci_prob=0.89)
for col in ["mean", "sd", "eti89_lb", "eti89_ub"]:
    if col in summary3.columns:
        summary3[col] = pd.to_numeric(summary3[col], errors="coerce")

print("\n  Slope posteriors (effect on log-odds per 1 SD change):")
print("  " + "-"*80)
for var in slope_vars:
    row = summary3.loc[var]
    m, lb, ub = float(row["mean"]), float(row["eti89_lb"]), float(row["eti89_ub"])
    direction = "↑ risk" if m > 0 else "↓ risk"
    sig = " ***" if (lb > 0 or ub < 0) else ""
    # Convert to odds ratio
    odds_ratio = np.exp(m)
    print(f"    {var:25s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}]  OR={odds_ratio:.2f}  {direction}{sig}")

# Index variables
print("\n  Category effects (index variables):")
for var_prefix, cat_name in [("alpha_rudder", "rudder"),
                              ("alpha_antifoul", "antifoul"),
                              ("alpha_hull", "hull_colour"),
                              ("alpha_sailing", "sailing_mode")]:
    if var_prefix not in trace3.posterior.data_vars:
        continue
    cats = meta["categories"][cat_name]
    print(f"\n  {cat_name}:")
    samples = trace3.posterior[var_prefix].values.reshape(-1, len(cats))
    for i, cat in enumerate(cats):
        vals = samples[:, i]
        m = vals.mean()
        lb, ub = np.percentile(vals, [5.5, 94.5])
        odds_ratio = np.exp(m)
        sig = " ***" if (lb > 0 or ub < 0) else ""
        print(f"    [{i}] {cat:25s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}]  OR={odds_ratio:.2f}{sig}")

# ═══════════════════════════════════════════════════════════════
# COEFFICIENT ANALYSIS (M4 — full model)
# ═══════════════════════════════════════════════════════════════
print("\n" + "="*60)
print("COEFFICIENT ANALYSIS — M4 (Full Model)")
print("="*60)

slope_vars4 = [v for v in trace4.posterior.data_vars if v.startswith("beta_")]
summary4 = az.summary(trace4, var_names=slope_vars4, ci_prob=0.89)
for col in ["mean", "sd", "eti89_lb", "eti89_ub"]:
    if col in summary4.columns:
        summary4[col] = pd.to_numeric(summary4[col], errors="coerce")

print("\n  Slope posteriors:")
print("  " + "-"*80)
for var in slope_vars4:
    row = summary4.loc[var]
    m, lb, ub = float(row["mean"]), float(row["eti89_lb"]), float(row["eti89_ub"])
    odds_ratio = np.exp(m)
    direction = "↑ risk" if m > 0 else "↓ risk"
    sig = " ***" if (lb > 0 or ub < 0) else ""
    print(f"    {var:25s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}]  OR={odds_ratio:.2f}  {direction}{sig}")

# Index variables for M4
for var_prefix, cat_name in [("alpha_antifoul", "antifoul"),
                              ("alpha_sailing", "sailing_mode")]:
    cats = meta["categories"][cat_name]
    print(f"\n  {cat_name} (M4):")
    samples = trace4.posterior[var_prefix].values.reshape(-1, len(cats))
    for i, cat in enumerate(cats):
        vals = samples[:, i]
        m = vals.mean()
        lb, ub = np.percentile(vals, [5.5, 94.5])
        odds_ratio = np.exp(m)
        sig = " ***" if (lb > 0 or ub < 0) else ""
        print(f"    [{i}] {cat:25s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}]  OR={odds_ratio:.2f}{sig}")

# ═══════════════════════════════════════════════════════════════
# RISK SCENARIOS
# ═══════════════════════════════════════════════════════════════
print("\n" + "="*60)
print("RISK SCENARIOS (using M3)")
print("="*60)

std = meta["standardization"]

def standardize_val(raw, param):
    return (raw - std[param]["mean"]) / std[param]["sd"]

post = trace3.posterior

# Need to handle index variables too
# Find the median sailing mode and antifoul indices
sailing_cats = meta["categories"]["sailing_mode"]
antifoul_cats = meta["categories"]["antifoul"]
rudder_cats = meta["categories"]["rudder"]
hull_cats = meta["categories"]["hull_colour"]

def compute_scenario_p(scenario_params, sailing_idx=3, antifoul_idx=1,
                        rudder_idx=3, hull_idx=2):
    """Compute posterior probability for a scenario."""
    alpha = post["alpha"].values.flatten()
    logit_p = alpha.copy()

    # Add slope effects
    for param_name, value in scenario_params.items():
        if param_name in post.data_vars:
            logit_p += post[param_name].values.flatten() * value

    # Add index effects
    logit_p += post["alpha_sailing"].values.reshape(-1, len(sailing_cats))[:, sailing_idx]
    logit_p += post["alpha_antifoul"].values.reshape(-1, len(antifoul_cats))[:, antifoul_idx]
    logit_p += post["alpha_rudder"].values.reshape(-1, len(rudder_cats))[:, rudder_idx]
    logit_p += post["alpha_hull"].values.reshape(-1, len(hull_cats))[:, hull_idx]

    return 1 / (1 + np.exp(-logit_p))

scenarios = [
    {
        "name": "Typical yacht: 12m sail, motorsailing, 5-7kts, 200m+ depth, daytime, F3-4, blue antifoul",
        "params": {
            "beta_length": standardize_val(1, "boat_length_ord"),
            "beta_speed": standardize_val(2, "speed_ord"),
            "beta_depth": standardize_val(3, "depth_ord"),
            "beta_distance": standardize_val(3, "distance_ord"),
            "beta_wind": standardize_val(1, "wind_ord"),
            "beta_sea": standardize_val(1, "sea_state_ord"),
            "beta_daylight": 1,
            "beta_autopilot": 1,
        },
        "sailing_idx": 2, "antifoul_idx": 1, "rudder_idx": 3, "hull_idx": 2,
    },
    {
        "name": "Higher risk: 15m+ sail, motoring, 3-4kts, 40-200m depth, night, black antifoul",
        "params": {
            "beta_length": standardize_val(3, "boat_length_ord"),
            "beta_speed": standardize_val(1, "speed_ord"),
            "beta_depth": standardize_val(2, "depth_ord"),
            "beta_distance": standardize_val(1, "distance_ord"),
            "beta_wind": standardize_val(0, "wind_ord"),
            "beta_sea": standardize_val(0, "sea_state_ord"),
            "beta_daylight": 0,
            "beta_autopilot": 0,
        },
        "sailing_idx": 1, "antifoul_idx": 0, "rudder_idx": 4, "hull_idx": 2,
    },
    {
        "name": "Lower risk: 10m sail, sailing, 5-7kts, 200m+ depth, daytime, coppercoat",
        "params": {
            "beta_length": standardize_val(0, "boat_length_ord"),
            "beta_speed": standardize_val(2, "speed_ord"),
            "beta_depth": standardize_val(3, "depth_ord"),
            "beta_distance": standardize_val(3, "distance_ord"),
            "beta_wind": standardize_val(1, "wind_ord"),
            "beta_sea": standardize_val(1, "sea_state_ord"),
            "beta_daylight": 1,
            "beta_autopilot": 1,
        },
        "sailing_idx": 3, "antifoul_idx": 2, "rudder_idx": 3, "hull_idx": 2,
    },
]

for s in scenarios:
    p = compute_scenario_p(
        s["params"],
        sailing_idx=s.get("sailing_idx", 3),
        antifoul_idx=s.get("antifoul_idx", 1),
        rudder_idx=s.get("rudder_idx", 3),
        hull_idx=s.get("hull_idx", 2),
    )
    print(f"\n  📊 {s['name']}")
    print(f"     P(interaction):")
    print(f"       Mean:   {p.mean():.1%}")
    print(f"       Median: {np.median(p):.1%}")
    print(f"       89% HDI: [{np.percentile(p, 5.5):.1%}, {np.percentile(p, 94.5):.1%}]")

# ═══════════════════════════════════════════════════════════════
# CONTRAST PLOTS — key comparisons
# ═══════════════════════════════════════════════════════════════
print("\n" + "="*60)
print("KEY CONTRASTS")
print("="*60)

# Antifoul: Black vs Coppercoat
antifoul_samples = trace3.posterior["alpha_antifoul"].values.reshape(-1, len(antifoul_cats))
black_vs_copper = antifoul_samples[:, 0] - antifoul_samples[:, 2]
print(f"\n  Black vs Coppercoat antifoul:")
print(f"    Difference in log-odds: {black_vs_copper.mean():.3f} "
      f"[{np.percentile(black_vs_copper, 5.5):.3f}, {np.percentile(black_vs_copper, 94.5):.3f}]")
print(f"    Odds ratio: {np.exp(black_vs_copper.mean()):.2f}")
print(f"    P(Black > Coppercoat): {(black_vs_copper > 0).mean():.1%}")

# Sailing mode: Motoring vs Sailing
sailing_samples = trace3.posterior["alpha_sailing"].values.reshape(-1, len(sailing_cats))
motor_vs_sail = sailing_samples[:, 1] - sailing_samples[:, 3]
print(f"\n  Motoring vs Sailing:")
print(f"    Difference in log-odds: {motor_vs_sail.mean():.3f} "
      f"[{np.percentile(motor_vs_sail, 5.5):.3f}, {np.percentile(motor_vs_sail, 94.5):.3f}]")
print(f"    Odds ratio: {np.exp(motor_vs_sail.mean()):.2f}")
print(f"    P(Motoring > Sailing): {(motor_vs_sail > 0).mean():.1%}")

# Day vs Night effect
beta_daylight = trace3.posterior["beta_daylight"].values.flatten()
print(f"\n  Night vs Day:")
print(f"    Effect of daylight: {beta_daylight.mean():.3f} "
      f"[{np.percentile(beta_daylight, 5.5):.3f}, {np.percentile(beta_daylight, 94.5):.3f}]")
print(f"    Night/Day odds ratio: {np.exp(-beta_daylight.mean()):.2f}x more risk at night")

# Autopilot effect
beta_ap = trace3.posterior["beta_autopilot"].values.flatten()
print(f"\n  Autopilot ON vs OFF:")
print(f"    Effect: {beta_ap.mean():.3f} "
      f"[{np.percentile(beta_ap, 5.5):.3f}, {np.percentile(beta_ap, 94.5):.3f}]")
print(f"    Odds ratio: {np.exp(beta_ap.mean()):.2f}")
print(f"    P(Autopilot reduces risk): {(beta_ap < 0).mean():.1%}")

# Depth effect
beta_depth = trace3.posterior["beta_depth"].values.flatten()
print(f"\n  Depth (per 1 SD increase):")
print(f"    Effect: {beta_depth.mean():.3f} "
      f"[{np.percentile(beta_depth, 5.5):.3f}, {np.percentile(beta_depth, 94.5):.3f}]")
print(f"    Odds ratio: {np.exp(beta_depth.mean()):.2f}")

# ═══════════════════════════════════════════════════════════════
# VISUALIZATION: Coefficient forest plot (manual)
# ═══════════════════════════════════════════════════════════════
print("\n" + "="*60)
print("GENERATING PLOTS")
print("="*60)

# Forest plot for slopes
fig, ax = plt.subplots(1, 1, figsize=(10, 8))

all_vars = []
means = []
lbs = []
ubs = []
colors = []

# Slopes
for var in sorted(slope_vars):
    samples = trace3.posterior[var].values.flatten()
    m = samples.mean()
    lb, ub = np.percentile(samples, [5.5, 94.5])
    all_vars.append(var.replace("beta_", ""))
    means.append(m)
    lbs.append(lb)
    ubs.append(ub)
    colors.append("#e74c3c" if lb > 0 else ("#3498db" if ub < 0 else "#95a5a6"))

y_pos = range(len(all_vars))
ax.barh(y_pos, means, xerr=[np.array(means) - np.array(lbs), np.array(ubs) - np.array(means)],
        color=colors, alpha=0.7, edgecolor="white", capsize=3, height=0.6)
ax.axvline(0, color="black", linestyle="--", alpha=0.3)
ax.set_yticks(y_pos)
ax.set_yticklabels(all_vars)
ax.set_xlabel("Effect on log-odds of interaction (89% ETI)")
ax.set_title("Orca Interaction Risk Factors — M3 Posterior Coefficients\n"
             "Red = increases risk, Blue = decreases risk, Grey = uncertain")
ax.invert_yaxis()
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "coefficient_forest.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("  Saved: coefficient_forest.png")

# Antifoul comparison plot
fig, ax = plt.subplots(1, 1, figsize=(10, 5))
for i, cat in enumerate(antifoul_cats):
    vals = antifoul_samples[:, i]
    ax.violinplot(vals, positions=[i], showmedians=True, widths=0.7)
ax.set_xticks(range(len(antifoul_cats)))
ax.set_xticklabels(antifoul_cats, rotation=45, ha="right")
ax.axhline(0, color="black", linestyle="--", alpha=0.3)
ax.set_ylabel("Effect on log-odds of interaction")
ax.set_title("Antifoul Colour Effect on Orca Interaction Risk\n(Higher = more risk)")
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "antifoul_effects.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("  Saved: antifoul_effects.png")

# Sailing mode comparison
fig, ax = plt.subplots(1, 1, figsize=(10, 5))
for i, cat in enumerate(sailing_cats):
    vals = sailing_samples[:, i]
    ax.violinplot(vals, positions=[i], showmedians=True, widths=0.7)
ax.set_xticks(range(len(sailing_cats)))
ax.set_xticklabels(sailing_cats, rotation=45, ha="right")
ax.axhline(0, color="black", linestyle="--", alpha=0.3)
ax.set_ylabel("Effect on log-odds of interaction")
ax.set_title("Sailing Mode Effect on Orca Interaction Risk\n(Higher = more risk)")
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "sailing_mode_effects.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("  Saved: sailing_mode_effects.png")

print("\n" + "█"*60)
print("  ANALYSIS COMPLETE")
print("█"*60)
