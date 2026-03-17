# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Drop p4c fork

The `smolkaj/p4c` fork adds `//p4include` package, `//testdata/p4_16_samples`
exports, and a macOS build fix. Upstream PR:
https://github.com/p4lang/p4c/pull/5533. Once merged and released to BCR,
drop the `git_override` in `MODULE.bazel`.

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

## Use dual port encoding in sonic-pins

Once the DataplaneService supports both dataplane (`uint32`) and P4RT (`bytes`)
port encodings ([design](../designs/dataplane_port_encoding.md)), update the
sonic-pins `FourwardBackend` to use P4RT ports directly — eliminating the
manual `SimpleAtoi`/`StrCat` conversions in `fourward_backend.cc`.

---

## Establish user-facing documentation

`docs/` currently serves developers working on 4ward. As the project
approaches upstream integration into sonic-pins, API consumers (DVaaS
integrators, sonic-pins developers) need their own documentation: API
reference, configuration guide, integration cookbook. Decide on format
(mdbook, docusaurus, plain markdown) when scope is clearer.
`docs/TYPE_TRANSLATION.md` is a first step toward user-facing reference
material.
