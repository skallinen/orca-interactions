// M4 (ladder rung 4) — extended model = M3 (no daylight) + moon + tide + cloud
// cover (33 params). Matches the methodology ladder (§4): "M4 + moon, tide,
// cloud cover ... showed no credible effects and slightly worse predictive
// performance" (see porting.md §6.2 for why this is the M4 contents). Fermi
// intercept prior, all slopes/offsets N(0, 0.5).
// Emits log_lik so orca.waic can compare it against M3.
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;

  vector[N] depth;
  vector[N] autopilot;
  vector[N] speed;
  vector[N] boatlen;
  vector[N] distance;
  vector[N] wind;
  vector[N] sea;
  vector[N] moon;
  vector[N] tide;
  vector[N] cloud;

  int<lower=1> n_sailing;
  array[N] int<lower=1> sailing;
  int<lower=1> n_antifoul;
  array[N] int<lower=1> antifoul;
  int<lower=1> n_hull;
  array[N] int<lower=1> hull;
  int<lower=1> n_rudder;
  array[N] int<lower=1> rudder;
}
parameters {
  real alpha;
  real beta_depth;
  real beta_autopilot;
  real beta_speed;
  real beta_length;
  real beta_distance;
  real beta_wind;
  real beta_sea;
  real beta_moon;
  real beta_tide;
  real beta_cloud;
  vector[n_sailing] alpha_sailing;
  vector[n_antifoul] alpha_antifoul;
  vector[n_hull] alpha_hull;
  vector[n_rudder] alpha_rudder;
}
model {
  vector[N] logit_p;

  alpha ~ normal(-3.5, 0.6);
  beta_depth ~ normal(0, 0.5);
  beta_autopilot ~ normal(0, 0.5);
  beta_speed ~ normal(0, 0.5);
  beta_length ~ normal(0, 0.5);
  beta_distance ~ normal(0, 0.5);
  beta_wind ~ normal(0, 0.5);
  beta_sea ~ normal(0, 0.5);
  beta_moon ~ normal(0, 0.5);
  beta_tide ~ normal(0, 0.5);
  beta_cloud ~ normal(0, 0.5);
  alpha_sailing ~ normal(0, 0.5);
  alpha_antifoul ~ normal(0, 0.5);
  alpha_hull ~ normal(0, 0.5);
  alpha_rudder ~ normal(0, 0.5);

  for (i in 1 : N) {
    logit_p[i] = alpha + beta_depth * depth[i] + beta_autopilot * autopilot[i]
                 + beta_speed * speed[i] + beta_length * boatlen[i]
                 + beta_distance * distance[i] + beta_wind * wind[i]
                 + beta_sea * sea[i] + beta_moon * moon[i] + beta_tide * tide[i]
                 + beta_cloud * cloud[i] + alpha_sailing[sailing[i]]
                 + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
                 + alpha_rudder[rudder[i]];
  }
  y ~ bernoulli_logit(logit_p);
}
generated quantities {
  vector[N] log_lik;
  for (i in 1 : N) {
    real lp = alpha + beta_depth * depth[i] + beta_autopilot * autopilot[i]
              + beta_speed * speed[i] + beta_length * boatlen[i]
              + beta_distance * distance[i] + beta_wind * wind[i]
              + beta_sea * sea[i] + beta_moon * moon[i] + beta_tide * tide[i]
              + beta_cloud * cloud[i] + alpha_sailing[sailing[i]]
              + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
              + alpha_rudder[rudder[i]];
    log_lik[i] = bernoulli_logit_lpmf(y[i] | lp);
  }
}
