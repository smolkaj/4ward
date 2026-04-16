# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Drop p4c fork

The `smolkaj/p4c` fork now carries a single patch on top of upstream
`main`: the PNA drop-by-default fix (p4lang/p4c#5569, still open).
The other two patches it used to carry — the bazel/p4include/macOS
fixes (p4lang/p4c#5533) and the PNA STF backend (p4lang/p4c#5570) —
are in upstream `main`. BCR consumers already get #5533 via p4c
1.2.5.11.bcr.1. Drop the `git_override` in `MODULE.bazel` once #5569
lands and both PNA fixes ship in a released p4c.

---

## Pinned dependencies inventory

Four deps use `git_override` with pinned commits. `behavioral_model` and
`bazel_clang_tidy` are dev-only (`dev_dependency = True`) and invisible to
BCR consumers; `p4c` and `grpc` affect BCR consumers too.

- **p4c** (`smolkaj/p4c` fork, `93932df`): upstream main plus a single
  cherry-picked patch, the PNA drop-by-default fix (p4lang/p4c#5569).
  See "Drop p4c fork" above.
- **grpc** (`smolkaj/grpc` fork, `a09a3af`): Bazel 9 compatibility patches
  (`native.objc_library` and friends) on top of upstream 1.80.0. Drop once
  grpc publishes a Bazel-9-compatible release to BCR.
- **bazel_clang_tidy** (`9e54bbb`): pinned before a commit (`c4d35e0`) that
  broke `-isystem` include ordering. Upstream bug never fixed — permanent
  workaround. Re-check if upstream ever resolves the issue.
- **behavioral_model** (`6c7c93e` + Bazel build patch): upstream uses Autotools;
  our patch adds native Bazel rules. The patch needs updating when we bump BMv2.

---

## Extract shared architecture helpers

`PSAArchitecture.kt` and `PNAArchitecture.kt` duplicate ~350 lines of
architecture-agnostic code: `BlockParam`, `buildBlockParamsMap`,
`createDefaultValues`, `buildExternInstancesMap`, `bindStageParams`,
`runParserStage`, `runControlStage`, `handleActionSelectorFork`,
`buildForkTree`, `evalGetHash`, `hashDataArg`, `sumWords`, `IO_TYPES`,
`MAX_RECIRCULATIONS`, and the entire Register/Hash/Counter/Meter/Checksum
extern method dispatch. Extract into shared files (e.g.
`ArchitectureHelpers.kt`, extend `TraceHelpers.kt` and `Hash.kt`).

---

## Establish user-facing documentation

`docs/` currently serves developers working on 4ward. As the project
approaches upstream integration into sonic-pins, API consumers (DVaaS
integrators, sonic-pins developers) need their own documentation: API
reference, configuration guide, integration cookbook. Decide on format
(mdbook, docusaurus, plain markdown) when scope is clearer.
`docs/TYPE_TRANSLATION.md` is a first step toward user-facing reference
material.
