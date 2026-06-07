// Parameter-recovery model (orca.models/parameter-recovery): a 3-parameter
// logistic regression used to confirm the sampler recovers known a/b from
// simulated data (methodology §4; bayesian_orca/models.py:parameter_recovery_test).
data {
  int<lower=0> N;
  array[N] int<lower=0, upper=1> y;
  vector[N] speed;
  vector[N] depth;
}
parameters {
  real alpha;
  real beta_speed;
  real beta_depth;
}
model {
  alpha ~ normal(-3.5, 0.6);
  beta_speed ~ normal(0, 0.5);
  beta_depth ~ normal(0, 0.5);
  y ~ bernoulli_logit(alpha + beta_speed * speed + beta_depth * depth);
}
