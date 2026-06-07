// M0 — intercept-only base-rate model (ladder rung 0).
// Mirrors bayesian_orca/models.py:build_model_0. Informative Fermi prior on the
// intercept: logit(0.025) ~ -3.66 -> Normal(-3.5, 0.6). Emits pointwise log_lik
// for WAIC (orca.waic).
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;
}
parameters {
  real alpha;
}
model {
  alpha ~ normal(-3.5, 0.6);
  y ~ bernoulli_logit(alpha);
}
generated quantities {
  vector[N] log_lik;
  for (i in 1 : N) {
    log_lik[i] = bernoulli_logit_lpmf(y[i] | alpha);
  }
}
