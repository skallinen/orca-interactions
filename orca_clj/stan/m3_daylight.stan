// M3 WITH daylight — the historical model-building M3 variant, used only by the
// night-encoding study (orca.encoding/test-night-encoding) to read off
// beta_daylight under two competing encodings of is_daytime (A vs B).
//
// This is m3_build.stan plus a beta_daylight * is_daytime term. The primary
// model (orca.model / stan/m3.stan) and the calculator EXCLUDE time of day: the
// encoding analysis (methodology §4, §7) showed the day/night signal was an
// artefact of the point-in-time vs time-span asymmetry between incident and
// uneventful reports, so daylight was removed from the regression and the night
// effect is reported separately by the exposure-based Poisson rate ratio
// (orca.timeofday). This file exists to demonstrate that removal, not to fit a
// headline model.
//
// Priors are the model-building Fermi set: intercept N(-3.5, 0.6), all
// slopes/offsets N(0, 0.5). Emits log_lik for WAIC (orca.waic).
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;

  vector[N] depth;
  vector[N] is_daytime;
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
  real beta_daylight;
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

  alpha ~ normal(-3.5, 0.6);
  beta_depth ~ normal(0, 0.5);
  beta_daylight ~ normal(0, 0.5);
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
    logit_p[i] = alpha + beta_depth * depth[i]
                 + beta_daylight * is_daytime[i] + beta_autopilot * autopilot[i]
                 + beta_speed * speed[i] + beta_length * boatlen[i]
                 + beta_distance * distance[i] + beta_wind * wind[i]
                 + beta_sea * sea[i] + alpha_sailing[sailing[i]]
                 + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
                 + alpha_rudder[rudder[i]];
  }
  y ~ bernoulli_logit(logit_p);
}
generated quantities {
  vector[N] log_lik;
  for (i in 1 : N) {
    real lp = alpha + beta_depth * depth[i]
              + beta_daylight * is_daytime[i] + beta_autopilot * autopilot[i]
              + beta_speed * speed[i] + beta_length * boatlen[i]
              + beta_distance * distance[i] + beta_wind * wind[i]
              + beta_sea * sea[i] + alpha_sailing[sailing[i]]
              + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
              + alpha_rudder[rudder[i]];
    log_lik[i] = bernoulli_logit_lpmf(y[i] | lp);
  }
}
