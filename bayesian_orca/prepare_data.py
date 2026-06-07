#!/usr/bin/env python3
"""
Step 1: Data Preparation for Bayesian Orca Interaction Analysis
Following Statistical Rethinking workflow — clean, harmonize, encode.

Creates a single modeling-ready DataFrame with:
- Binary outcome: interaction (1) vs uneventful (0)
- Index-coded categorical predictors (McElreath Ch. 5)
- Standardized ordinal predictors
- Parsed moon/tide/daylight fields
"""

import pandas as pd
import numpy as np
import json
import re
import os
import sys

DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "orca_data")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "data")


def load_reports():
    """Load all detailed reports from JSON (more reliable than multi-line CSVs)."""
    json_path = os.path.join(DATA_DIR, "all_reports_detailed.json")
    with open(json_path) as f:
        reports = json.load(f)

    incidents = [r for r in reports if r["report_type"] == "incident"]
    uneventful = [r for r in reports if r["report_type"] == "uneventful"]
    print(f"Loaded {len(incidents)} incidents, {len(uneventful)} uneventful passages")
    return incidents, uneventful


def parse_moon(moon_str):
    """Parse moon field like 'waning<br>80% illuminated<br>within 3 days of full'."""
    if not moon_str or pd.isna(moon_str):
        return np.nan, np.nan

    moon_str = str(moon_str).lower()

    # Extract illumination percentage
    illum_match = re.search(r"(\d+)%", moon_str)
    illumination = int(illum_match.group(1)) if illum_match else np.nan

    # Extract phase
    if "waxing" in moon_str:
        phase = 1  # waxing
    elif "waning" in moon_str:
        phase = 0  # waning
    else:
        phase = np.nan

    return illumination, phase


def harmonize_daylight(value):
    """Harmonize darkness_or_daylight field.
    Incidents: single value (Day, Night, Dawn, Dusk)
    Uneventful: multi-select comma-separated.
    Collapse to binary: 1=day, 0=not-day.
    """
    if not value or pd.isna(value):
        return np.nan
    value = str(value).lower().strip()
    # If it contains "day" as a component, treat as daytime
    # (many uneventful passages span multiple conditions)
    if "day" in value:
        return 1
    return 0


def harmonize_trailing(row):
    """Unify towing (incidents) and trailing (uneventful) fields."""
    # Check both possible field names
    towing = str(row.get("towing", "")).lower().strip()
    trailing = str(row.get("trailing", "")).lower().strip()

    if "not towing" in towing or trailing == "no" or towing == "no":
        return 0
    if trailing == "yes" or ("towing" in towing and "not" not in towing):
        return 1
    if towing in ("", "nan") and trailing in ("", "nan"):
        return np.nan
    return 0  # Default to not towing if unclear


def extract_date(row):
    """Extract a date from either incident or uneventful fields."""
    for field in ["date_of_interaction", "date_passage_commenced"]:
        val = row.get(field, "")
        if val and str(val) != "nan" and str(val).strip():
            return str(val).strip()
    return None


# --- Ordinal encoding maps ---
BOAT_LENGTH_MAP = {
    "Under 10m": 0,
    "10 - 12.5m": 1,
    "12.5 - 15m": 2,
    "Over 15m": 3,
}

DEPTH_MAP = {
    "Up to 20m": 0,
    "20 - 40m": 1,
    "40 - 200m": 2,
    "200m+": 3,
}

DISTANCE_MAP = {
    "0 - 2": 0,
    "2 - 5": 1,
    "5 - 10": 2,
    "Over 10": 3,
}

SPEED_MAP = {
    "0 - 2": 0,
    "3 - 4": 1,
    "5 - 7": 2,
    "8 - 11": 3,
}

WIND_MAP = {
    "0 - 2 (0 - 6 knots)": 0,
    "3 - 4 (7 - 16 knots)": 1,
    "5 - 6 (17 - 27 knots)": 2,
    "7+ (28 knots+)": 3,
}

SEA_STATE_MAP = {
    "Calm": 0,
    "Moderate": 1,
    "Rough": 2,
}

CLOUD_COVER_MAP = {
    "0 - 25%": 0,
    "25 - 50%": 1,
    "50 - 75%": 2,
    "75 - 100%": 3,
}


def encode_ordinal(series, mapping, name):
    """Encode an ordinal variable using a mapping dict."""
    result = series.map(lambda x: mapping.get(str(x).strip(), np.nan) if pd.notna(x) else np.nan)
    valid = result.notna().sum()
    total = len(result)
    print(f"  {name}: {valid}/{total} mapped ({total-valid} missing)")
    return result


def encode_index(series, name):
    """Create index variable (0-based) for categorical predictor.
    Returns (encoded_series, category_list).
    McElreath Ch. 5: each category gets its own parameter.
    """
    clean = series.fillna("Unknown").astype(str).str.strip()
    categories = sorted(clean.unique())
    cat_map = {cat: i for i, cat in enumerate(categories)}
    encoded = clean.map(cat_map)
    print(f"  {name}: {len(categories)} categories: {categories}")
    return encoded, categories


def standardize(series, name):
    """Standardize a numeric series (z-score). Returns (standardized, mean, sd)."""
    mean = series.mean()
    sd = series.std()
    if sd == 0 or pd.isna(sd):
        print(f"  WARNING: {name} has zero variance, skipping standardization")
        return series, mean, 1.0
    standardized = (series - mean) / sd
    print(f"  {name}: mean={mean:.2f}, sd={sd:.2f}")
    return standardized, mean, sd


def prepare_data():
    """Main data preparation pipeline."""
    incidents, uneventful = load_reports()

    # Combine into single list
    all_reports = incidents + uneventful
    df = pd.DataFrame(all_reports)

    print(f"\nTotal records: {len(df)}")
    print(f"  Incidents: {(df['report_type'] == 'incident').sum()}")
    print(f"  Uneventful: {(df['report_type'] == 'uneventful').sum()}")

    # === 1. Binary outcome ===
    df["interaction"] = (df["report_type"] == "incident").astype(int)

    # === 2. Extract date and month ===
    df["date_str"] = df.apply(extract_date, axis=1)
    df["date"] = pd.to_datetime(df["date_str"], format="mixed", errors="coerce")
    df["month"] = df["date"].dt.month
    df["year"] = df["date"].dt.year
    valid_dates = df["date"].notna().sum()
    print(f"\nDates parsed: {valid_dates}/{len(df)}")

    # === 3. Parse moon field ===
    print("\nParsing moon field...")
    moon_parsed = df["moon"].apply(lambda x: parse_moon(x) if pd.notna(x) else (np.nan, np.nan))
    df["moon_illumination"] = moon_parsed.apply(lambda x: x[0])
    df["moon_waxing"] = moon_parsed.apply(lambda x: x[1])
    valid_moon = df["moon_illumination"].notna().sum()
    print(f"  Moon illumination: {valid_moon}/{len(df)} parsed")

    # === 4. Harmonize daylight ===
    print("\nHarmonizing daylight...")
    df["is_daytime"] = df["darkness_or_daylight"].apply(harmonize_daylight)
    valid_day = df["is_daytime"].notna().sum()
    print(f"  Daylight: {valid_day}/{len(df)} harmonized")

    # === 5. Harmonize trailing/towing ===
    print("\nHarmonizing trailing/towing...")
    df["is_towing"] = df.apply(harmonize_trailing, axis=1)
    valid_tow = df["is_towing"].notna().sum()
    print(f"  Towing: {valid_tow}/{len(df)} harmonized")

    # === 6. Tide (binary) ===
    print("\nEncoding tide...")
    df["is_spring_tide"] = df["tide"].apply(
        lambda x: 1 if pd.notna(x) and "within 3 days" in str(x).lower() and "not" not in str(x).lower()
        else (0 if pd.notna(x) and "not" in str(x).lower() else np.nan)
    )
    valid_tide = df["is_spring_tide"].notna().sum()
    print(f"  Spring tide: {valid_tide}/{len(df)} encoded")

    # === 7. Autopilot (binary) ===
    print("\nEncoding autopilot...")
    df["autopilot_on"] = df["autopilot"].apply(
        lambda x: 1 if pd.notna(x) and "on" in str(x).lower().strip()
        else (0 if pd.notna(x) and "off" in str(x).lower().strip() else np.nan)
    )
    valid_ap = df["autopilot_on"].notna().sum()
    print(f"  Autopilot: {valid_ap}/{len(df)} encoded")

    # === 8. Encode ordinal variables ===
    print("\nEncoding ordinal variables...")
    df["boat_length_ord"] = encode_ordinal(df["boat_length"], BOAT_LENGTH_MAP, "boat_length")
    df["depth_ord"] = encode_ordinal(df["depth"], DEPTH_MAP, "depth")
    df["distance_ord"] = encode_ordinal(df["distance_off_land"], DISTANCE_MAP, "distance_off_land")
    df["speed_ord"] = encode_ordinal(df["speed"], SPEED_MAP, "speed")
    df["wind_ord"] = encode_ordinal(df["wind_speed"], WIND_MAP, "wind_speed")
    df["sea_state_ord"] = encode_ordinal(df["sea_state"], SEA_STATE_MAP, "sea_state")
    df["cloud_cover_ord"] = encode_ordinal(df["cloud_cover"], CLOUD_COVER_MAP, "cloud_cover")

    # === 9. Encode index (categorical) variables ===
    print("\nEncoding categorical variables (index coding)...")
    df["boat_type_idx"], boat_type_cats = encode_index(df["boat_type"], "boat_type")
    df["rudder_idx"], rudder_cats = encode_index(df["rudder"], "rudder")
    df["antifoul_idx"], antifoul_cats = encode_index(df["antifoul_colour"], "antifoul")
    df["sailing_mode_idx"], sailing_cats = encode_index(df["motoring_or_sailing"], "sailing_mode")
    df["hull_colour_idx"], hull_cats = encode_index(df["hull_topsides_colour"], "hull_colour")

    # === 10. Standardize ordinal predictors ===
    print("\nStandardizing ordinal predictors...")
    standardization_params = {}
    for col in ["boat_length_ord", "depth_ord", "distance_ord", "speed_ord",
                 "wind_ord", "sea_state_ord", "cloud_cover_ord", "moon_illumination"]:
        valid = df[col].dropna()
        if len(valid) > 0:
            col_std = f"{col}_std"
            df[col_std], mean, sd = standardize(df[col], col)
            standardization_params[col] = {"mean": mean, "sd": sd}

    # === 11. Select modeling columns ===
    model_cols = [
        # Outcome
        "interaction",
        # Metadata
        "report_id", "report_type", "date", "month", "year",
        # Ordinal predictors (raw)
        "boat_length_ord", "depth_ord", "distance_ord", "speed_ord",
        "wind_ord", "sea_state_ord", "cloud_cover_ord",
        # Ordinal predictors (standardized)
        "boat_length_ord_std", "depth_ord_std", "distance_ord_std", "speed_ord_std",
        "wind_ord_std", "sea_state_ord_std", "cloud_cover_ord_std",
        # Categorical predictors (index)
        "boat_type_idx", "rudder_idx", "antifoul_idx", "sailing_mode_idx", "hull_colour_idx",
        # Binary predictors
        "is_daytime", "autopilot_on", "is_towing", "is_spring_tide", "moon_waxing",
        # Continuous
        "moon_illumination", "moon_illumination_std",
        # Location (incidents only)
        "summary_lat", "summary_long",
    ]

    # Filter to existing columns
    model_cols = [c for c in model_cols if c in df.columns]
    df_model = df[model_cols].copy()

    # === 12. Report missing data ===
    print("\n" + "=" * 60)
    print("MISSING DATA REPORT")
    print("=" * 60)
    predictor_cols = [c for c in model_cols if c not in
                      ["interaction", "report_id", "report_type", "date", "month", "year",
                       "summary_lat", "summary_long"]]
    for col in predictor_cols:
        if col.endswith("_std"):
            continue  # Skip standardized versions
        missing = df_model[col].isna().sum()
        pct = missing / len(df_model) * 100
        if missing > 0:
            print(f"  {col:30s}: {missing:4d} missing ({pct:.1f}%)")

    # === 13. Report class distributions for key predictors ===
    print("\n" + "=" * 60)
    print("PREDICTOR DISTRIBUTIONS: Incident vs Uneventful")
    print("=" * 60)
    for col, name, cat_list in [
        ("boat_type_idx", "boat_type", boat_type_cats),
        ("rudder_idx", "rudder", rudder_cats),
        ("antifoul_idx", "antifoul", antifoul_cats),
        ("sailing_mode_idx", "sailing_mode", sailing_cats),
    ]:
        print(f"\n  {name}:")
        for i, cat in enumerate(cat_list):
            n_inc = ((df_model[col] == i) & (df_model["interaction"] == 1)).sum()
            n_une = ((df_model[col] == i) & (df_model["interaction"] == 0)).sum()
            total = n_inc + n_une
            rate = n_inc / total * 100 if total > 0 else 0
            print(f"    [{i}] {cat:25s}: {n_inc:3d} inc / {n_une:3d} une = {rate:.1f}% interaction rate")

    # === 14. Save ===
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # Save modeling dataframe
    output_path = os.path.join(OUTPUT_DIR, "modeling_data.csv")
    df_model.to_csv(output_path, index=False)
    print(f"\nSaved modeling data: {output_path} ({len(df_model)} rows, {len(model_cols)} columns)")

    # Save category mappings and standardization params
    metadata = {
        "categories": {
            "boat_type": boat_type_cats,
            "rudder": rudder_cats,
            "antifoul": antifoul_cats,
            "sailing_mode": sailing_cats,
            "hull_colour": hull_cats,
        },
        "ordinal_maps": {
            "boat_length": BOAT_LENGTH_MAP,
            "depth": DEPTH_MAP,
            "distance_off_land": DISTANCE_MAP,
            "speed": SPEED_MAP,
            "wind_speed": WIND_MAP,
            "sea_state": SEA_STATE_MAP,
            "cloud_cover": CLOUD_COVER_MAP,
        },
        "standardization": standardization_params,
        "n_incidents": int((df_model["interaction"] == 1).sum()),
        "n_uneventful": int((df_model["interaction"] == 0).sum()),
    }
    meta_path = os.path.join(OUTPUT_DIR, "metadata.json")
    with open(meta_path, "w") as f:
        json.dump(metadata, f, indent=2, default=str)
    print(f"Saved metadata: {meta_path}")

    return df_model, metadata


if __name__ == "__main__":
    df, meta = prepare_data()
    print(f"\nReady for modeling: {len(df)} observations, "
          f"{meta['n_incidents']} interactions, {meta['n_uneventful']} uneventful")
