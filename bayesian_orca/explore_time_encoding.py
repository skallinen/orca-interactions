#!/usr/bin/env python3
"""
Explore better encodings for time-of-day.

The core problem:
- Incidents record a single time period (Day, Night, Dawn, Dusk)
- Uneventful passages record all periods covered (e.g., "Dawn, Day, Dusk, Night")

Approach: Split multi-period uneventful passages into separate "exposure windows".
Each window inherits the passage's other covariates. A passage through Day+Night 
becomes two observations: one daytime uneventful, one nighttime uneventful.

For the binary day/night variable:
- Day = Day
- Night = Night  
- Dawn → Day (light is increasing, visibility improving)
- Dusk → Night (light is decreasing, approaching darkness)

Then test three models:
1. Model A: Original encoding (multi-period → day)
2. Model B: "contains Night" → night
3. Model C: Split into exposure windows (with cluster-robust SEs or passage random effect)
"""
import json, warnings
warnings.filterwarnings("ignore")
import numpy as np
import pandas as pd

# Load raw data
inc = pd.read_csv("orca_data/incident_reports.csv")
une = pd.read_csv("orca_data/uneventful_reports.csv")

print("=" * 70)
print("  TIME-OF-DAY DATA EXPLORATION")
print("=" * 70)

# Incident time-of-day distribution
print("\n█ INCIDENTS - darkness_or_daylight values:")
print(inc['darkness_or_daylight'].value_counts().to_string())

# Uneventful time-of-day distribution  
print("\n█ UNEVENTFUL - darkness_or_daylight values:")
print(une['darkness_or_daylight'].value_counts().to_string())

# How many uneventful passages have multiple periods?
def count_periods(s):
    if pd.isna(s): return 0
    return len([x.strip() for x in s.split(',')])

une['n_periods'] = une['darkness_or_daylight'].map(count_periods)
print(f"\n█ UNEVENTFUL - number of time periods per passage:")
print(une['n_periods'].value_counts().sort_index().to_string())

# What combinations exist?
print(f"\n█ UNEVENTFUL - all unique time-period combinations:")
for combo, count in une['darkness_or_daylight'].value_counts().items():
    periods = [p.strip() for p in str(combo).split(',')]
    has_night = 'Night' in periods
    has_day = 'Day' in periods
    n_windows = sum(1 for p in periods if p in ['Day', 'Night', 'Dawn', 'Dusk'])
    print(f"  {combo:45s}: n={count:3d}  periods={n_windows}  has_night={has_night}  has_day={has_day}")

# Now build the split dataset
print("\n\n" + "=" * 70)
print("  BUILDING SPLIT DATASET (exposure windows)")
print("=" * 70)

# Map: Dawn → day, Dusk → night
PERIOD_TO_BINARY = {
    'Day': 1,
    'Night': 0,
    'Dawn': 1,   # light increasing
    'Dusk': 0,   # light decreasing
}

# For incidents: single observation with the recorded time
inc_rows = []
for _, row in inc.iterrows():
    tod = str(row['darkness_or_daylight']).strip()
    if tod in PERIOD_TO_BINARY:
        inc_rows.append({
            'passage_id': f"inc_{row.name}",
            'interaction': 1,
            'is_daytime': PERIOD_TO_BINARY[tod],
            'period': tod
        })
    else:
        # Unknown or NaN
        inc_rows.append({
            'passage_id': f"inc_{row.name}",
            'interaction': 1,
            'is_daytime': np.nan,
            'period': tod
        })

print(f"\nIncident exposure windows: {len(inc_rows)}")
inc_df = pd.DataFrame(inc_rows)
print(f"  Day:   {(inc_df['is_daytime']==1).sum()}")
print(f"  Night: {(inc_df['is_daytime']==0).sum()}")
print(f"  NaN:   {inc_df['is_daytime'].isna().sum()}")

# For uneventful: one window per distinct binary period
une_rows = []
for _, row in une.iterrows():
    tod = str(row['darkness_or_daylight']).strip()
    if pd.isna(row['darkness_or_daylight']):
        une_rows.append({
            'passage_id': f"une_{row.name}",
            'interaction': 0,
            'is_daytime': np.nan,
            'period': 'NaN'
        })
        continue
    
    periods = [p.strip() for p in tod.split(',')]
    # Get unique binary values (day or night) from the periods
    binary_values = set()
    for p in periods:
        if p in PERIOD_TO_BINARY:
            binary_values.add(PERIOD_TO_BINARY[p])
    
    if len(binary_values) == 0:
        une_rows.append({
            'passage_id': f"une_{row.name}",
            'interaction': 0,
            'is_daytime': np.nan,
            'period': tod
        })
    elif len(binary_values) == 1:
        # Only day or only night
        val = binary_values.pop()
        une_rows.append({
            'passage_id': f"une_{row.name}",
            'interaction': 0,
            'is_daytime': val,
            'period': tod
        })
    else:
        # Both day AND night - create two rows
        for val in sorted(binary_values):
            une_rows.append({
                'passage_id': f"une_{row.name}",
                'interaction': 0,
                'is_daytime': val,
                'period': tod
            })

une_df = pd.DataFrame(une_rows)
print(f"\nUneventful exposure windows: {len(une_df)} (from {len(une)} passages)")
print(f"  Day:   {(une_df['is_daytime']==1).sum()}")
print(f"  Night: {(une_df['is_daytime']==0).sum()}")
print(f"  NaN:   {une_df['is_daytime'].isna().sum()}")
print(f"  Passages that were split into 2 windows: {len(une_df) - len(une)}")

# Combined rates
print("\n█ Interaction rates under SPLIT encoding:")
all_df = pd.concat([inc_df, une_df], ignore_index=True)
for val, name in [(1, "Day"), (0, "Night")]:
    mask = all_df['is_daytime'] == val
    n = mask.sum()
    n_inc = (mask & (all_df['interaction']==1)).sum()
    n_une = (mask & (all_df['interaction']==0)).sum()
    rate = n_inc/n if n > 0 else 0
    print(f"  {name:6s}: n={n:4d}  incidents={n_inc:3d}  uneventful={n_une:3d}  rate={rate:.1%}")

# Compare all three encodings
print("\n\n█ COMPARISON OF ALL THREE ENCODINGS:")
print(f"  {'Encoding':<30s}  {'Night n':>8s}  {'Night inc':>10s}  {'Night rate':>11s}  {'Day n':>6s}  {'Day inc':>8s}  {'Day rate':>9s}")

# Encoding A: multi-period → day
df_full = pd.read_csv("bayesian_orca/data/modeling_data.csv")
n_night_A = (df_full['is_daytime']==0).sum()
inc_night_A = ((df_full['is_daytime']==0) & (df_full['interaction']==1)).sum()
n_day_A = (df_full['is_daytime']==1).sum()
inc_day_A = ((df_full['is_daytime']==1) & (df_full['interaction']==1)).sum()
print(f"  {'A: multi-period→day':<30s}  {n_night_A:8d}  {inc_night_A:10d}  {inc_night_A/n_night_A:10.1%}  {n_day_A:6d}  {inc_day_A:8d}  {inc_day_A/n_day_A:8.1%}")

# Encoding B: contains Night → night
combined_raw = pd.concat([inc['darkness_or_daylight'], une['darkness_or_daylight']], ignore_index=True)
combined_int = pd.concat([pd.Series([1]*len(inc)), pd.Series([0]*len(une))], ignore_index=True)
is_night_B = combined_raw.map(lambda x: 'Night' in str(x))
n_night_B = is_night_B.sum()
inc_night_B = (is_night_B & (combined_int==1)).sum()
n_day_B = (~is_night_B).sum()
inc_day_B = (~is_night_B & (combined_int==1)).sum()
print(f"  {'B: contains Night→night':<30s}  {n_night_B:8d}  {inc_night_B:10d}  {inc_night_B/n_night_B:10.1%}  {n_day_B:6d}  {inc_day_B:8d}  {inc_day_B/n_day_B:8.1%}")

# Encoding C: split
n_night_C = (all_df['is_daytime']==0).sum()
inc_night_C = ((all_df['is_daytime']==0) & (all_df['interaction']==1)).sum()
n_day_C = (all_df['is_daytime']==1).sum()
inc_day_C = ((all_df['is_daytime']==1) & (all_df['interaction']==1)).sum()
print(f"  {'C: split (dawn→day,dusk→night)':<30s}  {n_night_C:8d}  {inc_night_C:10d}  {inc_night_C/n_night_C:10.1%}  {n_day_C:6d}  {inc_day_C:8d}  {inc_day_C/n_day_C:8.1%}")

# Also try: dawn→night, dusk→day
PERIOD_TO_BINARY_ALT = {'Day': 1, 'Night': 0, 'Dawn': 0, 'Dusk': 1}
inc_alt = []
for _, row in inc.iterrows():
    tod = str(row['darkness_or_daylight']).strip()
    if tod in PERIOD_TO_BINARY_ALT:
        inc_alt.append({'interaction': 1, 'is_daytime': PERIOD_TO_BINARY_ALT[tod]})
une_alt = []
for _, row in une.iterrows():
    tod = str(row['darkness_or_daylight']).strip()
    if pd.isna(row['darkness_or_daylight']): continue
    periods = [p.strip() for p in tod.split(',')]
    binary_values = set()
    for p in periods:
        if p in PERIOD_TO_BINARY_ALT: binary_values.add(PERIOD_TO_BINARY_ALT[p])
    for val in sorted(binary_values):
        une_alt.append({'interaction': 0, 'is_daytime': val})

alt_df = pd.DataFrame(inc_alt + une_alt)
n_night_D = (alt_df['is_daytime']==0).sum()
inc_night_D = ((alt_df['is_daytime']==0) & (alt_df['interaction']==1)).sum()
n_day_D = (alt_df['is_daytime']==1).sum()
inc_day_D = ((alt_df['is_daytime']==1) & (alt_df['interaction']==1)).sum()
print(f"  {'D: split (dawn→night,dusk→day)':<30s}  {n_night_D:8d}  {inc_night_D:10d}  {inc_night_D/n_night_D:10.1%}  {n_day_D:6d}  {inc_day_D:8d}  {inc_day_D/n_day_D:8.1%}")

print("\n" + "=" * 70)
print("  KEY INSIGHT")
print("=" * 70)
print("""
The split encoding (C) creates pseudo-replicated rows for multi-period passages.
This inflates the sample size and violates independence. 

To handle this properly we would need either:
1. A mixed-effects model with a random intercept per passage_id
2. Cluster-robust standard errors
3. GEE (Generalized Estimating Equations)

But the fundamental problem remains: incident reports are point-in-time 
while uneventful reports are time-spans. No encoding can fully fix this 
asymmetry. The best we can do is show the range of results across 
reasonable encodings and let the reader judge.
""")
