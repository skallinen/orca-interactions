// M3 (model-building, ladder rung 3) — vessel + activity + environment.
// The PRIMARY model's predictor set: M2 plus depth, distance, wind, sea state
// (30 params). NO daylight — time of day was originally trialled here but
// removed after the encoding analysis (methodology §4, §7); the with-daylight
// historical variant lives in m3_daylight.stan for the encoding studies.
//
// This is the ladder/model-building M3: Fermi intercept prior N(-3.5, 0.6) and
// all slopes/offsets N(0, 0.5).
// It differs from the calculator's final refit (stan/m3.stan / orca.model),
// which relaxes to alpha~N(-1,1) with beta_depth,beta_autopilot~N(0,1). Emits
// log_lik so orca.waic can compare it against M4.
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

  alpha ~ normal(-3.5, 0.6);
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
generated quantities {
  vector[N] log_lik;
  for (i in 1 : N) {
    real lp = alpha + beta_depth * depth[i] + beta_autopilot * autopilot[i]
              + beta_speed * speed[i] + beta_length * boatlen[i]
              + beta_distance * distance[i] + beta_wind * wind[i]
              + beta_sea * sea[i] + alpha_sailing[sailing[i]]
              + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
              + alpha_rudder[rudder[i]];
    log_lik[i] = bernoulli_logit_lpmf(y[i] | lp);
  }
}
