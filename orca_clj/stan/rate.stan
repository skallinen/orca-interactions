// Exposure-based day/night incident-rate model (methodology.html §7).
// y_* incidents observed against T_* yacht-hours of exposure.
data {
  int<lower=0> y_night;
  int<lower=0> y_day;
  real<lower=0> T_night;
  real<lower=0> T_day;
}
parameters {
  real<lower=0> lambda_night;
  real<lower=0> lambda_day;
}
model {
  lambda_night ~ exponential(50);
  lambda_day ~ exponential(50);
  y_night ~ poisson(lambda_night * T_night);
  y_day ~ poisson(lambda_day * T_day);
}
generated quantities {
  real rate_ratio = lambda_night / lambda_day;
}
