# 4ward — Agent Guide

**Always work in a dedicated git worktree — never modify the main tree
directly.** This keeps the main tree clean and allows parallel work without
conflicts. Create one with:

```sh
git worktree add ../4ward-<branch> -b <branch>
```

Clean up after merging:

```sh
cd /path/to/main/tree
git worktree remove ../4ward-<branch>
git worktree prune
```

## Understand ideal before settling for less

Before committing to any design or implementation, define what the ideal
solution looks like — unconstrained by schedule, legacy, or expedience.
You don't have to build the ideal, but you must understand it. A pragmatic
shortcut is a legitimate engineering choice; a shortcut you took because you
never considered the alternative is just a blind spot. Name the north star,
name what you're trading away, and name why.

## Test-driven development

**Write the test first.** The test is the spec — it defines the behavior
you want before you write the code. If you can't write a clear test, you
don't understand the problem yet. A failing test is the starting point for
every change, not an afterthought.

## Key design invariants — do not break these

1. **The proto IR uses names, not IDs.** All cross-references in `ir.proto` and
   `simulator.proto` use string names. Numeric IDs belong to p4info (the
   control-plane API) only.

2. **Every Expr carries a Type.** The `type` field (field number 100) on `Expr`
   is always populated by the p4c backend. The simulator must never infer types
   at runtime.

3. **The simulator is the source of truth for all data-plane state.** Table
   entries, counters, registers — all live in the Kotlin simulator. The
   P4Runtime server (`p4runtime/`) is a thin adapter that forwards requests;
   it holds no P4 state of its own.

4. **Correctness over performance.** If you are tempted to optimize something at
   the cost of readability or correctness, don't. This is a development and
   testing tool.

5. **Never fail silently.** Prefer compile-time failures (exhaustive `when`
   expressions, type system constraints) over runtime checks. When runtime
   checks are needed, fail loudly (`error()`, `require()`, gRPC
   `UNIMPLEMENTED`). Never let unhandled inputs fall through to a default path.

## P4 language notes

The authoritative source for language semantics is the
[P4₁₆ Language Specification](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html).
**When in doubt, consult the spec.** If the spec is ambiguous, follow p4c's
behavior and document the ambiguity with a comment citing the relevant spec
section. For v1model architecture semantics, the de facto spec is the
[BMv2 simple_switch documentation](https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md).

[jafingerhut/p4-guide](https://github.com/jafingerhut/p4-guide) is a
community knowledge base with worked examples and detailed write-ups for
many P4 features. Especially useful:
- [`v1model-special-ops/`](https://github.com/jafingerhut/p4-guide/tree/master/v1model-special-ops) —
  clone, resubmit, recirculate, and multicast examples with packet
  traces. Essential reference for v1model semantics the spec leaves
  underspecified.
- [`docs/p4-table-behaviors.md`](https://github.com/jafingerhut/p4-guide/blob/master/docs/p4-table-behaviors.md) —
  exhaustive catalog of table match/miss/default-action edge cases.

The IR is emitted after p4c's midend, so it reflects a simplified,
fully-resolved program.

## Repository map

```
docs/ARCHITECTURE.md         Design rationale. Read this first.
simulator/ir.proto           The behavioral IR. The core contract of the project.
simulator/simulator.proto    Shared types for simulator clients (P4Runtime, STF, tests).
simulator/*.kt               Kotlin simulator (the heart of 4ward).
p4c_backend/*.{h,cpp}        C++ p4c backend plugin (emits proto IR from P4 source).
p4runtime/*.kt               P4Runtime gRPC server (Kotlin).
cli/*.kt                     Standalone CLI (4ward compile / sim / run).
web/*.kt                     Web playground server and graph extractors (Kotlin).
examples/*.p4                Ready-to-run example programs.
examples/tutorial.t          CLI tutorial (also a cram regression test).
e2e_tests/stf/               STF test runner (drives the simulator subprocess).
e2e_tests/corpus/            Corpus-based STF test harness (bulk p4c test suite).
e2e_tests/trace_tree/        Golden trace-tree tests (proto-based, not STF).
e2e_tests/p4testgen/         p4testgen-generated STF tests (one target per P4 program).
e2e_tests/*/                 Per-feature STF tests (passthrough, basic_table, lpm, …).
designs/                     Design documents (architecture decisions, feature proposals).
docs/                        Project documentation.
docs/RELEASING.md            How to cut a release and publish to the BCR.
userdocs/                    User-facing documentation site (mkdocs).
tools/                       Developer scripts (format, lint, coverage, …).
```

Unit tests live alongside the source they test (`FooTest.kt` next to `Foo.kt`).

## Code style

Write self-explanatory code. Add a comment when the code deviates from the
obvious approach, works around a non-obvious constraint, or implements a
subtle P4 spec requirement. Include spec references (section numbers, GitHub
issues) where helpful. Do not add comments that merely restate the code.

If you take a shortcut or skip a corner case, note it in
[LIMITATIONS.md](docs/LIMITATIONS.md) with a `TODO` comment at the site.
Mark workarounds with a prominent `WORKAROUND` comment explaining what is
broken and what the code should look like once the upstream issue is fixed.

## Build and test

```sh
bazel build //...                              # build everything
bazel test //... --test_tag_filters=-heavy     # run tests (skip heavy ones)
bazel test //...                               # run ALL tests (CI does this)
./tools/format.sh                              # auto-format all files
./tools/lint.sh                                # lint (clang-tidy + detekt)
./tools/dev.sh help                            # show all developer commands
```

**Use `--test_tag_filters=-heavy` locally** to skip tests that spawn many
JVM processes. CI runs all tests including heavy ones.

**CI is fast and has a warm remote cache — often faster than a cold local
build.** Push early and use `gh run watch` to monitor results. Check CI logs
with `gh run view --log-failed`.

## Commits and pull requests

Focus commit messages on *why* the change is being made and what problem it
solves. Avoid restating what the diff already shows. Reference the STF test
being fixed where applicable.

Open PRs in draft mode (`gh pr create --draft`). Rebase onto `origin/main`
before submitting. Lead with the win — what changed for the project, how it
fits into the big picture. Be concise and punchy. Don't drown achievements
in low-level details; the diff already has those.

Before submitting:

- Run `./tools/format.sh` and `./tools/lint.sh`. Fix all warnings, even
  pre-existing ones.
- Proactively add unit tests for new simulator behavior.
- Check whether your change affects [LIMITATIONS.md](docs/LIMITATIONS.md) or
  [REFACTORING.md](docs/REFACTORING.md).
- **NEVER edit docs/STATUS.md.** It is maintained exclusively by the project
  owner.
- **The linter serves us, not the other way around.** When a rule doesn't fit
  the code's natural structure, adjust the threshold rather than contorting the
  code.
