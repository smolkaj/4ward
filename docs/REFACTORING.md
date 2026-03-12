# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Upstream p4c build targets

The `smolkaj/p4c` fork adds two targets not in upstream `p4lang/p4c`:
`//:core_p4` (filegroup anchor for the `p4include/` path in genrules) and
`//testdata/p4_16_samples:*` (`exports_files` for corpus/BMv2 diff tests).
Both are only used by `e2e_tests/` — non-test code uses `//:p4include`,
`//:lib`, etc. which exist in BCR p4c. This is not a BCR blocker for
downstream consumers, but upstreaming these targets would let us drop the
`git_override` entirely.

---

## Pinned dependencies inventory

Three deps use `git_override` with pinned commits. `behavioral_model` and
`bazel_clang_tidy` are dev-only (`dev_dependency = True`) and invisible to
BCR consumers.

- **p4c** (`smolkaj/p4c` fork, `f36edeb`): adds `//:core_p4` and testdata
  `exports_files`. See "Upstream p4c build targets" above.
- **bazel_clang_tidy** (`9e54bbb`): pinned before a commit (`c4d35e0`) that
  broke `-isystem` include ordering. Upstream bug never fixed — permanent
  workaround. Re-check if upstream ever resolves the issue.
- **behavioral_model** (`6c7c93e` + Bazel build patch): upstream uses Autotools;
  our patch adds native Bazel rules. The patch needs updating when we bump BMv2.
