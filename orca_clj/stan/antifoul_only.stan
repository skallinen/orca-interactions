// Single-predictor antifoul model (orca.findings/validate-antifoul).
// logit P(interaction) = alpha + alpha_antifoul[k]. A deliberately minimal,
// no-controls model used only to validate the raw antifoul signal: does the
// Black > Coppercoat contrast survive in isolation? Priors: alpha ~ N(0, 1),
// alpha_antifoul ~ N(0, 0.5). Emits log_lik for WAIC (orca.waic).
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;

  int<lower=1> n_antifoul;
  array[N] int<lower=1> antifoul;
}
parameters {
  real alpha;
  vector[n_antifoul] alpha_antifoul;
}
model {
  vector[N] logit_p;

  alpha ~ normal(0, 1);
  alpha_antifoul ~ normal(0, 0.5);

  for (i in 1 : N) {
    logit_p[i] = alpha + alpha_antifoul[antifoul[i]];
  }
  y ~ bernoulli_logit(logit_p);
}
generated quantities {
  vector[N] log_lik;
  for (i in 1 : N) {
    log_lik[i] = bernoulli_logit_lpmf(y[i] | alpha + alpha_antifoul[antifoul[i]]);
  }
}
