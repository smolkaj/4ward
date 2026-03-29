# 4ward — Agent Guide

**Always work in a dedicated git worktree — never modify the main tree
directly.** See [Worktrees](#worktrees) for setup and cleanup.

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
userdocs/                    User-facing documentation site (mkdocs).
tools/                       Developer scripts (format, lint, coverage, …).
```

Unit tests live alongside the source they test (`FooTest.kt` next to `Foo.kt`).

## Build and test

```sh
bazel build //...                              # build everything
bazel test //... --test_tag_filters=-heavy     # run tests (skip heavy ones)
bazel test //...                               # run ALL tests (CI does this)
ibazel build //...                             # rebuild on file changes (preferred)
./tools/format.sh                              # auto-format all files
./tools/lint.sh                                # lint (clang-tidy + detekt)
./tools/coverage.sh                            # code coverage (--html, --baseline, --diff)
./tools/dev.sh help                            # show all developer commands
```

**Use `--test_tag_filters=-heavy` locally.** The `heavy` tag marks tests
that spawn many JVM processes (p4testgen: 186 separate JVMs). Skipping them
keeps local test runs fast and avoids memory pressure. CI runs all tests
including heavy ones.

**Prefer `ibazel` over `bazel` for any work that spans multiple edit/build
cycles.** It keeps the Bazel server warm and rebuilds only affected targets,
catching errors within seconds rather than minutes.

All builds are hermetic. Do not install dependencies outside of Bazel.

## CI

**CI is fast and has a warm remote cache — often faster than a cold local
build.** Push early and use `gh run watch` to monitor results. Check CI logs
with `gh run view --log-failed`.

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

5. **Never fail silently.** Prefer compile-time failures (exhaustive `when`
   expressions, type system constraints) over runtime checks. When
   compile-time enforcement isn't feasible, fail loudly at runtime with an
   explicit error (e.g. gRPC `UNIMPLEMENTED`, `error()`, `require()`).
   Never let unhandled inputs fall through to a generic code path that
   happens to "work" — that's the worst kind of bug: it looks correct.

   Concretely: if a proto field, enum value, entity type, or RPC option is
   parsed but not implemented, reject it. Avoid `else` catch-alls that
   funnel unknown inputs into a default path. When adding validation for a
   new case, also implement (or explicitly reject) the corresponding
   behavior in all downstream layers — validation alone creates a false
   sense of completeness.

## Shortcuts and workarounds

If you take a shortcut or skip a corner case, note it in
[LIMITATIONS.md](docs/LIMITATIONS.md) and leave a `TODO(<scope>)` comment at
the site — e.g. `TODO(PR 3): update names to match actual simulator output`.

**Be loud about workarounds.** Mark workarounds with a prominent `WORKAROUND`
comment explaining: (1) what is broken, (2) the upstream issue or root cause,
and (3) what the code should look like once the bug is fixed.

## P4 language notes

The authoritative source for language semantics is the
[P4₁₆ Language Specification](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html).
**When in doubt, consult the spec.** If the spec is ambiguous, follow p4c's
behavior and document the ambiguity with a comment citing the relevant spec
section. For v1model architecture semantics, the de facto spec is the
[BMv2 simple_switch documentation](https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md).

The IR is emitted after p4c's midend, so it reflects a simplified program:
no generics, no abstract types, no P4_14 constructs.

## Architecture implementations

To add a new P4 architecture, follow the existing `V1ModelArchitecture.kt` as
the reference implementation. Register the new architecture in the `when`
expression inside `Simulator.loadPipeline()`.

## Commit messages

Focus on *why* the change is being made and what problem it solves. Avoid
restating what the diff already shows. Reference the STF test being fixed where
applicable.

## Code comments

Write self-explanatory code. Add a comment when the code deviates from the
obvious approach, works around a non-obvious constraint, or implements a
subtle P4 spec requirement. Include spec references (section numbers, GitHub
issues) where helpful. Do not add comments that merely restate the code.

## Pull requests

Open PRs in draft mode (`gh pr create --draft`). Rebase onto `origin/main`
before submitting. Lead with the win — what changed for the project, how it
fits into the big picture. Be concise and punchy. Don't drown achievements
in low-level details; the diff already has those.

## Before submitting a PR

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

## Worktrees

**Always work in a dedicated git worktree. Never make changes directly in the
main tree.** This keeps the main tree clean and allows parallel work without
conflicts. Create one with:

```sh
git worktree add ../4ward-<branch> -b <branch>
```

**Clean up worktrees after merging.** Each worktree gets its own Bazel output
base and server JVM. Stale worktrees accumulate idle JVMs that eat memory.
After a PR is merged, remove the worktree:

```sh
cd /path/to/main/tree
git worktree remove ../4ward-<branch>
git worktree prune
```
