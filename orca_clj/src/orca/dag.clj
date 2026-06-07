(ns orca.dag
  "Causal DAG for the orca-interaction analysis (Statistical Rethinking
   Ch. 5–6: think causally before modeling).

   Pure documentation: `print-dag` emits the causal reasoning, the adjustment
   sets for the causal vs predictive estimands, the key caveats (selection bias,
   geographic confounding, collider warning on post-outcome variables), and a
   Mermaid diagram of the proposed structure. `mermaid` returns just the diagram
   source so it can be embedded elsewhere (e.g. the methodology page)."
  (:require
   [clojure.string :as str]))

(def mermaid
  "Mermaid source for the proposed causal DAG."
  (str/join
    "\n"
    ["```mermaid"
     "graph TD"
     "    subgraph \"Unobserved\""
     "        UL[\"Location/Route\"]"
     "        UO[\"Orca Presence\"]"
     "    end"
     ""
     "    subgraph \"Layer 1: Vessel (Pre-treatment)\""
     "        BT[\"boat_type\"]"
     "        BL[\"boat_length\"]"
     "        RD[\"rudder_type\"]"
     "        AF[\"antifoul_colour\"]"
     "        HC[\"hull_colour\"]"
     "    end"
     ""
     "    subgraph \"Layer 2: Activity\""
     "        SM[\"sailing_mode\"]"
     "        SP[\"speed\"]"
     "        AP[\"autopilot\"]"
     "    end"
     ""
     "    subgraph \"Layer 3: Environment\""
     "        WS[\"wind_speed\"]"
     "        SS[\"sea_state\"]"
     "        DP[\"depth\"]"
     "        DL[\"distance_off_land\"]"
     "        DY[\"daylight\"]"
     "        MN[\"moon\"]"
     "        TD[\"tide\"]"
     "        MO[\"month/season\"]"
     "    end"
     ""
     "    INT[\"INTERACTION\"]"
     ""
     "    UL --> UO"
     "    UL --> DP"
     "    UL --> DL"
     "    MO --> UO"
     "    UO --> INT"
     ""
     "    BT --> SM"
     "    BT --> SP"
     "    WS --> SS"
     "    WS --> SP"
     "    WS --> SM"
     ""
     "    BL --> INT"
     "    RD --> INT"
     "    AF --> INT"
     "    HC --> INT"
     "    SM --> INT"
     "    SP --> INT"
     "    AP --> INT"
     "    DP --> INT"
     "    DL --> INT"
     "    SS --> INT"
     "    DY --> INT"
     "    MN --> INT"
     "    TD --> INT"
     "```"]))

(def adjustment-notes
  "The causal reasoning, adjustment sets, and caveats."
  (str/join
    "\n"
    ["CAUSAL DAG: ORCA INTERACTION RISK"
     ""
     "ESTIMAND 1 (Causal):    What vessel/activity factors causally affect the"
     "                        probability of an orca interaction?"
     "ESTIMAND 2 (Predictive): Given observable conditions before/during a passage,"
     "                        what is the predicted interaction risk?"
     ""
     "VARIABLE LAYERS"
     "  Layer 0  Unobserved confounders: orca presence (U_orca), location/route"
     "           (U_location, only partially observed), season (via month)."
     "  Layer 1  Vessel characteristics (pre-treatment): boat_type, boat_length,"
     "           rudder, antifoul_colour, hull_colour."
     "  Layer 2  Activity state: sailing_mode, speed, autopilot."
     "  Layer 3  Environment: wind, sea_state, depth, distance, daylight, moon, tide."
     "  Layer 4  Outcome: interaction (incident vs uneventful)."
     ""
     "ADJUSTMENT SETS"
     "  Causal model (effect of vessel characteristics):"
     "    - Include wind_speed, sea_state (confounders between activity and outcome)."
     "    - Include month/season (confounder via orca presence)."
     "    - For the TOTAL effect of boat_type, do NOT condition on speed/sailing_mode"
     "      (they are mediators); for the DIRECT effect, do condition on them."
     "  Predictive model (best prediction): include everything observable; causal"
     "    structure is irrelevant for prediction, only association."
     ""
     "  NOTE — what the fitted M3 actually implements: M3 is the PREDICTIVE estimand"
     "    (all observables), NOT this DAG's causal adjustment set. It conditions on"
     "    speed/sailing_mode (mediators) and carries NO month/season term, so month"
     "    is an unadjusted known confounder. The reported coefficients are therefore"
     "    associational, not the DAG's causal effect of vessel characteristics."
     ""
     "CAVEATS"
     "  1. SELECTION BIAS: both incident and uneventful reports are self-selected;"
     "     the reported base rate (~33%) massively overstates the true rate. Handled"
     "     via an informative Fermi prior on the intercept."
     "  2. GEOGRAPHIC CONFOUNDING: location cannot be controlled properly; depth /"
     "     distance only partially proxy for it."
     "  3. COLLIDER WARNING: 'damaged' and 'tow_required' are POST-outcome variables"
     "     (only in incident reports); conditioning on them would induce collider"
     "     bias, so they are excluded."]))

(defn print-dag
  "Print the causal reasoning, adjustment sets, caveats, and the Mermaid diagram."
  [& _]
  (println adjustment-notes)
  (println)
  (println mermaid)
  {:mermaid mermaid :notes adjustment-notes})
