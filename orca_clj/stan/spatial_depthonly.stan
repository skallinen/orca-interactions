data {
  int<lower=0> N;
  array[N] int<lower=0,upper=1> y;
  vector[N] z;    // standardized log10 depth
  vector[N] z2;   // z^2 (peaked shelf/slope preference)
}
parameters {
  real b0;
  real b_d1;
  real b_d2;
}
model {
  b0 ~ normal(0, 5);
  b_d1 ~ normal(0, 0.5);
  b_d2 ~ normal(0, 0.5);
  y ~ bernoulli_logit(b0 + b_d1 * z + b_d2 * z2);
}
