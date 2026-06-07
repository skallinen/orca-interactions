#!/usr/bin/env python3
"""
Steps 3–9: Full Bayesian Analysis Pipeline for Orca Interaction Risk
Following Statistical Rethinking by Richard McElreath.

Models 0–4 with progressive complexity:
  M0: Intercept-only (base rate)
  M1: Vessel characteristics
  M2: Vessel + Activity
  M3: Vessel + Activity + Environment (primary)
  M4: Full model with season + additional

Includes:
  - Prior predictive simulation
  - Parameter recovery validation
  - Model fitting with NUTS
  - Posterior predictive checks
  - Model comparison (PSIS-LOO)
  - Results visualization
"""

import os
import json
import warnings
warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", category=UserWarning)

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns

import pymc as pm
import arviz as az

# Configuration
RANDOM_SEED = 42
N_CHAINS = 4
N_DRAWS = 2000
N_TUNE = 1000
TARGET_ACCEPT = 0.95
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "results")
DATA_DIR = os.path.join(os.path.dirname(__file__), "data")

# Base rate prior from Fermi estimation research:
# ~2-3% interaction rate → logit(0.025) ≈ -3.66
# We use Normal(-3.5, 0.6) as the informative intercept prior
# This spans ~1% to ~7% at ±2 SD on probability scale
INTERCEPT_PRIOR_MU = -3.5
INTERCEPT_PRIOR_SD = 0.6

# Slope priors (McElreath Ch. 11): weakly regularizing
SLOPE_PRIOR_SD = 0.5
INDEX_PRIOR_SD = 0.5


def load_data():
    """Load prepared modeling data."""
    df = pd.read_csv(os.path.join(DATA_DIR, "modeling_data.csv"))
    with open(os.path.join(DATA_DIR, "metadata.json")) as f:
        meta = json.load(f)
    print(f"Loaded {len(df)} observations: "
          f"{(df.interaction==1).sum()} incidents, {(df.interaction==0).sum()} uneventful")
    return df, meta


def get_complete_cases(df, required_cols):
    """Return rows with no missing values in required columns."""
    mask = df[required_cols].notna().all(axis=1)
    n_complete = mask.sum()
    n_dropped = len(df) - n_complete
    if n_dropped > 0:
        print(f"  Complete cases: {n_complete}/{len(df)} ({n_dropped} dropped for missing data)")
    return df[mask].reset_index(drop=True)


# ═══════════════════════════════════════════════════════════════
# MODEL DEFINITIONS
# ═══════════════════════════════════════════════════════════════

def build_model_0(df):
    """M0: Intercept-only — estimates the dataset base rate.
    Uses the informative Fermi-estimated prior on the intercept.
    """
    with pm.Model() as model:
        # Informative prior: true base rate ~2-3%
        alpha = pm.Normal("alpha", mu=INTERCEPT_PRIOR_MU, sigma=INTERCEPT_PRIOR_SD)
        p = pm.Deterministic("p", pm.math.sigmoid(alpha))
        y = pm.Bernoulli("y", p=p, observed=df.interaction.values)
    return model


def build_model_1(df, meta):
    """M1: Vessel characteristics only.
    Predictors: boat_length, rudder type, antifoul colour, hull colour.
    These are all pre-treatment (fixed before passage).
    """
    required = ["interaction", "boat_length_ord_std", "rudder_idx",
                 "antifoul_idx", "hull_colour_idx"]
    df_c = get_complete_cases(df, required)

    n_rudder = len(meta["categories"]["rudder"])
    n_antifoul = len(meta["categories"]["antifoul"])
    n_hull = len(meta["categories"]["hull_colour"])

    with pm.Model() as model:
        # Store data for posterior predictions
        pm.Data("y_obs", df_c.interaction.values)

        # Priors — index variables (McElreath Ch. 5)
        alpha_rudder = pm.Normal("alpha_rudder", mu=0, sigma=INDEX_PRIOR_SD, shape=n_rudder)
        alpha_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=INDEX_PRIOR_SD, shape=n_antifoul)
        alpha_hull = pm.Normal("alpha_hull", mu=0, sigma=INDEX_PRIOR_SD, shape=n_hull)

        # Intercept (base rate)
        alpha = pm.Normal("alpha", mu=INTERCEPT_PRIOR_MU, sigma=INTERCEPT_PRIOR_SD)

        # Slopes for ordinal predictors
        beta_length = pm.Normal("beta_length", mu=0, sigma=SLOPE_PRIOR_SD)

        # Linear model
        logit_p = (alpha
                   + beta_length * df_c.boat_length_ord_std.values
                   + alpha_rudder[df_c.rudder_idx.values.astype(int)]
                   + alpha_antifoul[df_c.antifoul_idx.values.astype(int)]
                   + alpha_hull[df_c.hull_colour_idx.values.astype(int)])

        p = pm.Deterministic("p", pm.math.sigmoid(logit_p))
        y = pm.Bernoulli("y", p=p, observed=df_c.interaction.values)

    return model, df_c


def build_model_2(df, meta):
    """M2: Vessel + Activity.
    Adds: sailing_mode, speed, autopilot.
    """
    required = ["interaction", "boat_length_ord_std", "rudder_idx",
                 "antifoul_idx", "hull_colour_idx",
                 "sailing_mode_idx", "speed_ord_std", "autopilot_on"]
    df_c = get_complete_cases(df, required)

    n_rudder = len(meta["categories"]["rudder"])
    n_antifoul = len(meta["categories"]["antifoul"])
    n_hull = len(meta["categories"]["hull_colour"])
    n_sailing = len(meta["categories"]["sailing_mode"])

    with pm.Model() as model:
        # Index variable priors
        alpha_rudder = pm.Normal("alpha_rudder", mu=0, sigma=INDEX_PRIOR_SD, shape=n_rudder)
        alpha_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=INDEX_PRIOR_SD, shape=n_antifoul)
        alpha_hull = pm.Normal("alpha_hull", mu=0, sigma=INDEX_PRIOR_SD, shape=n_hull)
        alpha_sailing = pm.Normal("alpha_sailing", mu=0, sigma=INDEX_PRIOR_SD, shape=n_sailing)

        alpha = pm.Normal("alpha", mu=INTERCEPT_PRIOR_MU, sigma=INTERCEPT_PRIOR_SD)

        beta_length = pm.Normal("beta_length", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_speed = pm.Normal("beta_speed", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_autopilot = pm.Normal("beta_autopilot", mu=0, sigma=SLOPE_PRIOR_SD)

        logit_p = (alpha
                   + beta_length * df_c.boat_length_ord_std.values
                   + beta_speed * df_c.speed_ord_std.values
                   + beta_autopilot * df_c.autopilot_on.values
                   + alpha_rudder[df_c.rudder_idx.values.astype(int)]
                   + alpha_antifoul[df_c.antifoul_idx.values.astype(int)]
                   + alpha_hull[df_c.hull_colour_idx.values.astype(int)]
                   + alpha_sailing[df_c.sailing_mode_idx.values.astype(int)])

        p = pm.Deterministic("p", pm.math.sigmoid(logit_p))
        y = pm.Bernoulli("y", p=p, observed=df_c.interaction.values)

    return model, df_c


def build_model_3(df, meta):
    """M3: Vessel + Activity + Environment (PRIMARY MODEL).
    Adds: depth, distance, wind, sea_state, daylight.
    """
    required = ["interaction", "boat_length_ord_std", "rudder_idx",
                 "antifoul_idx", "hull_colour_idx",
                 "sailing_mode_idx", "speed_ord_std", "autopilot_on",
                 "depth_ord_std", "distance_ord_std",
                 "wind_ord_std", "sea_state_ord_std", "is_daytime"]
    df_c = get_complete_cases(df, required)

    n_rudder = len(meta["categories"]["rudder"])
    n_antifoul = len(meta["categories"]["antifoul"])
    n_hull = len(meta["categories"]["hull_colour"])
    n_sailing = len(meta["categories"]["sailing_mode"])

    with pm.Model() as model:
        # Index variable priors
        alpha_rudder = pm.Normal("alpha_rudder", mu=0, sigma=INDEX_PRIOR_SD, shape=n_rudder)
        alpha_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=INDEX_PRIOR_SD, shape=n_antifoul)
        alpha_hull = pm.Normal("alpha_hull", mu=0, sigma=INDEX_PRIOR_SD, shape=n_hull)
        alpha_sailing = pm.Normal("alpha_sailing", mu=0, sigma=INDEX_PRIOR_SD, shape=n_sailing)

        alpha = pm.Normal("alpha", mu=INTERCEPT_PRIOR_MU, sigma=INTERCEPT_PRIOR_SD)

        # Ordinal slopes
        beta_length = pm.Normal("beta_length", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_speed = pm.Normal("beta_speed", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_autopilot = pm.Normal("beta_autopilot", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_depth = pm.Normal("beta_depth", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_distance = pm.Normal("beta_distance", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_wind = pm.Normal("beta_wind", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_sea = pm.Normal("beta_sea", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_daylight = pm.Normal("beta_daylight", mu=0, sigma=SLOPE_PRIOR_SD)

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

        p = pm.Deterministic("p", pm.math.sigmoid(logit_p))
        y = pm.Bernoulli("y", p=p, observed=df_c.interaction.values)

    return model, df_c


def build_model_4(df, meta):
    """M4: Full model — adds month (seasonal), moon, tide, towing."""
    required = ["interaction", "boat_length_ord_std", "rudder_idx",
                 "antifoul_idx", "hull_colour_idx",
                 "sailing_mode_idx", "speed_ord_std", "autopilot_on",
                 "depth_ord_std", "distance_ord_std",
                 "wind_ord_std", "sea_state_ord_std", "is_daytime",
                 "moon_illumination_std", "is_spring_tide", "is_towing", "month"]
    df_c = get_complete_cases(df, required)

    n_rudder = len(meta["categories"]["rudder"])
    n_antifoul = len(meta["categories"]["antifoul"])
    n_hull = len(meta["categories"]["hull_colour"])
    n_sailing = len(meta["categories"]["sailing_mode"])

    with pm.Model() as model:
        # Index variable priors
        alpha_rudder = pm.Normal("alpha_rudder", mu=0, sigma=INDEX_PRIOR_SD, shape=n_rudder)
        alpha_antifoul = pm.Normal("alpha_antifoul", mu=0, sigma=INDEX_PRIOR_SD, shape=n_antifoul)
        alpha_hull = pm.Normal("alpha_hull", mu=0, sigma=INDEX_PRIOR_SD, shape=n_hull)
        alpha_sailing = pm.Normal("alpha_sailing", mu=0, sigma=INDEX_PRIOR_SD, shape=n_sailing)

        alpha = pm.Normal("alpha", mu=INTERCEPT_PRIOR_MU, sigma=INTERCEPT_PRIOR_SD)

        # Ordinal slopes
        beta_length = pm.Normal("beta_length", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_speed = pm.Normal("beta_speed", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_autopilot = pm.Normal("beta_autopilot", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_depth = pm.Normal("beta_depth", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_distance = pm.Normal("beta_distance", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_wind = pm.Normal("beta_wind", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_sea = pm.Normal("beta_sea", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_daylight = pm.Normal("beta_daylight", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_moon = pm.Normal("beta_moon", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_tide = pm.Normal("beta_tide", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_towing = pm.Normal("beta_towing", mu=0, sigma=SLOPE_PRIOR_SD)

        # Seasonal effect: use sin/cos for cyclical month encoding
        month_vals = df_c.month.values.astype(float)
        month_sin = np.sin(2 * np.pi * month_vals / 12)
        month_cos = np.cos(2 * np.pi * month_vals / 12)
        beta_month_sin = pm.Normal("beta_month_sin", mu=0, sigma=SLOPE_PRIOR_SD)
        beta_month_cos = pm.Normal("beta_month_cos", mu=0, sigma=SLOPE_PRIOR_SD)

        logit_p = (alpha
                   + beta_length * df_c.boat_length_ord_std.values
                   + beta_speed * df_c.speed_ord_std.values
                   + beta_autopilot * df_c.autopilot_on.values
                   + beta_depth * df_c.depth_ord_std.values
                   + beta_distance * df_c.distance_ord_std.values
                   + beta_wind * df_c.wind_ord_std.values
                   + beta_sea * df_c.sea_state_ord_std.values
                   + beta_daylight * df_c.is_daytime.values
                   + beta_moon * df_c.moon_illumination_std.values
                   + beta_tide * df_c.is_spring_tide.values
                   + beta_towing * df_c.is_towing.values
                   + beta_month_sin * month_sin
                   + beta_month_cos * month_cos
                   + alpha_rudder[df_c.rudder_idx.values.astype(int)]
                   + alpha_antifoul[df_c.antifoul_idx.values.astype(int)]
                   + alpha_hull[df_c.hull_colour_idx.values.astype(int)]
                   + alpha_sailing[df_c.sailing_mode_idx.values.astype(int)])

        p = pm.Deterministic("p", pm.math.sigmoid(logit_p))
        y = pm.Bernoulli("y", p=p, observed=df_c.interaction.values)

    return model, df_c


# ═══════════════════════════════════════════════════════════════
# PRIOR PREDICTIVE SIMULATION (McElreath Ch. 4, 11)
# ═══════════════════════════════════════════════════════════════

def prior_predictive_check(model, model_name):
    """Simulate from priors to verify they produce sensible probabilities."""
    print(f"\n{'='*60}")
    print(f"PRIOR PREDICTIVE CHECK: {model_name}")
    print(f"{'='*60}")

    with model:
        prior = pm.sample_prior_predictive(draws=1000, random_seed=RANDOM_SEED)

    # Extract prior probabilities — handle DataTree (PyMC v6)
    try:
        prior_data = prior.prior if hasattr(prior, 'prior') else prior["prior"]
        if hasattr(prior_data, 'data_vars'):
            prior_ds = prior_data
        else:
            prior_ds = prior_data.to_dataset() if hasattr(prior_data, 'to_dataset') else prior_data
    except Exception:
        prior_ds = prior

    if "p" in prior_ds:
        p_samples = prior_ds["p"].values.flatten()
    else:
        # For M0
        alpha_samples = prior_ds["alpha"].values.flatten()
        p_samples = 1 / (1 + np.exp(-alpha_samples))

    print(f"  Prior predicted probability range:")
    print(f"    Mean: {np.mean(p_samples):.3f}")
    print(f"    Median: {np.median(p_samples):.3f}")
    print(f"    5th percentile: {np.percentile(p_samples, 5):.3f}")
    print(f"    95th percentile: {np.percentile(p_samples, 95):.3f}")

    # Plot
    fig, ax = plt.subplots(1, 1, figsize=(8, 4))
    ax.hist(p_samples, bins=50, density=True, alpha=0.7, color="#4C72B0", edgecolor="white")
    ax.axvline(0.025, color="red", linestyle="--", label="Fermi estimate (~2.5%)")
    ax.set_xlabel("Prior predicted P(interaction)")
    ax.set_ylabel("Density")
    ax.set_title(f"Prior Predictive Distribution — {model_name}")
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(OUTPUT_DIR, f"prior_predictive_{model_name}.png"), dpi=150)
    plt.close(fig)
    print(f"  Saved plot: prior_predictive_{model_name}.png")


# ═══════════════════════════════════════════════════════════════
# PARAMETER RECOVERY (McElreath Ch. 4)
# ═══════════════════════════════════════════════════════════════

def parameter_recovery_test():
    """Generate fake data with known parameters, verify the model recovers them."""
    print(f"\n{'='*60}")
    print(f"PARAMETER RECOVERY VALIDATION")
    print(f"{'='*60}")

    np.random.seed(RANDOM_SEED)
    N = 654

    # Known true parameters
    true_alpha = -3.5
    true_beta_speed = 0.3
    true_beta_depth = -0.2

    # Simulate predictors
    speed = np.random.normal(0, 1, N)
    depth = np.random.normal(0, 1, N)

    # Simulate outcome
    logit_p = true_alpha + true_beta_speed * speed + true_beta_depth * depth
    p = 1 / (1 + np.exp(-logit_p))
    y = np.random.binomial(1, p, N)

    print(f"  Simulated data: {N} observations, {y.sum()} interactions ({y.mean()*100:.1f}%)")
    print(f"  True parameters: alpha={true_alpha}, beta_speed={true_beta_speed}, beta_depth={true_beta_depth}")

    # Fit model
    with pm.Model() as recovery_model:
        alpha = pm.Normal("alpha", mu=-3.5, sigma=0.6)
        beta_speed_r = pm.Normal("beta_speed", mu=0, sigma=0.5)
        beta_depth_r = pm.Normal("beta_depth", mu=0, sigma=0.5)

        logit_p_r = alpha + beta_speed_r * speed + beta_depth_r * depth
        y_obs = pm.Bernoulli("y", logit_p=logit_p_r, observed=y)

        trace = pm.sample(draws=1000, tune=500, chains=2, random_seed=RANDOM_SEED,
                          progressbar=True, target_accept=0.9)

    summary = az.summary(trace, var_names=["alpha", "beta_speed", "beta_depth"],
                         ci_prob=0.89)
    print(f"\n  Recovery results (89% HDI):")
    print(summary.to_string())

    # Check if true values are within HDI
    for param, true_val in [("alpha", true_alpha),
                            ("beta_speed", true_beta_speed),
                            ("beta_depth", true_beta_depth)]:
        row = summary.loc[param]
        in_hdi = float(row["eti89_lb"]) <= true_val <= float(row["eti89_ub"])
        status = "✅" if in_hdi else "❌"
        print(f"  {status} {param}: true={true_val:.2f}, "
              f"posterior mean={float(row['mean']):.2f}, "
              f"89% HDI=[{float(row['eti89_lb']):.2f}, {float(row['eti89_ub']):.2f}]")

    return trace


# ═══════════════════════════════════════════════════════════════
# MODEL FITTING
# ═══════════════════════════════════════════════════════════════

def fit_model(model, model_name):
    """Fit a model using NUTS and return the trace."""
    print(f"\n{'='*60}")
    print(f"FITTING {model_name}")
    print(f"{'='*60}")

    with model:
        trace = pm.sample(
            draws=N_DRAWS, tune=N_TUNE, chains=N_CHAINS,
            random_seed=RANDOM_SEED, target_accept=TARGET_ACCEPT,
            progressbar=True,
        )

    # Diagnostics
    print(f"\n  Convergence diagnostics:")
    summary = az.summary(trace, var_names=[v.name for v in model.free_RVs],
                         ci_prob=0.89)
    print(summary.to_string())

    # Cast summary columns to numeric for comparisons
    for col in ["r_hat", "ess_bulk", "ess_tail", "mean", "sd"]:
        if col in summary.columns:
            summary[col] = pd.to_numeric(summary[col], errors="coerce")

    # Check R-hat
    rhat_ok = (summary["r_hat"] < 1.01).all()
    print(f"\n  R-hat < 1.01 for all parameters: {'✅' if rhat_ok else '❌'}")

    # Check effective sample size
    min_ess = min(summary["ess_bulk"].min(), summary["ess_tail"].min())
    print(f"  Min effective sample size: {min_ess:.0f} {'✅' if min_ess > 400 else '❌'}")

    # Check divergences
    try:
        n_div = trace.sample_stats["diverging"].sum().item()
    except Exception:
        n_div = 0
    print(f"  Divergent transitions: {n_div} {'✅' if n_div == 0 else '⚠️'}")

    # Save trace plot
    var_names = [v.name for v in model.free_RVs if v.name != "p"]
    try:
        fig = plt.figure(figsize=(12, 2 * len(var_names)))
        az.plot_trace(trace, var_names=var_names)
        plt.tight_layout()
        plt.savefig(os.path.join(OUTPUT_DIR, f"trace_{model_name}.png"), dpi=100)
        plt.close()
        print(f"  Saved trace plot: trace_{model_name}.png")
    except Exception as e:
        print(f"  Trace plot skipped (ArviZ API issue): {e}")
        plt.close("all")

    return trace


# ═══════════════════════════════════════════════════════════════
# POSTERIOR PREDICTIVE CHECKS
# ═══════════════════════════════════════════════════════════════

def posterior_predictive_check(model, trace, df_c, model_name):
    """Compare model predictions against observed data."""
    print(f"\n{'='*60}")
    print(f"POSTERIOR PREDICTIVE CHECK: {model_name}")
    print(f"{'='*60}")

    with model:
        ppc = pm.sample_posterior_predictive(trace, random_seed=RANDOM_SEED)

    # Overall predicted vs observed interaction rate
    try:
        ppc_data = ppc.posterior_predictive if hasattr(ppc, 'posterior_predictive') else ppc["posterior_predictive"]
        y_pred = ppc_data["y"].values
    except Exception:
        y_pred = ppc["y"].values
    pred_rates = y_pred.mean(axis=-1).flatten()
    obs_rate = df_c.interaction.mean()

    print(f"  Observed interaction rate: {obs_rate:.3f}")
    print(f"  Predicted rate (posterior mean): {pred_rates.mean():.3f}")
    print(f"  Predicted rate 89% HDI: [{np.percentile(pred_rates, 5.5):.3f}, "
          f"{np.percentile(pred_rates, 94.5):.3f}]")

    # Plot
    fig, ax = plt.subplots(1, 1, figsize=(8, 4))
    ax.hist(pred_rates, bins=50, density=True, alpha=0.7, color="#4C72B0",
            edgecolor="white", label="Posterior predictive")
    ax.axvline(obs_rate, color="red", linewidth=2, label=f"Observed ({obs_rate:.3f})")
    ax.set_xlabel("Predicted interaction rate")
    ax.set_ylabel("Density")
    ax.set_title(f"Posterior Predictive Check — {model_name}")
    ax.legend()
    fig.tight_layout()
    fig.savefig(os.path.join(OUTPUT_DIR, f"ppc_{model_name}.png"), dpi=150)
    plt.close(fig)

    return ppc


# ═══════════════════════════════════════════════════════════════
# MODEL COMPARISON (McElreath Ch. 7)
# ═══════════════════════════════════════════════════════════════

def compare_models(traces, model_names):
    """Compare models using PSIS-LOO."""
    print(f"\n{'='*60}")
    print(f"MODEL COMPARISON (PSIS-LOO)")
    print(f"{'='*60}")

    compare_dict = {name: trace for name, trace in zip(model_names, traces)}
    comparison = az.compare(compare_dict, ic="loo")

    print(comparison.to_string())

    try:
        fig, ax = plt.subplots(1, 1, figsize=(8, 4))
        az.plot_compare(comparison, ax=ax)
        fig.tight_layout()
        fig.savefig(os.path.join(OUTPUT_DIR, "model_comparison.png"), dpi=150)
        plt.close(fig)
        print(f"  Saved: model_comparison.png")
    except Exception as e:
        print(f"  Comparison plot skipped: {e}")
        plt.close("all")

    return comparison


# ═══════════════════════════════════════════════════════════════
# RESULTS VISUALIZATION
# ═══════════════════════════════════════════════════════════════

def plot_coefficients(trace, model_name, meta):
    """Forest plot of coefficient posteriors on probability scale."""
    print(f"\n{'='*60}")
    print(f"COEFFICIENT ANALYSIS: {model_name}")
    print(f"{'='*60}")

    # Get slope parameters (excluding index variables and intercept)
    slope_vars = [v for v in trace.posterior.data_vars
                  if v.startswith("beta_")]

    if not slope_vars:
        print("  No slope variables found.")
        return

    try:
        fig, ax = plt.subplots(1, 1, figsize=(10, max(4, len(slope_vars) * 0.6)))
        az.plot_forest(trace, var_names=slope_vars, combined=True,
                       ci_prob=0.89, ax=ax)
        ax.axvline(0, color="red", linestyle="--", alpha=0.5)
        ax.set_title(f"Coefficient Posteriors (89% HDI) — {model_name}\n"
                     f"Effect on log-odds of interaction per 1 SD change in predictor")
        fig.tight_layout()
        fig.savefig(os.path.join(OUTPUT_DIR, f"coefficients_{model_name}.png"), dpi=150)
        plt.close(fig)
        print(f"  Saved: coefficients_{model_name}.png")
    except Exception as e:
        print(f"  Forest plot skipped: {e}")
        plt.close("all")

    # Print interpretable summary
    summary = az.summary(trace, var_names=slope_vars, ci_prob=0.89)
    print(f"\n  Slope posteriors (89% HDI):")
    for var in slope_vars:
        row = summary.loc[var]
        direction = "↑ risk" if float(row["mean"]) > 0 else "↓ risk"
        sig = "***" if (float(row["eti89_lb"]) > 0 or float(row["eti89_ub"]) < 0) else ""
        print(f"    {var:25s}: {float(row['mean']):+.3f} [{float(row['eti89_lb']):+.3f}, {float(row['eti89_ub']):+.3f}] "
              f"{direction} {sig}")


def plot_index_effects(trace, model_name, meta):
    """Plot category-level effects from index variables."""

    for var_prefix, cat_name in [("alpha_rudder", "rudder"),
                                  ("alpha_antifoul", "antifoul"),
                                  ("alpha_sailing", "sailing_mode")]:
        if var_prefix not in trace.posterior.data_vars:
            continue

        cats = meta["categories"][cat_name]
        samples = trace.posterior[var_prefix].values.reshape(-1, len(cats))

        fig, ax = plt.subplots(1, 1, figsize=(10, max(3, len(cats) * 0.5)))

        for i, cat in enumerate(cats):
            vals = samples[:, i]
            ax.hist(vals, bins=40, alpha=0.4, label=cat, density=True)

        ax.axvline(0, color="black", linestyle="--", alpha=0.3)
        ax.set_xlabel("Effect on log-odds of interaction")
        ax.set_ylabel("Density")
        ax.set_title(f"{cat_name} category effects — {model_name}")
        ax.legend(fontsize=8)
        fig.tight_layout()
        fig.savefig(os.path.join(OUTPUT_DIR, f"index_{cat_name}_{model_name}.png"), dpi=150)
        plt.close(fig)
        print(f"  Saved: index_{cat_name}_{model_name}.png")


def risk_scenarios(trace, meta):
    """Compute posterior predicted risk for specific sailing scenarios."""
    print(f"\n{'='*60}")
    print(f"RISK SCENARIOS")
    print(f"{'='*60}")

    std = meta["standardization"]

    def standardize_val(raw, param):
        return (raw - std[param]["mean"]) / std[param]["sd"]

    # Extract posterior samples
    post = trace.posterior
    alpha = post["alpha"].values.flatten()

    scenarios = [
        {
            "name": "Low risk: 12m sailboat, motorsailing, 5-7kts, 200m+ depth, daytime, light wind",
            "params": {
                "beta_length": standardize_val(1, "boat_length_ord"),     # 10-12.5m
                "beta_speed": standardize_val(2, "speed_ord"),            # 5-7 kts
                "beta_depth": standardize_val(3, "depth_ord"),            # 200m+
                "beta_distance": standardize_val(3, "distance_ord"),      # Over 10nm
                "beta_wind": standardize_val(1, "wind_ord"),              # F3-4
                "beta_sea": standardize_val(0, "sea_state_ord"),          # Calm
                "beta_daylight": 1,                                        # Daytime
                "beta_autopilot": 1,                                       # On
            }
        },
        {
            "name": "Medium risk: 12m sailboat, motoring, 5-7kts, 40-200m depth, daytime, moderate wind",
            "params": {
                "beta_length": standardize_val(1, "boat_length_ord"),
                "beta_speed": standardize_val(2, "speed_ord"),
                "beta_depth": standardize_val(2, "depth_ord"),            # 40-200m
                "beta_distance": standardize_val(2, "distance_ord"),      # 5-10nm
                "beta_wind": standardize_val(1, "wind_ord"),
                "beta_sea": standardize_val(1, "sea_state_ord"),          # Moderate
                "beta_daylight": 1,
                "beta_autopilot": 1,
            }
        },
        {
            "name": "Higher risk: 15m+ sailboat, motoring, 3-4kts, 20-40m depth, night",
            "params": {
                "beta_length": standardize_val(3, "boat_length_ord"),     # Over 15m
                "beta_speed": standardize_val(1, "speed_ord"),            # 3-4 kts
                "beta_depth": standardize_val(1, "depth_ord"),            # 20-40m
                "beta_distance": standardize_val(0, "distance_ord"),      # 0-2nm
                "beta_wind": standardize_val(0, "wind_ord"),              # F0-2
                "beta_sea": standardize_val(0, "sea_state_ord"),          # Calm
                "beta_daylight": 0,                                        # Night
                "beta_autopilot": 1,
            }
        },
    ]

    for scenario in scenarios:
        logit_p = alpha.copy()
        for param_name, value in scenario["params"].items():
            if param_name in post.data_vars:
                logit_p += post[param_name].values.flatten() * value

        p = 1 / (1 + np.exp(-logit_p))

        print(f"\n  📊 {scenario['name']}")
        print(f"     Predicted P(interaction):")
        print(f"       Mean:   {p.mean():.1%}")
        print(f"       Median: {np.median(p):.1%}")
        print(f"       89% HDI: [{np.percentile(p, 5.5):.1%}, {np.percentile(p, 94.5):.1%}]")


# ═══════════════════════════════════════════════════════════════
# MAIN PIPELINE
# ═══════════════════════════════════════════════════════════════

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    df, meta = load_data()

    # ── Step 1: Prior predictive simulation ──
    print("\n" + "█" * 60)
    print("  STEP 1: PRIOR PREDICTIVE SIMULATION")
    print("█" * 60)

    m0 = build_model_0(df)
    prior_predictive_check(m0, "M0_intercept")

    # ── Step 2: Parameter recovery ──
    print("\n" + "█" * 60)
    print("  STEP 2: PARAMETER RECOVERY VALIDATION")
    print("█" * 60)

    parameter_recovery_test()

    # ── Step 3: Fit progressive models ──
    print("\n" + "█" * 60)
    print("  STEP 3: FIT MODELS TO REAL DATA")
    print("█" * 60)

    models_and_data = {}
    traces = {}

    # M0
    m0 = build_model_0(df)
    traces["M0"] = fit_model(m0, "M0_intercept")

    # M1
    m1, df1 = build_model_1(df, meta)
    traces["M1"] = fit_model(m1, "M1_vessel")

    # M2
    m2, df2 = build_model_2(df, meta)
    traces["M2"] = fit_model(m2, "M2_vessel_activity")

    # M3
    m3, df3 = build_model_3(df, meta)
    traces["M3"] = fit_model(m3, "M3_primary")
    models_and_data["M3"] = (m3, df3)

    # M4
    m4, df4 = build_model_4(df, meta)
    traces["M4"] = fit_model(m4, "M4_full")
    models_and_data["M4"] = (m4, df4)

    # ── Step 4: Posterior predictive checks ──
    print("\n" + "█" * 60)
    print("  STEP 4: POSTERIOR PREDICTIVE CHECKS")
    print("█" * 60)

    for name in ["M3", "M4"]:
        m, df_c = models_and_data[name]
        posterior_predictive_check(m, traces[name], df_c, name)

    # ── Step 5: Model comparison ──
    print("\n" + "█" * 60)
    print("  STEP 5: MODEL COMPARISON (PSIS-LOO)")
    print("█" * 60)

    # LOO comparison for models with same data
    compare_models(
        [traces["M3"], traces["M4"]],
        ["M3_primary", "M4_full"]
    )

    # ── Step 6: Results ──
    print("\n" + "█" * 60)
    print("  STEP 6: RESULTS ANALYSIS")
    print("█" * 60)

    best_name = "M3"
    plot_coefficients(traces[best_name], best_name, meta)
    plot_index_effects(traces[best_name], best_name, meta)
    risk_scenarios(traces[best_name], meta)

    # Save all traces
    for name, trace in traces.items():
        try:
            trace.to_netcdf(os.path.join(OUTPUT_DIR, f"trace_{name}.nc"))
        except Exception:
            az.to_netcdf(trace, os.path.join(OUTPUT_DIR, f"trace_{name}.nc"))
    print(f"\nAll traces saved to {OUTPUT_DIR}/")

    print("\n" + "█" * 60)
    print("  ANALYSIS COMPLETE")
    print("█" * 60)


if __name__ == "__main__":
    main()
