# AGENTS.md — working agreements for `orca_clj`

Invariants and workflow for anyone (human or agent) working in this project.
Companion to `README.md` (setup); read both before starting.

## Stack

scicloj + plain Java libraries on the JVM:
tablecloth/dtype-next (data), CmdStan via `orca.stan` (MCMC), `commons-suncalc`
(solar), Apache Commons Math (stats/distributions), XChart (PNG plots).

## Hard invariants (do not violate)

1. **Never run the Clojure formatter on `.stan` files.**
   `standard-clojure-style` treats Stan as Clojure and *mangles* it (collapses
   blocks, moves `;`, inserts stray `} ;`). Only ever pass `src/` and `test/`
   `.clj` files to it. If a `.stan` file gets mangled: restore tracked ones with
   `git checkout stan/<f>.stan`, rewrite untracked ones, then recompile to
   confirm. Stan formatting is done by hand (CmdStan's own canonical layout).

2. **Never regenerate the committed *numeric* oracle.**
   `bayesian_orca/data/modeling_data.csv`, `metadata.json`,
   `blogpost/posterior_draws.json`, and `bayesian_orca/results/*.png` are the
   validation oracle the analysis is checked against. Treat them as read-only
   reference; new Clojure output is validated against them, never the reverse.

3. **The two blog posts are the source of truth for the *method*.**
   `blogpost/methodology.html` + `index.html` describe the current method and the
   modeling decisions. Where an implementation choice diverges from a naive
   reading it is deliberate; don't silently "fix" the science.

## Writing prose (blog, docs, docstrings, comments, commit messages)

Write plainly and directly. The prose here is human-edited; avoid the patterns
that read as machine-generated:

1. **Never use em dashes or en dashes as sentence punctuation.** Use a comma, a
   colon, parentheses, or two sentences, or rewrite. In HTML that means no
   `&mdash;`/`&ndash;` and no literal `—`/`–` as a break. A hyphen inside a range
   or compound (`20 - 40m`, `split-R̂`) is fine.
2. **No thesis-antithesis construction**: "not X, but Y", "it's not just X, it's
   Y", "not only ... but also". State the point directly.
3. **No rule-of-three padding** ("fast, simple, and correct") unless all three
   items carry weight.
4. **No filler openers or hedges**: "It's worth noting", "It's important to
   realise", "Note that", "Of course", "Simply put".
5. **No formulaic wrap-ups**: "In conclusion", "Overall", "In summary", "At the
   end of the day".
6. **No inflated diction**: delve, dive into, leverage, utilise, seamless,
   tapestry, landscape, realm, testament, boasts, unlock, "plays a crucial role".
7. **No connector chaining** (runs of "Moreover / Furthermore / Additionally")
   and **no sycophantic framing** ("Great question", "Let's ...").

Prefer concrete numbers and specifics; cut adjectives that don't change the
meaning. Idiomatic Clojure naming in code follows the other sections, not this
one.

## Clojure workflow (REPL-driven)

- A long-lived **socket REPL on port 5577** runs inside the nix tooling shell.
  Send forms with `echo '<forms>' | bb re.clj` (see `re.clj`). Output streams
  back until a `<<<DONE>>>` sentinel.
- **Evaluate the namespace after every edit** (`(require 'orca.x :reload)`) — it
  catches syntax errors and dependency problems immediately.
- **Restart the REPL after any `deps.edn` change.** The JVM caches its classpath
  at start; `add-libs` pulls jars for `require` but its dynamic classloader does
  **not** satisfy `:import`, so new Java deps need a full restart:
  ```bash
  pkill -f "clojure -M:repl"
  nix-shell -p clojure jdk21 cmdstan clang gnumake babashka \
    --run 'tail -f /dev/null | clojure -M:repl' &
  ```
- The socket REPL's classpath is `src:resources:<deps>` — **`test/` is not on
  it**. Run tests with `clojure -X:test` (separate JVM), not via `bb re.clj`.

## After each edit: format, lint, evaluate

1. **Format** (`.clj` only): `npx @chrisoakman/standard-clojure-style fix <files>`
   (a PostToolUse hook also auto-formats on save). Keep `check` clean.
2. **Lint**: `clj-kondo --lint <files>` — keep it at **0 warnings, 0 errors**.
   Known quirks: same-package imports the formatter collapses can exceed
   clj-kondo's 100-char line limit (see `orca.plot` workaround); name local
   bindings to avoid shadowing top-level vars.
3. **Evaluate** the namespace in the REPL.

## Tests

`clojure -X:test` (cognitect test-runner, `:test` alias). Pure functions are
unit-tested against hand-computed / known reference values. Heavy MCMC paths
must run end-to-end at least once whenever the code path they exercise changes.

## CmdStan notes

- `CMDSTAN` must point at a **writable copy** of the nix store tree (store is
  read-only; its prebuilt PCH is compiler-mismatched on Darwin). See `README.md`.
- `orca.stan/compile-model` runs `make -C $CMDSTAN <abs-model-path>`; `make` only
  recompiles when the `.stan` is newer than its executable. First compile rebuilds
  the PCH (~3–5 min); later compiles are fast.
- Every model `orca.waic` compares carries `generated quantities { vector[N]
  log_lik; }` so WAIC can consume pointwise log-likelihood (the ladder rungs
  m0–m4). Models outside WAIC — `m3_prior` (sensitivity), `rate` (Poisson
  exposure), `recovery` (parameter recovery) — legitimately omit it.

## Diagnostics & numerics conventions

- `orca.diagnostics` reproduces ArviZ to ~2 sig figs: rank-normalized folded
  split-R̂, bulk/tail ESS (Geyer), ETI, HDI. Convergence gate: **R̂ < 1.01,
  ESS > 400, 0 divergences.**
- Model comparison is **WAIC** (`orca.waic`), an intentional substitution for
  ArviZ's PSIS-LOO (simpler, no Pareto tail fit); validated on M3-vs-M4 ordering.
- `orca.stan/sample-chains` returns **per-chain** datasets (needed for R̂/ESS);
  `sample` pools them. Use `sample-chains` when diagnostics matter.
