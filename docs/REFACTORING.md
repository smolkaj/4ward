# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Drop p4c fork

The `smolkaj/p4c` fork adds the PNA STF backend for p4testgen and a
PNA drop-by-default fix. Upstream PRs:
- https://github.com/p4lang/p4c/pull/5570 (PNA STF backend)
- https://github.com/p4lang/p4c/issues/5569 (drop-by-default)

The `//p4include` and macOS fixes (p4lang/p4c#5533) already landed and
are available in BCR as p4c 1.2.5.11.bcr.1. Once the remaining changes
are merged and released, drop the `git_override` in `MODULE.bazel`.

---

## Pinned dependencies inventory

Three deps use `git_override` with pinned commits. `behavioral_model` and
`bazel_clang_tidy` are dev-only (`dev_dependency = True`) and invisible to
BCR consumers.

- **p4c** (`smolkaj/p4c` fork, `2a38af8`): adds `//p4include` package,
  testdata exports, macOS build fix. See "Drop p4c fork" above.
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
