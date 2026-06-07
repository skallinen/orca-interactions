# AGENTS.md — working agreements for `orca_clj`

Invariants and workflow for anyone (human or agent) working in this project.
Companion to `README.md` (setup) and `../porting.md` (the original build plan
and decision log). Read all three before starting.

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
   reading, the rationale is recorded in `porting.md §6`; don't silently "fix"
   the science.

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
must run end-to-end at least once per phase.

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

## Phase discipline & execution model (goal-driven, subagent-per-phase)

Follow `porting.md §8`'s phased sequence. This port runs under a standing
**goal**, not a hands-on interactive session:

- The **main context is the orchestrator** — it holds the plan, dispatches work,
  runs the reviews, and records progress in `orca-clj-porting-progress.md`. It does
  *not* itself write the bulk of each phase's code.
- Each phase is executed by a **fresh sequential subagent** (its own context
  window) so a phase's working memory never crowds the orchestrator. A phase
  subagent reads `porting.md` + this file + the progress memory, does the phase,
  gets reviewed (below), addresses the review, and reports back; the orchestrator
  then updates the progress memory and dispatches the next phase.
- This **replaces the old "clear the context after each phase" rule** — same
  intent (no stale phase state), but the orchestrator's context is preserved for
  continuity across phases.

## Code-review panel (after every phase, and once over the whole system)

After a phase's code is written, formatted, linted clean and tests are green, run
a **review panel before moving on**. Launch the reviewers as **separate
subagents**, each adopting (LARPing) one voice and reading the phase's diff
through their lens:

- **Rich Hickey** — simplicity over ease; is anything *complected*? data over
  syntax, values/immutability, naming, no incidental complexity or needless state.
- **Alex Miller** — idiomatic Clojure & core tooling; deps/aliases, `clojure.core`
  fit, library/API shape, practical correctness, no reinvented wheels.
- **Zachary Tellman** (*Elements of Clojure*) — naming precision, composition vs
  indirection, error/edge handling, robustness, where abstraction boundaries fall.
- **Daniel Slutsky** — scicloj/data-science idioms; tablecloth/dtype-next usage,
  numerical correctness, column semantics, would this read well in a noj notebook.
- **Richard McElreath** (*Statistical Rethinking*) — the *science*: priors, model
  criticism, causal/adjustment validity, diagnostics, whether the stats actually
  support what the code claims.

Collect the findings, decide what to act on (record any skip with a reason), apply
fixes, then re-lint and re-test. Only then is the phase done.

**Final whole-system pass:** after all phases are complete, run the **same panel
once more over the entire `orca_clj` system** (not a single diff) — architecture,
cross-namespace consistency, and the science end-to-end — and address what it
finds. This is `porting.md §8`'s **Phase F**.
