# 4ward — Agent Guide

Welcome, AI agent! This guide gives you the context you need to work effectively
in the 4ward codebase. Read it before making changes.

## Repository map

```
simulator/ir.proto           The behavioral IR. The core contract of the project.
simulator/simulator.proto    The simulator service protocol (stdin/stdout IPC).
simulator/*.kt               Kotlin simulator (the heart of 4ward).
simulator/BUILD.bazel        Proto + Kotlin build targets for the simulator.
p4c_backend/*.{h,cpp}        C++ p4c backend plugin (emits proto IR from P4 source).
p4c_backend/BUILD.bazel      C++ build targets.
e2e_tests/stf/Runner.kt      STF test runner (drives the simulator subprocess).
e2e_tests/passthrough/       Walking-skeleton end-to-end test.
ARCHITECTURE.md              Design rationale. Read this first.
```

Unit tests live alongside the source they test:
- Kotlin: `FooTest.kt` next to `Foo.kt` in the same directory.
- C++: `foo_test.cc` next to `foo.h/foo.cc` in the same directory.

## Build and test

```sh
bazel build //...          # build everything
bazel test //...           # run all tests
./format.sh                # auto-format all files (clang-format + buildifier + ktfmt)
./lint.sh                  # lint all files (clang-tidy for C++, detekt for Kotlin)
./coverage.sh              # collect code coverage (LCOV + optional HTML report)
./dev.sh help              # show all developer commands
```

All builds are hermetic. Do not install dependencies outside of Bazel.

## CI

CI runs on every push and PR via GitHub Actions (`.github/workflows/ci.yml`):
formatting, clang-tidy, build+test (Ubuntu + macOS), and coverage.

On PRs, a coverage report is published to GitHub Pages and linked from a bot
comment. Reports are browsable at
`https://smolkaj.github.io/4ward/pr/<number>/`.

## Key design invariants — do not break these

1. **The proto IR uses names, not IDs.** All cross-references in `ir.proto` and
   `simulator.proto` use string names. Numeric IDs belong to p4info (the
   control-plane API) only. Do not introduce ID-based cross-references into the
   IR.

2. **Every Expr carries a Type.** The `type` field (field number 100) on `Expr`
   is always populated by the p4c backend. The simulator must never infer types
   at runtime. Do not remove this field or make it optional.

3. **The simulator is the source of truth for all data-plane state.** Table
   entries, counters, registers — all live in the Kotlin simulator. The future
   Go P4Runtime server is a thin adapter that forwards requests; it holds no
   P4 state of its own.

4. **Correctness over performance.** If you are tempted to optimize something at
   the cost of readability or correctness, don't. This is a development and
   testing tool.

## Style guides

- **Kotlin**: follow the [Google Kotlin style guide](https://google.github.io/styleguide/kotlinguide.html).
  4-space indentation, 100-character line limit, `lowerCamelCase` for functions
  and properties, `UpperCamelCase` for classes, `UPPER_SNAKE_CASE` for constants.
  Enforced by ktfmt (`./format.sh`) and detekt (`./lint.sh`).
- **C++**: follow the [Google C++ style guide](https://google.github.io/styleguide/cppguide.html).
  C++20, enforced by clang-format (`./format.sh`) and clang-tidy (`./lint.sh`,
  see `.clang-tidy` at the repo root).
- **Proto**: field names in `snake_case`, enum values in `UPPER_SNAKE_CASE` with
  a type prefix (e.g. `STAGE_KIND_UNSPECIFIED`). Comments on every non-obvious
  field.
- **BUILD files**: enforced by buildifier (`./format.sh`).

## Kotlin packages

- Simulator source: `fourward.simulator`
- Proto-generated (IR): `fourward.ir.v1`
- Proto-generated (simulator protocol): `fourward.sim.v1`
- E2E test runner: `fourward.e2e`
- E2E tests: `fourward.e2e.<test-name>`

## Making a test pass

The recommended way to add a feature is:

1. Find a failing STF test in `e2e_tests/` that exercises the feature.
2. Run it: `bazel test //e2e_tests/<test>`.
3. Read the failure. It will tell you what the simulator returned vs. what was
   expected.
4. Implement the missing feature in `simulator/`.
5. Confirm the test passes and no other tests regress: `bazel test //...`.

Do not add features that are not exercised by a test.

## P4 language notes

- P4_16 is the target language. P4_14 is not supported.
- The IR is emitted after p4c's midend, so it reflects a simplified program:
  no generics, no abstract types, no P4_14 constructs.
- Arithmetic on `bit<N>` types is unsigned and truncates on overflow (wraps
  modulo 2^N). Arithmetic on `int<N>` is two's-complement. Saturation
  arithmetic uses `|+|` and `|-|` operators.
- `varbit<N>` (variable-length headers, used in IPv4 options) is a stretch goal;
  do not implement it unless specifically asked.

## Architecture implementations

Adding a new P4 architecture (e.g. PSA):

1. Add a new `Architecture` implementation in `simulator/` (e.g. `PsaArchitecture.kt`).
2. Register it in `Simulator.kt`.
3. Add `--arch psa` support to the p4c backend.
4. Add STF tests in `e2e_tests/` that exercise PSA-specific behaviour.

## Proto changes

Proto changes affect both the p4c backend (C++) and the simulator (Kotlin). When
changing `ir.proto` or `simulator.proto`:

1. Update the `.proto` file.
2. Update the p4c backend to emit the new fields.
3. Update the Kotlin simulator to consume them.
4. Make sure the relevant STF tests still pass.

Never remove or renumber existing fields; add new ones instead.
