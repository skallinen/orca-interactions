(ns orca.model
  "Fit M3 (no daylight) with CmdStan and export posterior draws in the same
   layout as bayesian_orca/refit_no_daylight.py -> blogpost/posterior_draws.json."
  (:require
   [orca.config :as config]
   [orca.prepare :as prep]
   [orca.stan :as stan]
   [orca.util :as util]
   [tablecloth.api :as tc]))

(def model-predictors
  "Columns that must be complete (matches the Python mask)."
  [:depth_ord_std :autopilot_on :speed_ord_std :boat_length_ord_std
   :distance_ord_std :wind_ord_std :sea_state_ord_std :sailing_mode_idx
   :antifoul_idx :hull_colour_idx :rudder_idx])

(defn complete-cases
  "Filter to rows with no missing model predictor (Python complete-case mask)."
  [ds]
  (tc/drop-missing ds model-predictors))

(defn stan-data
  "Build the CmdStan data map from a complete-case dataset + metadata.
   Category indices are shifted 0-based -> 1-based."
  [d md]
  (let [col   (fn [k] (vec (d k)))
        idx1  (fn [k] (mapv #(inc (long %)) (d k)))
        ncat  (fn [k] (count (get-in md [:categories k])))]
    {:N         (tc/row-count d)
     :y         (mapv long (d :interaction))
     :depth     (col :depth_ord_std)
     :autopilot (mapv double (d :autopilot_on))
     :speed     (col :speed_ord_std)
     :boatlen   (col :boat_length_ord_std)
     :distance  (col :distance_ord_std)
     :wind      (col :wind_ord_std)
     :sea       (col :sea_state_ord_std)
     :n_sailing (ncat :sailing_mode) :sailing  (idx1 :sailing_mode_idx)
     :n_antifoul (ncat :antifoul)    :antifoul (idx1 :antifoul_idx)
     :n_hull    (ncat :hull_colour)  :hull     (idx1 :hull_colour_idx)
     :n_rudder  (ncat :rudder)       :rudder   (idx1 :rudder_idx)}))

(def layout
  "Output parameter order (matches refit_no_daylight.py)."
  ["alpha" "b_depth" "b_autopilot" "b_speed" "b_length" "b_distance" "b_wind"
   "b_sea" "s_0" "s_1" "s_2" "s_3" "s_4" "a_0" "a_1" "a_2" "a_3" "a_4" "a_5"
   "a_6" "a_7" "h_0" "h_1" "h_2" "r_0" "r_1" "r_2" "r_3" "r_4" "r_5"])

(def stan-cols
  "CmdStan output column for each layout slot (vector params use .k, 1-based)."
  ["alpha" "beta_depth" "beta_autopilot" "beta_speed" "beta_length"
   "beta_distance" "beta_wind" "beta_sea"
   "alpha_sailing.1" "alpha_sailing.2" "alpha_sailing.3" "alpha_sailing.4"
   "alpha_sailing.5"
   "alpha_antifoul.1" "alpha_antifoul.2" "alpha_antifoul.3" "alpha_antifoul.4"
   "alpha_antifoul.5" "alpha_antifoul.6" "alpha_antifoul.7" "alpha_antifoul.8"
   "alpha_hull.1" "alpha_hull.2" "alpha_hull.3"
   "alpha_rudder.1" "alpha_rudder.2" "alpha_rudder.3" "alpha_rudder.4"
   "alpha_rudder.5" "alpha_rudder.6"])

(defn thin-to
  "Evenly thin `n` indices from a draws dataset of `total` rows."
  [total n]
  (let [step (/ (double total) n)]
    (mapv #(int (Math/floor (* % step))) (range n))))

(defn draws->rows
  "Project pooled Stan draws into [n x 30] vectors, rounded to 4 dp, thinned to
   `n-out` rows."
  [draws n-out]
  (let [cols (mapv #(vec (draws %)) stan-cols)
        total (count (first cols))
        keep-idx (thin-to total n-out)]
    (mapv (fn [i] (mapv #(-> (nth % i) double (* 10000) Math/round (/ 10000.0)) cols))
          keep-idx)))

(defn run
  "Full pipeline: prepare -> fit -> export posterior_draws.json. Returns the
   output map (also written to `out-path`)."
  [{:keys [raw-path out-path n-out n-chains seed num-warmup num-samples]
    :or   {raw-path (config/cfg :paths :raw)
           out-path (config/cfg :paths :out-posterior)
           n-out (config/cfg :mcmc :n-out)
           n-chains (config/cfg :mcmc :n-chains)
           seed (config/cfg :mcmc :seed)
           num-warmup (config/cfg :mcmc :num-warmup)
           num-samples (config/cfg :mcmc :num-samples)}}]
  (let [{:keys [data metadata]} (prep/prepare (util/read-json raw-path))
        d      (complete-cases data)
        draws  (stan/sample "stan/m3.stan" (stan-data d metadata)
                            {:n-chains n-chains :seed seed
                             :num-warmup num-warmup :num-samples num-samples
                             :out-dir (config/cfg :paths :out-dir)})
        rows   (draws->rows draws n-out)
        n-int  (-> d (tc/select-rows #(= 1 (% :interaction))) tc/row-count)
        output {:n (count rows)
                :layout layout
                :categories (:categories metadata)
                :standardization (:standardization metadata)
                :sample_rate (/ (Math/round (* 1e6 (/ (double n-int) (tc/row-count d)))) 1e6)
                :draws rows}]
    (util/write-json out-path output)
    output))
