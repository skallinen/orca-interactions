data {
  int<lower=0> N;
  int<lower=1> K;
  matrix[N, K] X;
  array[N] int<lower=0,upper=1> y;
}
parameters {
  real alpha;
  vector[K] beta;
}
model {
  alpha ~ normal(0, 5);
  beta ~ normal(0, 1);
  y ~ bernoulli_logit(alpha + X * beta);
}
