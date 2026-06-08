data {
  int<lower=0> N;
  int<lower=1> M;
  array[N] int<lower=0,upper=1> y;
  matrix[N, M] Bsp;
}
parameters {
  real b0;
  vector[M] w;
  real<lower=0> tau;
}
model {
  b0 ~ normal(0, 5);
  tau ~ normal(0, 0.4) T[0, ];
  w ~ normal(0, tau);
  y ~ bernoulli_logit(b0 + Bsp * w);
}
