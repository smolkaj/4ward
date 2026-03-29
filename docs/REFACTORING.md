# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Pinned dependencies inventory

Two deps use `git_override` with pinned commits. Both are dev-only
(`dev_dependency = True`) and invisible to BCR consumers.

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
