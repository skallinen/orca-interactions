# orca_clj — JVM/Clojure reproduction of the Bayesian orca analysis

A pure-JVM reproduction of the Python (`bayesian_orca/`) technical analysis:

- **Data prep** — [tablecloth](https://github.com/scicloj/tablecloth) (`orca.prepare`)
- **MCMC** — [CmdStan](https://mc-stan.org/) NUTS, driven from Clojure (`orca.stan`)
- **Solar/time-of-day** — `commons-suncalc` (Java) replacing Python's `astral`

The committed Python outputs are the validation oracle (never re-run Python):

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
