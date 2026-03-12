# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Shared trace helpers between V1Model and PSA architectures

**Files**: `simulator/V1ModelArchitecture.kt`, `simulator/PSAArchitecture.kt`

**Problem**: Both architecture implementations have byte-for-byte identical
private helpers: `buildDropTrace`, `buildOutputTrace`, `packetIngressEvent`,
`stageEvent`. These emerged naturally as PSA grew to match v1model's trace
structure, but the duplication is now clear.

**Fix**: Extract into a shared `TraceHelpers.kt` (or add to `Architecture.kt`
as package-level functions). `buildDropTrace` in PSA hard-codes
`DropReason.MARK_TO_DROP` while v1model parameterizes it — unify on the
parameterized form.

---

## `PacketContext.getEvents()` defensive copy

**Files**: `simulator/PacketContext.kt`

**Problem**: `getEvents()` calls `toList()` to defensively copy the internal
`MutableList<TraceEvent>`, but every caller immediately passes the result to
`addAllEvents()` on a proto builder and never mutates it. This produces an
unnecessary O(N) copy on every packet — including the common fast path with
no cloning or recirculation.

**Fix**: Return the internal list directly as `List<TraceEvent>` (no copy).
`PacketContext` is single-owner and not reused after events are retrieved.

---

## Upstream p4c backend

Land the 4ward backend in the p4c repository. Blocked on upstream review.
Once merged, switch `MODULE.bazel` from `smolkaj/p4c` fork to `p4lang/p4c`.

---

## Unpin gutil override

**File**: `MODULE.bazel`

`gutil` is pinned to commit `20c8d2e` to pick up Bazel 9 compatibility
(google/gutil#42). Check if this fix has landed in a tagged BCR release —
if so, replace the `git_override` with a regular `bazel_dep` version pin.

---

## Pinned dependencies inventory

Several deps use `git_override` with pinned commits. This is tracked here as
a reminder to periodically check for upstream updates:

- **bazel_clang_tidy** (`9e54bbb`): pinned before a commit (`c4d35e0`) that
  broke `-isystem` include ordering. Upstream bug never fixed — permanent
  workaround. Re-check if upstream ever resolves the issue.
- **behavioral_model** (`6c7c93e` + Bazel build patch): upstream uses Autotools;
  our patch adds native Bazel rules. The patch needs updating when we bump BMv2.
- **p4_constraints** (`cbcc7b1`): standard pin from `p4lang/p4-constraints`.
  Check for updates when bumping p4c.
