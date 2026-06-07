#!/usr/bin/env python3
"""
Step 2: Causal DAG for Orca Interaction Analysis
Following Statistical Rethinking Ch. 5–6 — think causally before modeling.

Outputs a Mermaid diagram of the proposed causal structure and
determines the adjustment sets for causal vs predictive modeling.
"""


def print_dag():
    """Print the DAG reasoning and Mermaid diagram."""

    print("""
╔══════════════════════════════════════════════════════════════╗
║         CAUSAL DAG: ORCA INTERACTION RISK                   ║
╚══════════════════════════════════════════════════════════════╝

ESTIMAND 1 (Causal): What vessel/activity factors causally affect
                     the probability of an orca interaction?

ESTIMAND 2 (Predictive): Given observable conditions before/during
                         a passage, what is the predicted interaction risk?

═══════════════════════════════════════════════════════════════
CAUSAL REASONING
═══════════════════════════════════════════════════════════════

The variables fall into natural causal layers:

Layer 0: UNOBSERVED CONFOUNDERS
  - Orca presence/activity (U_orca): We can't directly observe where
    orcas are. This is the primary driver of interactions but is latent.
  - Location/route (U_location): Strongly determines orca exposure.
    Only partially observed (incidents have lat/lon, uneventful have port names).
  - Season (partially observed via month): Drives orca migration patterns.

Layer 1: VESSEL CHARACTERISTICS (pre-treatment, fixed before passage)
  - boat_type: Sail vs Motor → affects speed, sailing_mode, noise
  - boat_length: Fixed property → may affect orca interest/detectability
  - rudder: Fixed → key damage target, different types may be more vulnerable
  - antifoul_colour: Fixed → hypothesis that dark antifoul attracts orcas
  - hull_colour: Fixed → similar hypothesis

Layer 2: ACTIVITY STATE (partially chosen, partially imposed)
  - motoring_or_sailing: Choice, but constrained by wind
  - speed: Choice, but constrained by boat_type + wind + sea_state
  - autopilot: Choice, may affect rudder behavior/vibration

Layer 3: ENVIRONMENTAL CONDITIONS (not controllable)
  - wind_speed → affects sea_state, speed, sailing_mode
  - sea_state: Driven by wind
  - depth: Driven by route/location
  - distance_off_land: Driven by route/location
  - darkness/daylight: Time-dependent
  - moon, tide: Astronomical, may affect orca behavior

Layer 4: OUTCOME
  - interaction: Binary (incident vs uneventful)

═══════════════════════════════════════════════════════════════
KEY CAUSAL PATHS
═══════════════════════════════════════════════════════════════

1. DIRECT vessel effects (want to estimate):
   antifoul_colour → INTERACTION
   hull_colour → INTERACTION
   rudder_type → INTERACTION
   boat_length → INTERACTION

2. MEDIATED through activity:
   boat_type → speed → INTERACTION
   boat_type → sailing_mode → INTERACTION
   wind → speed → INTERACTION
   wind → sailing_mode → INTERACTION

3. CONFOUNDED by unobserved location:
   U_location → orca_presence → INTERACTION
   U_location → depth
   U_location → distance_off_land

═══════════════════════════════════════════════════════════════
ADJUSTMENT SETS
═══════════════════════════════════════════════════════════════

For CAUSAL model (effect of vessel characteristics):
  - Include: wind_speed, sea_state (confounders between activity and outcome)
  - Include: month/season (confounder via orca presence)
  - DO NOT include: speed, sailing_mode if asking about total effect
    of boat_type (they are mediators on the causal path)
  - DO include: speed, sailing_mode if asking about direct effect only

For PREDICTIVE model (best prediction of interaction):
  - Include everything observable: all vessel characteristics,
    all activity variables, all environmental conditions
  - Causal structure doesn't matter for prediction, only association

═══════════════════════════════════════════════════════════════
IMPORTANT CAVEATS
═══════════════════════════════════════════════════════════════

1. SELECTION BIAS: Both incident and uneventful reports are
   self-selected. The reported base rate (~33%) massively
   overstates the true rate. We handle this via an informative
   prior on the intercept.

2. GEOGRAPHIC CONFOUNDING: We cannot control for location
   properly. The predictor distributions (depth, distance, etc.)
   partially proxy for location but imperfectly.

3. COLLIDER WARNING: "damaged" and "tow_required" are POST-outcome
   variables. Including them would create collider bias.
   They are ONLY in incident reports, so we don't include them.
""")

    # Mermaid diagram
    mermaid = """
```mermaid
graph TD
    subgraph "Unobserved"
        UL["🌍 Location/Route"]
        UO["🐋 Orca Presence"]
    end

    subgraph "Layer 1: Vessel (Pre-treatment)"
        BT["⛵ boat_type"]
        BL["📏 boat_length"]
        RD["🔧 rudder_type"]
        AF["🎨 antifoul_colour"]
        HC["🎨 hull_colour"]
    end

    subgraph "Layer 2: Activity"
        SM["🚢 sailing_mode"]
        SP["⚡ speed"]
        AP["🤖 autopilot"]
    end

    subgraph "Layer 3: Environment"
        WS["💨 wind_speed"]
        SS["🌊 sea_state"]
        DP["📐 depth"]
        DL["📍 distance_off_land"]
        DY["☀️ daylight"]
        MN["🌙 moon"]
        TD["🌊 tide"]
        MO["📅 month/season"]
    end

    INT["⚠️ INTERACTION"]

    UL --> UO
    UL --> DP
    UL --> DL
    MO --> UO
    UO --> INT

    BT --> SM
    BT --> SP
    WS --> SS
    WS --> SP
    WS --> SM

    BL --> INT
    RD --> INT
    AF --> INT
    HC --> INT
    SM --> INT
    SP --> INT
    AP --> INT
    DP --> INT
    DL --> INT
    SS --> INT
    DY --> INT
    MN --> INT
    TD --> INT

    style INT fill:#ff6b6b,color:#fff
    style UO fill:#ffd93d,color:#000
    style UL fill:#ffd93d,color:#000
```
"""
    print(mermaid)


if __name__ == "__main__":
    print_dag()
