// M3 (no daylight) with the intercept prior passed as DATA — drives the prior
// sensitivity analysis (orca.sensitivity).
//
// Time of day is not in the regression (methodology §4, §7): it is handled
// separately by the exposure-based Poisson rate ratio (orca.timeofday). So this
// is the no-daylight M3 (identical predictors to m3_build.stan); only the
// intercept prior mean/sd vary, supplied as alpha_mu / alpha_sd. All
// slopes/offsets keep N(0, 0.5). No generated quantities — the sensitivity
// study needs only the posterior of alpha and the slopes/offsets, not WAIC.
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;

  real alpha_mu;
  real<lower=0> alpha_sd;

  vector[N] depth;
  vector[N] autopilot;
  vector[N] speed;
  vector[N] boatlen;
  vector[N] distance;
  vector[N] wind;
  vector[N] sea;

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
  vector[n_sailing] alpha_sailing;
  vector[n_antifoul] alpha_antifoul;
  vector[n_hull] alpha_hull;
  vector[n_rudder] alpha_rudder;
}
model {
  vector[N] logit_p;

  alpha ~ normal(alpha_mu, alpha_sd);
  beta_depth ~ normal(0, 0.5);
  beta_autopilot ~ normal(0, 0.5);
  beta_speed ~ normal(0, 0.5);
  beta_length ~ normal(0, 0.5);
  beta_distance ~ normal(0, 0.5);
  beta_wind ~ normal(0, 0.5);
  beta_sea ~ normal(0, 0.5);
  alpha_sailing ~ normal(0, 0.5);
  alpha_antifoul ~ normal(0, 0.5);
  alpha_hull ~ normal(0, 0.5);
  alpha_rudder ~ normal(0, 0.5);

  for (i in 1 : N) {
    logit_p[i] = alpha + beta_depth * depth[i] + beta_autopilot * autopilot[i]
                 + beta_speed * speed[i] + beta_length * boatlen[i]
                 + beta_distance * distance[i] + beta_wind * wind[i]
                 + beta_sea * sea[i] + alpha_sailing[sailing[i]]
                 + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
                 + alpha_rudder[rudder[i]];
  }
  y ~ bernoulli_logit(logit_p);
}
