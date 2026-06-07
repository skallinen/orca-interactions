// 4-rate time-of-day interaction model (methodology.html §7).
// Tests whether a risk factor's effect differs between day and night by
// fitting an incident rate per yacht-hour in four cells: factor present (p)
// vs absent (a), crossed with day (d) vs night (n).
data {
  int<lower=0> y_pd;
  int<lower=0> y_pn;
  int<lower=0> y_ad;
  int<lower=0> y_an;
  real<lower=0> T_pd;
  real<lower=0> T_pn;
  real<lower=0> T_ad;
  real<lower=0> T_an;
}
parameters {
  real<lower=0> lambda_pd;
  real<lower=0> lambda_pn;
  real<lower=0> lambda_ad;
  real<lower=0> lambda_an;
}
model {
  lambda_pd ~ exponential(50);
  lambda_pn ~ exponential(50);
  lambda_ad ~ exponential(50);
  lambda_an ~ exponential(50);
  y_pd ~ poisson(lambda_pd * T_pd);
  y_pn ~ poisson(lambda_pn * T_pn);
  y_ad ~ poisson(lambda_ad * T_ad);
  y_an ~ poisson(lambda_an * T_an);
}
generated quantities {
  real day_rr = lambda_pd / lambda_ad;
  real night_rr = lambda_pn / lambda_an;
  real interaction = night_rr / day_rr;
}
