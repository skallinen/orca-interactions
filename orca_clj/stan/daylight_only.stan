// Single-predictor daylight model (orca.findings/validate-daylight).
// logit P(interaction) = alpha + beta_day * is_daytime. A minimal, no-controls
// model used only to validate the raw night/day signal in isolation and to read
// off the implied P(interaction | night) and P(interaction | day). Priors:
// alpha ~ N(0, 1), beta_day ~ N(0, 1). Emits log_lik for WAIC (orca.waic).
//
// Note: the primary regression M3 (orca.model) excludes time of day; the night
// effect is reported separately by the exposure-based Poisson rate ratio
// (orca.timeofday). This single-predictor fit is a finding-validation check,
// not the headline model.
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;
  vector[N] is_daytime;
}
parameters {
  real alpha;
  real beta_day;
}
model {
  alpha ~ normal(0, 1);
  beta_day ~ normal(0, 1);
  y ~ bernoulli_logit(alpha + beta_day * is_daytime);
}
generated quantities {
  vector[N] log_lik;
  for (i in 1 : N) {
    log_lik[i] = bernoulli_logit_lpmf(y[i] | alpha + beta_day * is_daytime[i]);
  }
}
