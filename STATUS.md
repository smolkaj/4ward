# 4ward — Status

## 2026-03-03

**Day one.** Stood up the project end-to-end: proto IR, p4c backend, Kotlin
simulator, STF test runner, CI pipeline, coverage reporting.

### What landed (PRs #1–#38)

- **Simulator core**: interpreter with v1model architecture support — tables
  (exact, LPM, ternary), header manipulation, control flow (exit, switch,
  nested tables), type casting, side effects, mux operator,
  `table.apply().hit/.miss/.action_run`.
- **p4c backend**: C++ plugin emitting proto IR from P4 source. Three
  quick-wins (#27) jumped corpus pass rate from 26 to 90 tests: `**` wildcard
  match, `standard_metadata_t` initialization, and `IR::ArrayIndex`.
- **STF test infrastructure**: parameterized corpus harness batching 90+ tests
  into a single JVM (#30), p4testgen integration for automated path-covering
  tests (#36).
- **CI/CD**: GitHub Actions with formatting, clang-tidy, build+test (Ubuntu +
  macOS), JaCoCo coverage with differential reports on PRs, BuildBuddy remote
  cache, self-hosted GitHub Pages coverage reports.
- **Developer tooling**: `format.sh`, `lint.sh`, `coverage.sh`,
  `diff-coverage.sh`, `dev.sh` runner, parallel-worktree `~/.bazelrc` docs.

### Where are we? Where are we going?

90 corpus tests passing out of ~140 tracked. 6 hand-written feature tests
(passthrough, basic_table, lpm_routing, ternary_acl, multi_table,
switch_action_run). ~50 tests remain unclassified — need to test each one and
sort into passing or failing.

## 2026-03-04

**Corpus expansion.** Classified every remaining p4c STF test (PRs #39–#86).

### What landed

- Tested and categorized all 77 previously-untracked p4c STF tests.
- 8 new tests pass (101 total in CI).
- 116 failing tests sorted into manual suites with inline error annotations.

### Corpus snapshot: 217 tests total

| Suite | Count | Status |
|---|---|---|
| v1model (CI) | 101 | passing |
| Skeleton includes | 16 | manual — missing Bazel targets |
| PSA architecture | 25 | manual — unsupported arch |
| Lookahead/advance | 6 | manual — p4c backend limitation |
| Other failures | 66 | manual — various simulator gaps |
| Slow compile | 1 | manual — 10+ min compile |
| Unsupported arch | 1 | manual — non-v1model/PSA |

### Top failure categories

| Missing feature | Blocked tests |
|---|---|
| Header unions (UnitVal) | 14 |
| Header stack indexing | 11 |
| Const table entries | 9 |
| Lookahead/advance | 6 |
| verify/verify_checksum | 5 |
| Payload mismatches | 6 |
| STF parser edge cases | 3 |
| Integer literal w/o bit type | 3 |
| Expression kind: type | 3 |
| Register read/write | 2 |

### Where are we? Where are we going?

**217 p4c STF tests tracked, 101 passing in CI (~47%).**

The passing tests cover the core v1model pipeline well: table lookups
(exact/LPM/ternary), header manipulation, control flow (exit, switch, nested
tables), type casting, side effects, and mux operations. Plus 6 hand-written
feature tests (passthrough, basic_table, lpm_routing, ternary_acl,
multi_table, switch_action_run).

Failing tests (116) break down into clear feature clusters:

| Missing feature | Blocked tests | Effort |
|---|---|---|
| **PSA architecture** | 25 | Large (new architecture impl) |
| **Skeleton .p4 Bazel targets** | 16 | Build plumbing, not simulator work |
| **Header union support** | 14 | UnitVal field access + casting |
| **Header stack indexing** | 11 | Field access on HeaderStackVal |
| **Const table entries** | 9 | Populating tables from IR at load time |
| **Lookahead / advance** | 6 | p4c backend limitation |
| **verify/verify_checksum externs** | 5 | Extern implementation |
| **Payload mismatches** | 6 | Various correctness bugs |
| **STF parser issues** | 3 | NumberFormatException on edge-case STF syntax |
| **Integer literal w/o bit type** | 3 | IR/interpreter gap |
| **Unhandled expression kinds** | 3 | type expressions |
| **Register read/write** | 2 | Extern implementation |
| **Other** | ~13 | Assorted (unknown tables, missing packets, etc.) |

If the goal is to maximize passing corpus tests with least effort:

1. **Const table entries** (9 tests) — probably straightforward: populate table
   entries from the IR's `const_default_action`/`entries` fields during
   LoadPipeline
2. **Header stack indexing** (11 tests) — handle array-index access on
   HeaderStackVal in the interpreter
3. **Header unions** (14 tests) — support union types in the simulator's value
   model
4. **verify/verify_checksum externs** (5 tests) — implement the extern calls
5. **Register read/write** (2 tests) — implement register extern methods

Items 1–5 would potentially move **41 tests** from manual to passing, bringing
the pass rate from ~47% to ~66%.

The bigger lifts (PSA architecture, skeleton Bazel targets, lookahead support)
are either architectural or toolchain work and probably belong on a separate
track.
