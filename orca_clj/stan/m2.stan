// M2 — vessel + activity (ladder rung 2).
// M1 plus sailing mode (index), speed (ordinal slope), and autopilot (binary
// slope). Fermi intercept prior, slope/index priors N(0, 0.5). Emits log_lik
// for WAIC.
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;

  vector[N] boatlen;
  vector[N] speed;
  vector[N] autopilot;

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
  real beta_length;
  real beta_speed;
  real beta_autopilot;
  vector[n_sailing] alpha_sailing;
  vector[n_antifoul] alpha_antifoul;
  vector[n_hull] alpha_hull;
  vector[n_rudder] alpha_rudder;
}
model {
  vector[N] logit_p;

  alpha ~ normal(-3.5, 0.6);
  beta_length ~ normal(0, 0.5);
  beta_speed ~ normal(0, 0.5);
  beta_autopilot ~ normal(0, 0.5);
  alpha_sailing ~ normal(0, 0.5);
  alpha_antifoul ~ normal(0, 0.5);
  alpha_hull ~ normal(0, 0.5);
  alpha_rudder ~ normal(0, 0.5);

  for (i in 1 : N) {
    logit_p[i] = alpha + beta_length * boatlen[i] + beta_speed * speed[i]
                 + beta_autopilot * autopilot[i] + alpha_sailing[sailing[i]]
                 + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
                 + alpha_rudder[rudder[i]];
  }
  y ~ bernoulli_logit(logit_p);
}
generated quantities {
  vector[N] log_lik;
  for (i in 1 : N) {
    real lp = alpha + beta_length * boatlen[i] + beta_speed * speed[i]
              + beta_autopilot * autopilot[i] + alpha_sailing[sailing[i]]
              + alpha_antifoul[antifoul[i]] + alpha_hull[hull[i]]
              + alpha_rudder[rudder[i]];
    log_lik[i] = bernoulli_logit_lpmf(y[i] | lp);
  }
}
