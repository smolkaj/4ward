# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Upstream p4c backend

Land the 4ward backend in the p4c repository. Blocked on upstream review.
Once merged, switch `MODULE.bazel` from `smolkaj/p4c` fork to `p4lang/p4c`.

---

## Pinned dependencies inventory

Several deps use `git_override` with pinned commits. This is tracked here as
a reminder to periodically check for upstream updates:

- **bazel_clang_tidy** (`9e54bbb`): pinned before a commit (`c4d35e0`) that
  broke `-isystem` include ordering. Upstream bug never fixed — permanent
  workaround. Re-check if upstream ever resolves the issue.
- **behavioral_model** (`6c7c93e` + Bazel build patch): upstream uses Autotools;
  our patch adds native Bazel rules. The patch needs updating when we bump BMv2.
