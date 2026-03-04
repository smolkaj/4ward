# Simulator Refactoring Backlog

Three self-contained cleanups for `simulator/`. Each can be done independently.
All tests must remain green (`bazel test //...`) after each change.

---

## 1. ArrayDeque iteration: drop `scopes.reversed()`

**File**: `simulator/Environment.kt`

**Problem**: `Environment` stores scopes in an `ArrayDeque` and looks up
variables by calling `scopes.reversed().firstNotNullOfOrNull { ... }`. The
`.reversed()` call allocates a new list on every lookup, which is wasteful for
a hot path.

**Fix**: Iterate from the end of the `ArrayDeque` directly using
`scopes.asReversed()` (a view, no allocation) or an explicit index loop.

```kotlin
// Before
scopes.reversed().firstNotNullOfOrNull { it[name] }

// After
scopes.asReversed().firstNotNullOfOrNull { it[name] }
```

`asReversed()` returns a live reversed view backed by the original list — no
copy. This is a one-line change but worth doing before the interpreter handles
deeper nesting.

---

## 2. `execMethodCall` dispatch: eliminate the stringly-typed `when`

**File**: `simulator/Interpreter.kt`, function `evalMethodCall` (around line 344)

**Problem**: Method dispatch is a `when (call.method)` on raw strings
(`"isValid"`, `"setValid"`, `"setInvalid"`, `"extract"`, `"emit"`,
`"lookahead"`, `"__call__"`, …). Adding new methods means touching this
central switch and remembering the exact string. It's easy to silently miss a
case.

**Fix**: Extract each handler into its own private function and document the
expected target type alongside the method name. Group by receiver type with
comments:

```kotlin
private fun evalMethodCall(call: MethodCall, env: Environment): Value = when (call.method) {
    // --- Header methods ---
    "isValid"   -> evalIsValid(call, env)
    "setValid"  -> evalSetValid(call, env)
    "setInvalid"-> evalSetInvalid(call, env)
    // --- Packet methods ---
    "extract"   -> execExtract(call, env)
    "emit"      -> execEmit(call, env)
    "lookahead" -> execLookahead(call, env)
    // --- Action/function calls ---
    "__call__"  -> execInlineActionCall(...)
    else        -> execExternCall(call, env)
}
```

The functions already exist in many cases — this is mostly about moving the
dispatch string into a single, well-documented place and removing the nested
`if/else` chains inside `evalMethodCall`.

---

## 3. `Environment`: separate packet state from variable scopes

**File**: `simulator/Environment.kt` (and callers in `V1ModelArchitecture.kt`,
`Interpreter.kt`)

**Problem**: `Environment` currently holds both:
- the variable scope stack (local vars, headers, metadata), and
- packet-level state (the raw input bytes, the emit buffer, the execution
  trace).

These are different concerns with different lifetimes. Packet state lives for
the duration of one `processPacket` call; variable scopes are pushed/popped
per control/parser invocation. Mixing them makes `Environment`'s constructor
awkward (`byteArrayOf()` for tests that don't need a packet) and its
responsibilities unclear.

**Fix**: Extract packet state into a separate `PacketContext` (or
`PacketEnv`) that is created once per packet and threaded through
`processPacket` and the interpreter entry points. `Environment` then holds
only scopes and can be constructed without a packet.

Concretely:
- Move `inputBytes`, `emitBuffer`, `trace` out of `Environment` into a new
  `PacketContext` class.
- Update `V1ModelArchitecture.processPacket` to create a `PacketContext` and
  pass it alongside the `Environment`.
- Update interpreter entry points (`runParser`, `runControl`) to accept an
  optional `PacketContext` (needed for `extract`/`emit`).
- Simplify test helpers: `Environment(byteArrayOf())` becomes just
  `Environment()`.

This is the largest of the three changes and touches the most files, but it
makes both classes easier to reason about and test independently.

---

## 4. Corpus tests: opt-out instead of opt-in

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
`local_path_override`) and can enumerate files with
`mctx.path(Label("@p4c//:MODULE.bazel")).dirname`. The generated repo replaces
`e2e_tests/corpus/BUILD.bazel` entirely, and all `.p4`/`.stf` files in
`e2e_tests/corpus/` can be deleted (they are already referenced directly from
`@p4c//testdata/p4_16_samples/` as of the change that introduced this note).
