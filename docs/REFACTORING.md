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

## Re-enable buf lint

Blocked on buf support for proto edition 2024.

---

## Separate simulator return type from gRPC wire type

**Files**: `simulator/Simulator.kt`, `p4runtime/DataplaneService.kt`,
`simulator/simulator.proto`

**Problem**: `Simulator.processPacket` returns `ProcessPacketResponse` (a proto
message that also serves as the Dataplane gRPC wire type). This forces
`DataplaneService.processPacket` to rebuild the response just to strip the
`trace` field — the unary RPC shouldn't expose the trace, but the proto
carries it because the simulator populates it internally.

**Fix**: Have `Simulator.processPacket` return a plain Kotlin data class
(e.g. `ProcessPacketResult(outputPackets, trace)`) and let each gRPC method
build its own proto response. This decouples the simulator's internal
representation from the wire format.

**Trigger**: when a third consumer of `processPacket` appears, or when the
response fields diverge further between the two Dataplane RPCs.

---

## Upstream p4c backend

Land the 4ward backend in the p4c repository. Blocked on upstream review.
