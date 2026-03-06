# Refactoring Backlog

Remove entries once they are resolved. This file should only contain open
tech debt — not a history of past refactors.

---

## Corpus tests: opt-out instead of opt-in

**Files**: `e2e_tests/corpus/BUILD.bazel`, new `e2e_tests/corpus_ext.bzl`,
`MODULE.bazel`

**Problem**: `e2e_tests/corpus/BUILD.bazel` is an explicit allow-list — adding
a newly-passing corpus test requires a BUILD edit. It's easy to forget, and
the list gives no signal about how many tests are still failing.

**Fix**: Replace the allow-list with a Bazel module extension that discovers
all `*-bmv2.p4`/`*-bmv2.stf` pairs in `@p4c//testdata/p4_16_samples/` at
load time and generates a `p4_stf_test` for each. Known failures are listed
in a `corpus.skip()` call in `MODULE.bazel` and get `tags = ["manual"]`
(excluded from `//...`). Enabling a newly-passing test then means removing it
from the skip list rather than adding to BUILD.

Sketch:

```python
# MODULE.bazel
corpus = use_extension("//e2e_tests:corpus_ext.bzl", "corpus_extension")
corpus.skip(tests = [
    "currently_failing_test-bmv2",
    # ...
])
use_repo(corpus, "p4c_corpus")
```

```python
# e2e_tests/corpus_ext.bzl
def _corpus_impl(mctx):
    skip = {}
    for mod in mctx.modules:
        for tag in mod.tags.skip:
            for t in tag.tests:
                skip[t] = True

    # Walk @p4c//testdata/p4_16_samples/ and collect *-bmv2 test names.
    # Generate a BUILD with p4_stf_test for each; tag skipped ones ["manual"].
    ...

corpus_extension = module_extension(
    implementation = _corpus_impl,
    tag_classes = {"skip": tag_class(attrs = {"tests": attr.string_list()})},
)
```

The module extension has read access to the p4c source tree (via
`git_override`) and can enumerate files with
`mctx.path(Label("@p4c//:MODULE.bazel")).dirname`. The generated repo replaces
`e2e_tests/corpus/BUILD.bazel` entirely.

---

## Extract shared `p4_compile` genrule macro

**Files**: new `e2e_tests/p4_compile.bzl`, all callers

**Problem**: The p4c-4ward compilation genrule is copy-pasted across 8
locations (`corpus.bzl`, `p4testgen.bzl`, and 6 hand-written `BUILD.bazel`
files). Each copy has the same `cmd`, `tools`, and `outs` pattern.

**Fix**: Extract a shared Starlark function in `e2e_tests/p4_compile.bzl`:

```python
def p4_compile(name, p4_src, tags = []):
    native.genrule(
        name = name + "_pb",
        srcs = [p4_src],
        outs = [name + ".txtpb"],
        cmd = "$(execpath //p4c_backend:p4c-4ward) -I $$(dirname $(execpath @p4c//:core_p4)) -o $@ $(SRCS)",
        tools = [
            "//p4c_backend:p4c-4ward",
            "@p4c//:core_p4",
            "@p4c//:p4include",
        ],
        tags = tags,
    )
```

Then `corpus.bzl`, `p4testgen.bzl`, and the standalone `BUILD.bazel` files
all call `p4_compile(name, p4_src)` instead of inlining the genrule.

---

## Reuse simulator process across p4testgen sub-tests

**Files**: `e2e_tests/stf/Runner.kt`, `e2e_tests/p4testgen/P4TestgenTest.kt`

**Problem**: Each sub-test in a p4testgen target spawns a fresh simulator
subprocess and re-loads the same pipeline config. With `max_tests = 100`,
subprocess overhead (~330ms each) dominates — 100 sub-tests take ~33s
when the actual packet processing is negligible.

**Fix**: All sub-tests within a target share the same `.txtpb`. Launch the
simulator once, load the pipeline once, then for each STF: install table
entries, send packets, check outputs. `StfRunner` currently assumes
one-shot execution (launch → run → destroy); refactor it to support a
persistent session that resets table state between STFs.

---

## Make `matchesMasked` internal for test reuse

**Files**: `e2e_tests/stf/Runner.kt`, `e2e_tests/stf/StfParserTest.kt`

**Problem**: `matchesMasked` is `private` in `Runner.kt`, so
`StfParserTest.kt` has an identical copy to test the logic independently.
Kotlin `internal` visibility doesn't cross Bazel target boundaries (each
`kt_jvm_library`/`kt_jvm_test` is a separate module).

**Fix**: Move `matchesMasked` to a shared utility in a common target that
both `stf_runner` and `StfParserTest` depend on, or merge the test into the
same target.

---

## CI hygiene

Pin tool versions and add `set -euo pipefail` to `format.sh`.

---

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Extract shared interpreter test helpers

**Files**: `simulator/Interpreter*Test.kt` (6 files)

**Problem**: Proto-building helpers (`boolLit`, `bit`, `nameRef`, `ifStmt`,
`controlConfig`) are duplicated as private functions across 6 test files.
The copies are identical or near-identical (some add optional parameters).

**Fix**: Extract into a shared `InterpreterTestDsl.kt` test utility. The
most general version of each helper (e.g. `controlConfig` with a
`controlName` parameter, `ifStmt` with optional `sourceInfo`) subsumes
the others.

---

## Pass `P4Info` directly to `TableStore.loadMappings`

**Files**: `simulator/TableStore.kt`, `simulator/Simulator.kt`,
`simulator/TableStoreTest.kt`

**Problem**: `loadMappings` takes a growing list of parameters — one per
entity type (`tableNameById`, `actionNameById`, `p4infoTables`,
`p4infoRegisters`). Each new P4Runtime entity (counters, meters, digests)
will add another parameter.

**Fix**: Accept the `P4Info` proto directly (or a slim wrapper) and let
`TableStore` extract what it needs internally. The two `Map<Int, String>`
parameters are already derived from `P4Info` in `Simulator.kt` and could
move inside `loadMappings`. This keeps the signature stable as entity
coverage grows.

**Trigger**: when counters or meters are wired through P4Runtime.

---

## Upstream p4c backend

Land the 4ward backend in the p4c repository. Blocked on upstream review.
