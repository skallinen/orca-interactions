#!/usr/bin/env python3
"""
Exploratory Data Analysis: Compare predictor distributions between
incident and uneventful groups.

This is the "look at the data" step McElreath emphasizes before modeling.
Shows WHAT the logistic regression is actually detecting.
"""

import os, json
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns

DATA_DIR = os.path.join(os.path.dirname(__file__), "data")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "results")

# Load data
df = pd.read_csv(os.path.join(DATA_DIR, "modeling_data.csv"))
with open(os.path.join(DATA_DIR, "metadata.json")) as f:
    meta = json.load(f)

inc = df[df.interaction == 1]
une = df[df.interaction == 0]
print(f"Incidents: {len(inc)}, Uneventful: {len(une)}")

# ═══════════════════════════════════════════════════════════════
# 1. ORDINAL VARIABLES — side-by-side bar charts
# ═══════════════════════════════════════════════════════════════

ordinal_vars = [
    ("boat_length_ord", "Boat Length", {0: "<10m", 1: "10-12.5m", 2: "12.5-15m", 3: ">15m"}),
    ("depth_ord", "Depth", {0: "<20m", 1: "20-40m", 2: "40-200m", 3: ">200m"}),
    ("distance_ord", "Distance Off Land (nm)", {0: "0-2", 1: "2-5", 2: "5-10", 3: ">10"}),
    ("speed_ord", "Speed (kts)", {0: "0-2", 1: "3-4", 2: "5-7", 3: "8-11"}),
    ("wind_ord", "Wind (Beaufort)", {0: "F0-2", 1: "F3-4", 2: "F5-6", 3: "F7+"}),
    ("sea_state_ord", "Sea State", {0: "Calm", 1: "Moderate", 2: "Rough"}),
    ("cloud_cover_ord", "Cloud Cover", {0: "0-25%", 1: "25-50%", 2: "50-75%", 3: "75-100%"}),
]

fig, axes = plt.subplots(3, 3, figsize=(16, 14))
axes = axes.flatten()

for idx, (col, label, val_map) in enumerate(ordinal_vars):
    ax = axes[idx]

    # Get distributions as proportions (normalized within each group)
    inc_counts = inc[col].dropna().value_counts(normalize=True).sort_index()
    une_counts = une[col].dropna().value_counts(normalize=True).sort_index()

    # Align
    all_vals = sorted(set(inc_counts.index) | set(une_counts.index))
    inc_props = [inc_counts.get(v, 0) for v in all_vals]
    une_props = [une_counts.get(v, 0) for v in all_vals]
    labels = [val_map.get(v, str(v)) for v in all_vals]

    x = np.arange(len(all_vals))
    w = 0.35
    bars1 = ax.bar(x - w/2, une_props, w, label="Uneventful", color="#3498db", alpha=0.8)
    bars2 = ax.bar(x + w/2, inc_props, w, label="Incident", color="#e74c3c", alpha=0.8)
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=9)
    ax.set_ylabel("Proportion within group")
    ax.set_title(label, fontsize=12, fontweight="bold")
    ax.legend(fontsize=8)

    # Add interaction rate annotation
    for i, v in enumerate(all_vals):
        n_inc = (inc[col] == v).sum()
        n_une = (une[col] == v).sum()
        total = n_inc + n_une
        if total > 0:
            rate = n_inc / total
            ax.annotate(f"{rate:.0%}", xy=(x[i], max(inc_props[i], une_props[i])),
                       fontsize=7, ha="center", va="bottom", color="#666")

# Hide unused subplots
for idx in range(len(ordinal_vars), len(axes)):
    axes[idx].set_visible(False)

fig.suptitle("Predictor Distributions: Incident (red) vs Uneventful (blue)\n"
             "Percentages = interaction rate within category",
             fontsize=14, fontweight="bold", y=1.02)
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "distribution_ordinal.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved: distribution_ordinal.png")


# ═══════════════════════════════════════════════════════════════
# 2. CATEGORICAL VARIABLES — interaction rate bar charts
# ═══════════════════════════════════════════════════════════════

cat_vars = [
    ("antifoul_idx", "Antifoul Colour", meta["categories"]["antifoul"]),
    ("sailing_mode_idx", "Sailing Mode", meta["categories"]["sailing_mode"]),
    ("rudder_idx", "Rudder Type", meta["categories"]["rudder"]),
    ("hull_colour_idx", "Hull Colour", meta["categories"]["hull_colour"]),
]

fig, axes = plt.subplots(2, 2, figsize=(16, 10))
axes = axes.flatten()

for idx, (col, label, cats) in enumerate(cat_vars):
    ax = axes[idx]

    rates = []
    totals = []
    labels_cat = []
    for i, cat in enumerate(cats):
        if cat == "Unknown":
            continue
        n_inc = ((df[col] == i) & (df.interaction == 1)).sum()
        n_une = ((df[col] == i) & (df.interaction == 0)).sum()
        total = n_inc + n_une
        rate = n_inc / total if total > 0 else 0
        rates.append(rate)
        totals.append(total)
        labels_cat.append(cat)

    # Sort by interaction rate
    order = np.argsort(rates)[::-1]
    rates = [rates[i] for i in order]
    totals = [totals[i] for i in order]
    labels_cat = [labels_cat[i] for i in order]

    colors = ["#e74c3c" if r > 0.4 else ("#f39c12" if r > 0.3 else "#3498db") for r in rates]
    bars = ax.barh(range(len(rates)), rates, color=colors, alpha=0.8, edgecolor="white")
    ax.set_yticks(range(len(rates)))
    ax.set_yticklabels([f"{l} (n={t})" for l, t in zip(labels_cat, totals)], fontsize=9)
    ax.set_xlabel("Interaction Rate")
    ax.set_title(label, fontsize=12, fontweight="bold")
    ax.axvline(216/654, color="black", linestyle="--", alpha=0.3, label=f"Overall rate ({216/654:.0%})")
    ax.legend(fontsize=8)
    ax.invert_yaxis()

    # Annotate
    for i, (r, t) in enumerate(zip(rates, totals)):
        ax.text(r + 0.01, i, f"{r:.0%}", va="center", fontsize=9)

fig.suptitle("Interaction Rate by Category\n"
             "Red = above 40%, Orange = 30-40%, Blue = below 30%",
             fontsize=14, fontweight="bold")
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "distribution_categorical.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved: distribution_categorical.png")


# ═══════════════════════════════════════════════════════════════
# 3. BINARY VARIABLES — comparison
# ═══════════════════════════════════════════════════════════════

fig, axes = plt.subplots(1, 4, figsize=(16, 4))

binary_vars = [
    ("is_daytime", "Day vs Night"),
    ("autopilot_on", "Autopilot"),
    ("is_spring_tide", "Spring Tide"),
    ("is_towing", "Towing"),
]

for idx, (col, label) in enumerate(binary_vars):
    ax = axes[idx]

    for val, val_label in [(0, "No/Off/Night"), (1, "Yes/On/Day")]:
        n_inc = ((df[col] == val) & (df.interaction == 1)).sum()
        n_une = ((df[col] == val) & (df.interaction == 0)).sum()
        total = n_inc + n_une
        if total > 0:
            rate = n_inc / total
            ax.bar(val_label, rate, color="#e74c3c" if rate > 0.4 else "#3498db",
                   alpha=0.8, edgecolor="white")
            ax.text(val_label, rate + 0.01, f"{rate:.0%}\n(n={total})",
                    ha="center", fontsize=9)

    ax.set_title(label, fontsize=11, fontweight="bold")
    ax.set_ylabel("Interaction Rate")
    ax.set_ylim(0, 0.7)
    ax.axhline(216/654, color="black", linestyle="--", alpha=0.3)

fig.suptitle("Binary Predictor Interaction Rates", fontsize=13, fontweight="bold")
fig.tight_layout()
fig.savefig(os.path.join(OUTPUT_DIR, "distribution_binary.png"), dpi=150, bbox_inches="tight")
plt.close(fig)
print("Saved: distribution_binary.png")


# ═══════════════════════════════════════════════════════════════
# 4. SUMMARY TABLE — raw differences that the model captures
# ═══════════════════════════════════════════════════════════════

print("\n" + "="*80)
print("RAW DISTRIBUTION DIFFERENCES → MODEL EFFECTS")
print("="*80)
print(f"\n{'Variable':30s} {'Raw Difference':30s} {'Model Effect (89% ETI)':30s}")
print("-"*90)

comparisons = [
    ("depth_ord", "Mean depth",
     f"Inc={inc.depth_ord.mean():.2f}, Une={une.depth_ord.mean():.2f}",
     "beta_depth = +0.84 [+0.59, +1.10] ***"),
    ("is_daytime", "Daytime %",
     f"Inc={inc.is_daytime.mean():.0%}, Une={une.is_daytime.mean():.0%}",
     "beta_daylight = -1.29 [-1.70, -0.88] ***"),
    ("autopilot_on", "Autopilot ON %",
     f"Inc={inc.autopilot_on.mean():.0%}, Une={une.autopilot_on.mean():.0%}",
     "beta_autopilot = -0.63 [-1.00, -0.26] ***"),
    ("speed_ord", "Mean speed",
     f"Inc={inc.speed_ord.mean():.2f}, Une={une.speed_ord.mean():.2f}",
     "beta_speed = -0.06 [-0.22, +0.11] (no effect)"),
    ("wind_ord", "Mean wind",
     f"Inc={inc.wind_ord.mean():.2f}, Une={une.wind_ord.mean():.2f}",
     "beta_wind = -0.05 [-0.24, +0.14] (no effect)"),
    ("boat_length_ord", "Mean length",
     f"Inc={inc.boat_length_ord.mean():.2f}, Une={une.boat_length_ord.mean():.2f}",
     "beta_length = +0.04 [-0.13, +0.21] (no effect)"),
    ("sea_state_ord", "Mean sea state",
     f"Inc={inc.sea_state_ord.mean():.2f}, Une={une.sea_state_ord.mean():.2f}",
     "beta_sea = -0.16 [-0.35, +0.03] (marginal)"),
    ("distance_ord", "Mean distance",
     f"Inc={inc.distance_ord.mean():.2f}, Une={une.distance_ord.mean():.2f}",
     "beta_distance = +0.21 [-0.02, +0.44] (marginal)"),
]

for col, name, raw, model in comparisons:
    print(f"  {name:28s} {raw:30s} {model}")

# Categorical
print("\n  Categorical:")
# Antifoul
inc_antifoul = inc.antifoul_idx.value_counts(normalize=True).sort_index()
une_antifoul = une.antifoul_idx.value_counts(normalize=True).sort_index()
cats = meta["categories"]["antifoul"]
for i, cat in enumerate(cats):
    if cat in ("Unknown",):
        continue
    ip = inc_antifoul.get(i, 0)
    up = une_antifoul.get(i, 0)
    diff = ip - up
    print(f"    Antifoul {cat:15s}: Inc={ip:.1%}, Une={up:.1%}, Δ={diff:+.1%}")

# Sailing mode
print()
inc_sail = inc.sailing_mode_idx.value_counts(normalize=True).sort_index()
une_sail = une.sailing_mode_idx.value_counts(normalize=True).sort_index()
cats = meta["categories"]["sailing_mode"]
for i, cat in enumerate(cats):
    if cat in ("Unknown", "Hove-to"):
        continue
    ip = inc_sail.get(i, 0)
    up = une_sail.get(i, 0)
    diff = ip - up
    print(f"    Sailing {cat:15s}: Inc={ip:.1%}, Une={up:.1%}, Δ={diff:+.1%}")

print("\n" + "="*80)
print("HOW THE MODEL USES THESE DIFFERENCES")
print("="*80)
print("""
The logistic regression works by finding the WEIGHTED COMBINATION of all these
distributional differences that best predicts the outcome (interaction vs not).

Key insight: Some raw differences are CONFOUNDED. For example:
  - Motoring boats might also be in shallower water
  - Night passages might correlate with different sailing modes
  - Boats with black antifoul might be in different areas

The model's job is to DISENTANGLE these effects:
  - beta_depth = +0.84 means depth still matters AFTER controlling for
    sailing mode, antifoul colour, daylight, etc.
  - beta_speed ≈ 0 means the raw speed difference DISAPPEARS once you
    control for other variables — it was confounded.

This is exactly what McElreath describes in Ch. 5-6: multiple regression
separates the unique contribution of each variable from the shared variation.
""")
