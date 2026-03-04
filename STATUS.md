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

### Where we ended

90 corpus tests passing out of ~140 tracked. 6 hand-written feature tests
(passthrough, basic_table, lpm_routing, ternary_acl, multi_table,
switch_action_run).

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

### Next up

Highest-impact features to unblock tests: const table entries (9), header
stack indexing (11), header unions (14), verify externs (5), register
read/write (2). These five would move ~41 tests from manual to passing
(47% → ~66%).
