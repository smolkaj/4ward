# 4ward — Agent Guide

## Repository map

```
docs/ARCHITECTURE.md         Design rationale. Read this first.
simulator/ir.proto           The behavioral IR. The core contract of the project.
simulator/simulator.proto    The simulator service protocol (stdin/stdout IPC).
simulator/*.kt               Kotlin simulator (the heart of 4ward).
p4c_backend/*.{h,cpp}        C++ p4c backend plugin (emits proto IR from P4 source).
p4runtime/*.kt               P4Runtime gRPC server (Kotlin).
e2e_tests/stf/               STF test runner (drives the simulator subprocess).
e2e_tests/corpus/            Corpus-based STF test harness (bulk p4c test suite).
e2e_tests/trace_tree/        Golden trace-tree tests (proto-based, not STF).
e2e_tests/p4testgen/         p4testgen-generated STF tests (one target per P4 program).
e2e_tests/*/                 Per-feature STF tests (passthrough, basic_table, lpm, …).
docs/                        Project documentation.
tools/                       Developer scripts (format, lint, coverage, …).
```

Unit tests live alongside the source they test (`FooTest.kt` next to `Foo.kt`).

## Build and test

```sh
bazel build //...          # build everything
bazel test //...           # run all tests
ibazel build //...         # rebuild automatically on file changes (preferred for interactive work)
./tools/format.sh          # auto-format all files (clang-format + buildifier + ktfmt)
./tools/lint.sh            # lint all files (clang-tidy for C++, detekt for Kotlin)
./tools/coverage.sh        # collect code coverage (see --html, --baseline, --diff)
./tools/diff-coverage.sh   # incremental coverage from a diff + LCOV file
./tools/dev.sh help        # show all developer commands
```

**Prefer `ibazel` over `bazel` for any work that spans multiple edit/build
cycles.** It keeps the Bazel server warm and rebuilds only affected targets,
catching errors within seconds rather than minutes.

All builds are hermetic. Do not install dependencies outside of Bazel.

## CI

CI runs on every push and PR via GitHub Actions (`.github/workflows/ci.yml`):
formatting, clang-tidy, build+test (Ubuntu + macOS), and coverage.

On PRs, a coverage report is published to GitHub Pages and linked from a bot
comment that shows both absolute and incremental (diff) coverage. Reports are
browsable at `https://smolkaj.github.io/4ward/pr/<number>/`.

**CI is fast and has a warm remote cache — often faster than a cold local
build.** When iterating on a fix, push early and use `gh run watch` to monitor
results rather than waiting for a full local rebuild. Check CI logs with
`gh run view --log-failed` to diagnose failures without pulling logs locally.

## Key design invariants — do not break these

1. **The proto IR uses names, not IDs.** All cross-references in `ir.proto` and
   `simulator.proto` use string names. Numeric IDs belong to p4info (the
   control-plane API) only. Do not introduce ID-based cross-references into the
   IR.

2. **Every Expr carries a Type.** The `type` field (field number 100) on `Expr`
   is always populated by the p4c backend. The simulator must never infer types
   at runtime. Do not remove this field or make it optional.

3. **The simulator is the source of truth for all data-plane state.** Table
   entries, counters, registers — all live in the Kotlin simulator. The
   P4Runtime server (`p4runtime/`) is a thin adapter that forwards requests;
   it holds no P4 state of its own.

4. **Correctness over performance.** If you are tempted to optimize something at
   the cost of readability or correctness, don't. This is a development and
   testing tool.

## Style

Style is enforced by `./tools/format.sh` (formatting) and `./tools/lint.sh` (linting). Run
both before completing a task. Fix all warnings.

**Proto conventions**: field names in `snake_case`, enum values in
`UPPER_SNAKE_CASE` with a type prefix (e.g. `STAGE_KIND_UNSPECIFIED`). Never
remove or renumber existing fields; add new ones instead.

## Making a test pass

Do not add features that are not exercised by an STF test in `e2e_tests/`.
Find the relevant failing test, implement the missing feature in `simulator/`,
and confirm no other tests regress: `bazel test //...`.

If you take a shortcut or skip a corner case to make progress, note it in
[LIMITATIONS.md](docs/LIMITATIONS.md) so it doesn't get forgotten, and leave a
`TODO(<scope>)` comment at the site — e.g. `TODO(PR 3): update names to match
actual simulator output`. The scope should identify the task, PR, or issue that
will resolve it. This applies to any code that is intentionally incomplete:
shortcuts, known limitations, placeholder/approximate data, stub
implementations.

## P4 language notes

4ward is a spec-compliant reference implementation. The authoritative source
for language semantics is the
[P4₁₆ Language Specification](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html).
**When in doubt, consult the spec.** If the spec is ambiguous, follow p4c's
behaviour and document the ambiguity with a comment citing the relevant spec
section.

Key points:

- P4_16 is the target language. P4_14 is not supported.
- The IR is emitted after p4c's midend, so it reflects a simplified program:
  no generics, no abstract types, no P4_14 constructs.
- Arithmetic on `bit<N>` types is unsigned and truncates on overflow (wraps
  modulo 2^N). Arithmetic on `int<N>` is two's-complement. Saturation
  arithmetic uses `|+|` and `|-|` operators.
- `varbit<N>` (variable-length headers, used in IPv4 options) has basic support
  in the simulator.

## Architecture implementations

To add a new P4 architecture, follow the existing `V1ModelArchitecture.kt` as
the reference implementation. Register the new architecture in the `when`
expression inside `Simulator.handleLoadPipeline()`.

## Proto changes

Proto changes affect both the p4c backend (C++) and the simulator (Kotlin).
Update both sides and make sure the relevant STF tests still pass.

## Pull request descriptions

Lead with the win — what changed for the project, how it fits into the big
picture. Be concise and punchy. Don't drown achievements in low-level details;
the diff already has those.

## Before submitting a PR

Consider whether your change affects any documentation:

- **[LIMITATIONS.md](docs/LIMITATIONS.md)** — did you add a shortcut, discover a
  gap, or resolve an existing limitation?
- **[REFACTORING.md](docs/REFACTORING.md)** — did you notice tech debt worth
  tracking, or complete an item already listed?

## Worktrees

**Always work in a dedicated git worktree. Never make changes directly in the
main tree.** This keeps the main tree clean and allows parallel work without
conflicts. Create one with:

```sh
git worktree add ../4ward-<branch> -b <branch>
```

Rebase and squash when merging back to main.

## Local development

When running multiple agents in parallel (each in its own worktree), Bazel can
saturate all CPU cores and cause heavy swapping because each worktree gets its
own output base — recompiling p4c and Z3 from scratch every time.

Add the following to your **`~/.bazelrc`** (not the repo `.bazelrc`) to fix
this:

```
# Shared disk cache across worktrees — avoids recompiling p4c/Z3 in every
# worktree. Grows without bounds; wipe with: rm -rf ~/.cache/bazel-disk
build --disk_cache=/absolute/path/to/.cache/bazel-disk

# Cap resources to keep the machine responsive when multiple agents build in
# parallel. Tune to your machine (these values suit a MacBook Air M-series).
# Only applied when explicitly opted in via: bazel build --config=throttle
build:throttle --local_cpu_resources=4
build:throttle --local_ram_resources=8192
```

Notes:
- Use an **absolute path** for `--disk_cache` (not `~`), since tilde in Bazel
  output confuses agents into thinking it's an error.
- Resource caps are behind `--config=throttle` so they don't affect normal
  builds, `format.sh`, or `lint.sh`. Opt in when running multiple agents:
  `bazel build --config=throttle //...`.
- These settings are intentionally **not** checked into the repo `.bazelrc`:
  `--disk_cache` has no garbage collection (would balloon CI), and resource
  limits are machine-specific.
