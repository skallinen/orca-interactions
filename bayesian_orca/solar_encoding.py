#!/usr/bin/env python3
"""
Re-encode time-of-day using actual timestamps and solar position.

For incidents: use date_of_interaction + time_of_interaction
For uneventful: use date_passage_commenced + time_passage_commenced
                AND  date_passage_ended + time_passage_ended

Solar categories (using astral library):
- Night: sun below -6° (civil twilight boundary)
- Dawn:  between sunrise-30min and sunrise+30min (rough civil dawn)
- Day:   sun above horizon
- Dusk:  between sunset-30min and sunset+30min

Actually simpler: use astral's built-in dawn/sunrise/sunset/dusk.
- Before dawn: Night
- Dawn to sunrise: Dawn
- Sunrise to sunset: Day
- Sunset to dusk: Dusk
- After dusk: Night

For uneventful passages that span multiple periods:
- Compute what fraction of the passage is in each period
- Or just: did the passage include night hours?

We'll use a central orca zone location: 37°N, 8°W (off southern Portugal)
"""
import warnings
warnings.filterwarnings("ignore")
import numpy as np
import pandas as pd
from datetime import datetime, timedelta, timezone
from astral import LocationInfo
from astral.sun import sun

# Central orca zone location
LOCATION = LocationInfo("Orca Zone", "Atlantic", "UTC", 37.0, -8.0)

def get_solar_period(dt):
    """Classify a datetime as Day, Night, Dawn, or Dusk using solar position."""
    try:
        s = sun(LOCATION.observer, date=dt.date())
        dawn = s['dawn'].replace(tzinfo=None)
        sunrise = s['sunrise'].replace(tzinfo=None)
        sunset = s['sunset'].replace(tzinfo=None)
        dusk = s['dusk'].replace(tzinfo=None)
        
        t = dt.replace(tzinfo=None)
        if t < dawn:
            return 'Night'
        elif t < sunrise:
            return 'Dawn'
        elif t < sunset:
            return 'Day'
        elif t < dusk:
            return 'Dusk'
        else:
            return 'Night'
    except Exception:
        return None

def get_night_fraction(start_dt, end_dt, steps=24):
    """Estimate what fraction of a passage is in darkness (night or dusk/dawn)."""
    if start_dt >= end_dt:
        return None, None
    
    duration = (end_dt - start_dt).total_seconds()
    step_secs = duration / steps
    
    periods = {'Day': 0, 'Night': 0, 'Dawn': 0, 'Dusk': 0}
    for i in range(steps):
        t = start_dt + timedelta(seconds=i * step_secs)
        p = get_solar_period(t)
        if p:
            periods[p] += 1
    
    total = sum(periods.values())
    if total == 0:
        return None, None
    
    # Binary: night = Night + Dusk, day = Day + Dawn (conservative)
    is_any_night = periods['Night'] > 0 or periods['Dusk'] > 0
    night_frac = (periods['Night'] + periods['Dusk']) / total
    
    return night_frac, periods

# Load data
inc = pd.read_csv("orca_data/incident_reports.csv")
une = pd.read_csv("orca_data/uneventful_reports.csv")

print("=" * 70)
print("  SOLAR-BASED TIME ENCODING")
print("=" * 70)

# INCIDENTS: classify the exact moment
print("\n█ INCIDENTS:")
inc_solar = []
for _, row in inc.iterrows():
    try:
        dt = datetime.strptime(f"{row['date_of_interaction']} {row['time_of_interaction']}", "%Y-%m-%d %H:%M:%S")
        period = get_solar_period(dt)
        inc_solar.append({
            'idx': row.name,
            'datetime': dt,
            'solar_period': period,
            'reported_period': row['darkness_or_daylight'],
            'interaction': 1
        })
    except Exception as e:
        inc_solar.append({
            'idx': row.name,
            'datetime': None,
            'solar_period': None,
            'reported_period': row['darkness_or_daylight'],
            'interaction': 1
        })

inc_solar_df = pd.DataFrame(inc_solar)
print(f"  Solar classification vs reported:")
ct = pd.crosstab(inc_solar_df['reported_period'], inc_solar_df['solar_period'], margins=True)
print(ct.to_string())

# Agreement rate
agree = (inc_solar_df['reported_period'] == inc_solar_df['solar_period']).sum()
print(f"\n  Agreement: {agree}/{len(inc_solar_df)} ({agree/len(inc_solar_df):.0%})")

# Binary classification for incidents
inc_solar_df['is_daytime_solar'] = inc_solar_df['solar_period'].map(
    lambda x: 1 if x in ['Day', 'Dawn'] else 0 if x in ['Night', 'Dusk'] else np.nan)
print(f"\n  Solar binary (Dawn→Day, Dusk→Night):")
print(f"    Day:   {(inc_solar_df['is_daytime_solar']==1).sum()}")
print(f"    Night: {(inc_solar_df['is_daytime_solar']==0).sum()}")

# UNEVENTFUL: classify start and end, compute night fraction
print("\n\n█ UNEVENTFUL PASSAGES:")
une_solar = []
for _, row in une.iterrows():
    try:
        start = datetime.strptime(f"{row['date_passage_commenced']} {row['time_passage_commenced']}", "%Y-%m-%d %H:%M:%S")
        end = datetime.strptime(f"{row['date_passage_ended']} {row['time_passage_ended']}", "%Y-%m-%d %H:%M:%S")
        
        # Handle overnight passages where end < start (next day)
        if end <= start:
            end += timedelta(days=1)
        
        duration_hrs = (end - start).total_seconds() / 3600
        night_frac, periods = get_night_fraction(start, end, steps=max(24, int(duration_hrs * 2)))
        
        start_period = get_solar_period(start)
        end_period = get_solar_period(end)
        
        une_solar.append({
            'idx': row.name,
            'start': start,
            'end': end,
            'duration_hrs': duration_hrs,
            'night_fraction': night_frac,
            'periods': periods,
            'start_period': start_period,
            'end_period': end_period,
            'reported_period': row['darkness_or_daylight'],
            'interaction': 0
        })
    except Exception as e:
        une_solar.append({
            'idx': row.name,
            'start': None, 'end': None, 'duration_hrs': None,
            'night_fraction': None, 'periods': None,
            'start_period': None, 'end_period': None,
            'reported_period': row['darkness_or_daylight'],
            'interaction': 0
        })

une_solar_df = pd.DataFrame(une_solar)

print(f"  Duration distribution (hours):")
durations = une_solar_df['duration_hrs'].dropna()
print(f"    Mean:   {durations.mean():.1f}h")
print(f"    Median: {durations.median():.1f}h")
print(f"    Min:    {durations.min():.1f}h")
print(f"    Max:    {durations.max():.1f}h")
print(f"    >12h:   {(durations > 12).sum()}")
print(f"    >24h:   {(durations > 24).sum()}")

print(f"\n  Night fraction distribution:")
nf = une_solar_df['night_fraction'].dropna()
print(f"    Mean:     {nf.mean():.2f}")
print(f"    Median:   {nf.median():.2f}")
print(f"    =0 (pure day):   {(nf == 0).sum()}")
print(f"    >0 (some night): {(nf > 0).sum()}")
print(f"    >0.5 (mostly night): {(nf > 0.5).sum()}")

# Binary: any night exposure → night passage
une_solar_df['any_night'] = une_solar_df['night_fraction'].map(lambda x: 1 if x and x > 0 else 0)
print(f"\n  Binary (any night exposure):")
print(f"    Day-only:      {(une_solar_df['any_night']==0).sum()}")
print(f"    Includes night: {(une_solar_df['any_night']==1).sum()}")

# Compare solar classification with reported
print(f"\n  Comparison: solar vs reported 'contains Night':")
une_solar_df['reported_has_night'] = une_solar_df['reported_period'].map(lambda x: 1 if 'Night' in str(x) else 0)
ct2 = pd.crosstab(une_solar_df['reported_has_night'], une_solar_df['any_night'],
                   rownames=['Reported has Night'], colnames=['Solar has Night'], margins=True)
print(ct2.to_string())

# FINAL COMPARISON
print("\n\n" + "=" * 70)
print("  SOLAR-BASED ENCODING: interaction rates")
print("=" * 70)

# Incidents: use solar period
# Uneventful: use "any night" based on solar calculation
inc_day = (inc_solar_df['is_daytime_solar'] == 1).sum()
inc_night = (inc_solar_df['is_daytime_solar'] == 0).sum()
une_day = (une_solar_df['any_night'] == 0).sum()
une_night = (une_solar_df['any_night'] == 1).sum()

print(f"\n  Approach: Incident=solar moment, Uneventful=any night exposure")
print(f"  {'':6s}  {'Incidents':>10s}  {'Uneventful':>11s}  {'Total':>6s}  {'Rate':>6s}")
n_day = inc_day + une_day
n_night = inc_night + une_night
print(f"  {'Day':6s}  {inc_day:10d}  {une_day:11d}  {n_day:6d}  {inc_day/n_day:.1%}")
print(f"  {'Night':6s}  {inc_night:10d}  {une_night:11d}  {n_night:6d}  {inc_night/n_night:.1%}")

# Also try: continuous night_fraction as a predictor instead of binary
print(f"\n  For uneventful passages, night fraction stats by reported period:")
for combo in une_solar_df['reported_period'].value_counts().head(8).index:
    mask = une_solar_df['reported_period'] == combo
    nf_vals = une_solar_df.loc[mask, 'night_fraction'].dropna()
    print(f"    {combo:40s}: n={mask.sum():3d}  night_frac={nf_vals.mean():.2f} ± {nf_vals.std():.2f}")

print("\n" + "=" * 70)
