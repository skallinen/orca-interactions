data {
  int<lower=0> N;
  int<lower=1> M;
  array[N] int<lower=0,upper=1> y;
  matrix[N, M] Bsp;
  vector[N] z;    // standardized log10 depth
  vector[N] z2;   // z^2 (peaked shelf/slope preference)
}
parameters {
  real b0;
  vector[M] w;
  real<lower=0> tau;
  real b_d1;
  real b_d2;
}
model {
  b0 ~ normal(0, 5);
  tau ~ normal(0, 0.4) T[0, ];
  w ~ normal(0, tau);
  b_d1 ~ normal(0, 0.5);
  b_d2 ~ normal(0, 0.5);
  y ~ bernoulli_logit(b0 + Bsp * w + b_d1 * z + b_d2 * z2);
}
