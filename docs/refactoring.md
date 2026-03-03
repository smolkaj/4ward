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
