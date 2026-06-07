#!/usr/bin/env python3
"""
Prior Sensitivity Analysis: How much do our conclusions depend on
the Fermi-estimated base rate prior?

Fits M3 with three intercept priors:
  Conservative: Normal(-4.5, 0.6)  →  ~1% base rate
  Central:      Normal(-3.5, 0.6)  →  ~3% base rate (our default)
  Aggressive:   Normal(-2.9, 0.6)  →  ~5% base rate
  Flat:         Normal(0, 1.5)     →  uninformative (let data speak)
"""

import os, json, warnings
warnings.filterwarnings("ignore")

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pymc as pm
import arviz as az

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "results")
RANDOM_SEED = 42

# Load
df = pd.read_csv(os.path.join(DATA_DIR, "modeling_data.csv"))
with open(os.path.join(DATA_DIR, "metadata.json")) as f:
    meta = json.load(f)

# Complete cases for M3
required = ["interaction", "boat_length_ord_std", "rudder_idx",
            "antifoul_idx", "hull_colour_idx",
            "sailing_mode_idx", "speed_ord_std", "autopilot_on",
            "depth_ord_std", "distance_ord_std",
            "wind_ord_std", "sea_state_ord_std", "is_daytime"]
mask = df[required].notna().all(axis=1)
df_c = df[mask].reset_index(drop=True)
print(f"Complete cases: {len(df_c)}")

n_rudder = len(meta["categories"]["rudder"])
n_antifoul = len(meta["categories"]["antifoul"])
n_hull = len(meta["categories"]["hull_colour"])
n_sailing = len(meta["categories"]["sailing_mode"])

# Prior scenarios
priors = {
    "Conservative\n(~1% base rate)": {"mu": -4.5, "sd": 0.6},
    "Central\n(~3% base rate)": {"mu": -3.5, "sd": 0.6},
    "Aggressive\n(~5% base rate)": {"mu": -2.9, "sd": 0.6},
    "Flat\n(uninformative)": {"mu": 0.0, "sd": 1.5},
}

results = {}

for name, prior_spec in priors.items():
    short = name.split("\n")[0]
    print(f"\n{'='*60}")
    print(f"FITTING: {short} — intercept prior Normal({prior_spec['mu']}, {prior_spec['sd']})")
    print(f"{'='*60}")

    with pm.Model() as model:
        alpha_rudder = pm.Normal("alpha_rudder", mu=0, sigma=0.5, shape=n_rudder)
        alpha_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=0.5, shape=n_antifoul)
        alpha_hull = pm.Normal("alpha_hull", mu=0, sigma=0.5, shape=n_hull)
        alpha_sailing = pm.Normal("alpha_sailing", mu=0, sigma=0.5, shape=n_sailing)

        alpha = pm.Normal("alpha", mu=prior_spec["mu"], sigma=prior_spec["sd"])

        beta_length = pm.Normal("beta_length", mu=0, sigma=0.5)
        beta_speed = pm.Normal("beta_speed", mu=0, sigma=0.5)
        beta_autopilot = pm.Normal("beta_autopilot", mu=0, sigma=0.5)
        beta_depth = pm.Normal("beta_depth", mu=0, sigma=0.5)
        beta_distance = pm.Normal("beta_distance", mu=0, sigma=0.5)
        beta_wind = pm.Normal("beta_wind", mu=0, sigma=0.5)
        beta_sea = pm.Normal("beta_sea", mu=0, sigma=0.5)
        beta_daylight = pm.Normal("beta_daylight", mu=0, sigma=0.5)

        logit_p = (alpha
                   + beta_length * df_c.boat_length_ord_std.values
                   + beta_speed * df_c.speed_ord_std.values
                   + beta_autopilot * df_c.autopilot_on.values
                   + beta_depth * df_c.depth_ord_std.values
                   + beta_distance * df_c.distance_ord_std.values
                   + beta_wind * df_c.wind_ord_std.values
                   + beta_sea * df_c.sea_state_ord_std.values
                   + beta_daylight * df_c.is_daytime.values
                   + alpha_rudder[df_c.rudder_idx.values.astype(int)]
                   + alpha_antifoul[df_c.antifoul_idx.values.astype(int)]
                   + alpha_hull[df_c.hull_colour_idx.values.astype(int)]
                   + alpha_sailing[df_c.sailing_mode_idx.values.astype(int)])

        y = pm.Bernoulli("y", logit_p=logit_p, observed=df_c.interaction.values)

        trace = pm.sample(draws=2000, tune=1000, chains=4,
                          random_seed=RANDOM_SEED, target_accept=0.95)

    results[name] = trace
    print(f"  Done. Divergences: {trace.sample_stats['diverging'].sum().item()}")


# ═══════════════════════════════════════════════════════════════
# COMPARISON: Intercept (absolute risk)
# ═══════════════════════════════════════════════════════════════
print("\n" + "█"*60)
print("  INTERCEPT COMPARISON (absolute base rate)")
print("█"*60)

print(f"\n  {'Prior':30s} {'α posterior':15s} {'Implied P':15s}")
print("  " + "-"*60)
for name, trace in results.items():
    short = name.split("\n")[0]
    alpha = trace.posterior["alpha"].values.flatten()
    p = 1 / (1 + np.exp(-alpha))
    mu_spec = priors[name]["mu"]
    print(f"  Prior α ~ N({mu_spec:+.1f}, 0.6)")
    print(f"    {'Posterior α':28s}: {alpha.mean():.3f} [{np.percentile(alpha, 5.5):.3f}, {np.percentile(alpha, 94.5):.3f}]")
    print(f"    {'Implied base P':28s}: {p.mean():.1%} [{np.percentile(p, 5.5):.1%}, {np.percentile(p, 94.5):.1%}]")
    print()

# ═══════════════════════════════════════════════════════════════
# COMPARISON: Slope coefficients (relative effects)
# ═══════════════════════════════════════════════════════════════
print("█"*60)
print("  SLOPE COMPARISON (relative effects — the ones that matter)")
print("█"*60)

key_betas = ["beta_depth", "beta_daylight", "beta_autopilot",
             "beta_speed", "beta_length", "beta_distance",
             "beta_wind", "beta_sea"]

for beta_name in key_betas:
    print(f"\n  {beta_name}:")
    for name, trace in results.items():
        short = name.split("\n")[0]
        vals = trace.posterior[beta_name].values.flatten()
        m = vals.mean()
        lb, ub = np.percentile(vals, [5.5, 94.5])
        credible = "***" if (lb > 0 or ub < 0) else ""
        print(f"    {short:15s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}] OR={np.exp(m):.2f} {credible}")

# Index variables
print(f"\n  Motoring effect (alpha_sailing[1]):")
for name, trace in results.items():
    short = name.split("\n")[0]
    cats = meta["categories"]["sailing_mode"]
    vals = trace.posterior["alpha_sailing"].values.reshape(-1, len(cats))[:, 1]
    m = vals.mean()
    lb, ub = np.percentile(vals, [5.5, 94.5])
    credible = "***" if (lb > 0 or ub < 0) else ""
    print(f"    {short:15s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}] OR={np.exp(m):.2f} {credible}")

print(f"\n  Black antifoul effect (alpha_antifoul[0]):")
for name, trace in results.items():
    short = name.split("\n")[0]
    cats = meta["categories"]["antifoul"]
    vals = trace.posterior["alpha_antifoul"].values.reshape(-1, len(cats))[:, 0]
    m = vals.mean()
    lb, ub = np.percentile(vals, [5.5, 94.5])
    credible = "***" if (lb > 0 or ub < 0) else ""
    print(f"    {short:15s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}] OR={np.exp(m):.2f} {credible}")

print(f"\n  Black vs Coppercoat contrast:")
for name, trace in results.items():
    short = name.split("\n")[0]
    cats = meta["categories"]["antifoul"]
    samples = trace.posterior["alpha_antifoul"].values.reshape(-1, len(cats))
    contrast = samples[:, 0] - samples[:, 2]
    m = contrast.mean()
    lb, ub = np.percentile(contrast, [5.5, 94.5])
    print(f"    {short:15s}: {m:+.3f} [{lb:+.3f}, {ub:+.3f}] OR={np.exp(m):.2f}  P(Black>Copper)={((contrast)>0).mean():.1%}")


# ═══════════════════════════════════════════════════════════════
# VISUALIZATION
# ═══════════════════════════════════════════════════════════════

# 1. Intercept posterior comparison
fig, axes = plt.subplots(1, 2, figsize=(14, 5))

colors = ["#2ecc71", "#3498db", "#e74c3c", "#95a5a6"]
prior_names_short = [n.split("\n")[0] for n in results.keys()]

# Left: intercept on logit scale
ax = axes[0]
for i, (name, trace) in enumerate(results.items()):
    alpha = trace.posterior["alpha"].values.flatten()
    ax.hist(alpha, bins=60, density=True, alpha=0.5, color=colors[i],
            label=prior_names_short[i], edgecolor="none")
ax.set_xlabel("Intercept (logit scale)")
ax.set_ylabel("Density")
ax.set_title("Posterior Intercept by Prior Choice")
ax.legend()

# Right: implied probability
ax = axes[1]
for i, (name, trace) in enumerate(results.items()):
    alpha = trace.posterior["alpha"].values.flatten()
    p = 1 / (1 + np.exp(-alpha))
    ax.hist(p, bins=60, density=True, alpha=0.5, color=colors[i],
            label=prior_names_short[i], edgecolor="none")
ax.set_xlabel("Implied base P(interaction)")
ax.set_ylabel("Density")
ax.set_title("Posterior Base Rate by Prior Choice")
ax.legend()

fig.suptitle("Prior Sensitivity: Intercept\n"
             "The intercept (absolute risk) is sensitive to the prior — but relative effects are not",
             fontsize=12, fontweight="bold")
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "sensitivity_intercept.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("\nSaved: sensitivity_intercept.png")

# 2. Slope comparison — the key plot
fig, ax = plt.subplots(1, 1, figsize=(14, 8))

all_params = key_betas + ["Motoring", "Black antifoul"]
y_positions = np.arange(len(all_params))

for i, (name, trace) in enumerate(results.items()):
    short = prior_names_short[i]
    offset = (i - 1.5) * 0.15  # stagger vertically

    for j, param in enumerate(all_params):
        if param == "Motoring":
            vals = trace.posterior["alpha_sailing"].values.reshape(-1, len(meta["categories"]["sailing_mode"]))[:, 1]
        elif param == "Black antifoul":
            vals = trace.posterior["alpha_antifoul"].values.reshape(-1, len(meta["categories"]["antifoul"]))[:, 0]
        else:
            vals = trace.posterior[param].values.flatten()

        m = vals.mean()
        lb, ub = np.percentile(vals, [5.5, 94.5])
        ax.plot([lb, ub], [j + offset, j + offset], color=colors[i],
                linewidth=2, alpha=0.7)
        ax.plot(m, j + offset, 'o', color=colors[i], markersize=6,
                label=short if j == 0 else "")

ax.axvline(0, color="black", linestyle="--", alpha=0.3)
ax.set_yticks(y_positions)
ax.set_yticklabels([p.replace("beta_", "") for p in all_params])
ax.set_xlabel("Effect on log-odds of interaction (89% ETI)")
ax.set_title("Prior Sensitivity: Do Slope Estimates Change?\n"
             "If lines overlap → robust to prior choice",
             fontsize=13, fontweight="bold")
ax.legend(loc="lower right", fontsize=10)
ax.invert_yaxis()
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "sensitivity_slopes.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved: sensitivity_slopes.png")

print("\n" + "█"*60)
print("  SENSITIVITY ANALYSIS COMPLETE")
print("█"*60)
