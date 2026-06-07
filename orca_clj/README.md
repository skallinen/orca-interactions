# orca_clj — JVM/Clojure Bayesian orca analysis

A pure-JVM Bayesian analysis of orca–vessel interactions:

- **Data acquisition** — JDK `java.net.http` client over the CA orca-survey API (`orca.fetch`)
- **Data prep** — [tablecloth](https://github.com/scicloj/tablecloth) (`orca.prepare`)
- **MCMC** — [CmdStan](https://mc-stan.org/) NUTS, driven from Clojure (`orca.stan`)
- **Diagnostics** — rank-normalized split-R̂, bulk/tail ESS, ETI/HDI (`orca.diagnostics`)
- **Model comparison** — WAIC (`orca.waic`)
- **Solar/time-of-day** — `commons-suncalc` (Java) for solar position (`orca.timeofday`)
- **Stats / plots** — Apache Commons Math (`orca.stats`), XChart PNGs (`orca.plot`)

A set of committed reference artifacts is the validation oracle:

| Clojure output | Validated against |
|----------------|-------------------|
| `orca.prepare` modeling data + metadata | `bayesian_orca/data/modeling_data.csv`, `metadata.json` |
| `orca.model` posterior draws            | `blogpost/posterior_draws.json` |
| `orca.timeofday` rate ratio             | methodology.html (0.56, 89% CI 0.43–0.72) |

## Setup

Everything runs inside the repo's nix dev shell (`nix develop` / `nix-shell`),
which provides clojure, cmdstan, clang, gnumake, babashka.

CmdStan must be built against a **writable** copy of the store tree (the store
is read-only and its prebuilt precompiled header is compiler-mismatched on
Darwin). One-time:

```bash
SRC=$(dirname $(dirname $(readlink -f $(nix-shell -p cmdstan --run 'which stan'))))/opt/cmdstan
DST=$HOME/.cache/orca-cmdstan
cp -R "$SRC" "$DST" && chmod -R u+w "$DST"
rm -rf "$DST"/stan/src/stan/model/model_header.hpp.gch   # rebuild PCH with active clang
export CMDSTAN="$DST"
```

The first model compile rebuilds the PCH (~3–5 min); later compiles are fast.

## REPL-driven workflow

```bash
# start a socket REPL inside the tooling shell (keeps stdin open so it persists)
nix-shell -p clojure jdk21 cmdstan clang gnumake babashka \
  --run 'tail -f /dev/null | clojure -M:repl' &

# send forms to it
echo "(require 'orca.prepare :reload)" | bb re.clj
```

## Data acquisition (`orca.fetch`)

`orca.fetch` is an API client, not a scraper: it pulls the report list, then
fetches every detailed incident / uneventful-passage report concurrently (bounded thread pool, with
retries), flattens each `{:Q :A}` response, and writes `reportlist.json`,
`all_reports_detailed.json`, and the per-type CSVs into `:api :out-dir`
(`../orca_data`). The committed `orca_data/` snapshot is the analysis input, so
a re-fetch is only needed to refresh from the live (slow) API:

```clojure
(require 'orca.fetch)
(orca.fetch/fetch-reports)          ; ~5–10 min against the live CA API
```

Endpoints, headers, worker count, retries and CSV column orderings live under
`:api` in `resources/config.edn`.

## Analysis namespaces

| Namespace | What it does |
|-----------|--------------|
| `orca.prepare`    | Build the modeling-ready dataset + metadata from the raw reports |
| `orca.model`      | Final calculator refit — relaxed-prior no-daylight M3, 500-draw export |
| `orca.models`     | The M0–M4 ladder: builders, fits, summaries, prior-predictive, parameter recovery, PPC, risk scenarios |
| `orca.waic`       | WAIC + model comparison from pointwise `log_lik` |
| `orca.results`    | Interpretation: slope/category tables, contrasts, risk scenarios, coefficient/effect plots |
| `orca.sensitivity`| Prior sensitivity sweep over four intercept priors |
| `orca.findings`   | Validations of the two headline findings (black antifoul, night/day) |
| `orca.encoding`   | Time-of-day encoding studies (the justification for excluding time of day) |
| `orca.timeofday`  | Exposure-based night/day Poisson rate ratio (0.56 [0.43, 0.72]) |
| `orca.eda`        | Stratified distribution comparison + figures |
| `orca.dag`        | Causal DAG, adjustment sets, and caveats |
| `orca.diagnostics`| Rank-normalized split-R̂, bulk/tail ESS, ETI/HDI |
| `orca.stats`      | χ², Fisher's exact, two-sample t-test |
| `orca.plot`       | Headless XChart PNG helpers |
| `orca.validate`   | Checks the Clojure outputs against the committed reference artifacts |
| `orca.core`       | `run-all` — the whole pipeline end to end |

### Method notes

- **Primary model M3** excludes any time-of-day predictor. Time of day is
  handled separately as an exposure-based Poisson rate ratio (`orca.timeofday`),
  because the reports record incident times as a single period while uneventful
  passages record every period covered — no binary day/night encoding is well
  posed (`orca.encoding` shows the sensitivity).
- **M4** = M3 + moon + tide + cloud cover (33 params).
- **Model comparison is WAIC** (`orca.waic`), validated on the M3-vs-M4
  ordering. M3 is preferred; M4 adds no credible effects.
- **Convergence gate:** R̂ < 1.01, ESS > 400, 0 divergences.
