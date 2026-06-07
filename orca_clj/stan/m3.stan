// M3 (no daylight) — Bayesian logistic regression for orca interactions.
// Mirrors bayesian_orca/refit_no_daylight.py: same priors, same linear
// predictor. Category indices are 1-based (Stan convention).
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

  alpha ~ normal(-1, 1);
  beta_depth ~ normal(0, 1);
  beta_autopilot ~ normal(0, 1);
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
