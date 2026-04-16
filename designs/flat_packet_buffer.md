# Flat Packet Buffer Rewrite

**Status: Design + consolidated implementation. The design below
describes the optimal end state. The branch lands the foundation,
buffer-backed values, consolidated per-packet buffers, and
cache-forwarding on fork — delivering **+6.3%** on the fork-heavy
wcmp×128 parallel workload (see "Measured results and what's left").
Further closing the gap to the 2-3x target requires attacking the
per-fork Java-object churn directly.**

## The goal

Replace the heap-of-objects packet-state representation with a flat
byte-buffer representation that delivers a meaningful performance
improvement on fork-heavy workloads (target: ~2–3× on `wcmp×128`
parallel) without adding architectural complexity that bleeds into
the rest of the codebase.

The diagnosis is in `designs/parallel_packet_scaling.md`: L3 cache
exhaustion under concurrent fork-heavy work caps useful scaling at
~8 worker threads. Per-packet state today is ~12 MB; the design
below brings it to ~256 bytes — fits in L1, removes the L3 ceiling.

## Foundational principle

The P4 IR carries the **complete static schema** of every program at
pipeline-load time: every header type's fields, every struct's
members, every action's locals, every field-access expression's
target. The runtime should derive everything from this schema once
at load and then, on the hot path, only touch bytes.

Every design decision below is an instance of this principle.

## What "optimal" means here

A design is optimal when:
- The runtime concepts are few and named after what they are
  (`PacketBuffer`, `PacketLayout`, `HeaderView`, `Interpreter`).
- A reader can hold the whole runtime model in their head.
- Tests, debuggers, and trace consumers see the same types as the
  interpreter — no separate "user-facing" vs "internal" hierarchy.
- Performance comes from doing less work, not from clever tricks.
- Each layer hides bit-fiddling from the layer above. The bit math
  exists in exactly one place (`PacketBuffer.readBits` /
  `writeBits`).
- The codebase is **smaller** after the rewrite, not bigger.

Things that are *not* optimal by this definition, even though they
would be faster:
- AOT bytecode generation per pipeline. Adds a generator, two
  execution paths (interpret vs run-compiled), pipeline reload
  semantics, debugger story, cached-jar hygiene. Massive blast
  radius for a multiplier on top of the simple design.
- Off-heap storage (`MemorySegment`, `Unsafe`). Manual lifecycle,
  no GC safety net, JNI-adjacent debugging.
- SIMD across packets via Panama's Vector API. Different code
  style; the rest of the code can't use it.
- Profile-guided fast paths and specialised generated variants.
  Multiple paths to maintain, fragile under workload changes.
- Specialised table data structures keyed off P4 match kind. The
  existing tables work fine; this is a separate concern from the
  storage rewrite.

The simple design below gets us most of the perf without any of
these.

## The design

Seven properties. They compose; the design is the conjunction.

### 1. One contiguous byte buffer per packet

All headers, all metadata, all action locals, all parser-extracted
data live in one contiguous `ByteArray`. Sized once per pipeline by
the layout computer: `packetLayout.totalBytes`. Typical: ~256 bytes.
Fork is one bulk `Arrays.copyOf` — sequential memory access, fits
in L1.

```kotlin
class PacketBuffer(internal val bytes: ByteArray) {
    fun readBits(offset: Int, width: Int): Long
    fun writeBits(offset: Int, width: Int, value: Long)
    fun zeroRange(offset: Int, width: Int)
    fun copyOf(): PacketBuffer
}
```

This already exists on the branch. Stays.

### 2. `PacketLayout`: one per pipeline, places every type at an absolute offset

Computed once at pipeline load by walking the IR. Maps each
top-level named type (the headers struct, the metadata struct,
`standard_metadata`, each action's locals scope) to its absolute
bit offset within the packet buffer. Each header/struct/stack's
per-type layout is reused unchanged from the existing foundation.

```kotlin
data class PacketLayout(
    val typeOffsets: Map<String, Int>,           // "headers" -> 0, "meta" -> 113, ...
    val headers: Map<String, HeaderLayout>,      // already exists
    val structs: Map<String, StructLayout>,      // already exists
    val stacks: Map<String, HeaderStackLayout>,  // see (4)
    val locals: Map<String, FieldSlot>,          // action local name -> slot
    val totalBits: Int,
)
```

### 3. `HeaderView` / `StructView` are the runtime representation of headers and structs

Not a wrapper around something else — *the* runtime type.
`HeaderView`/`StructView` extend `Value` directly. The interpreter
holds them, the trace inspector holds them, tests construct them.
No `HeaderVal`/`StructVal` data classes alongside; one type, one
purpose.

```kotlin
sealed class Value { ... }

class HeaderView(
    val buffer: PacketBuffer,
    val layout: HeaderLayout,
    val base: Int,
) : Value() {
    var isValid: Boolean
    operator fun get(field: String): Value
    operator fun set(field: String, value: Value)
}

class StructView(
    val buffer: PacketBuffer,
    val layout: StructLayout,
    val base: Int,
) : Value() {
    operator fun get(field: String): Value
    operator fun set(field: String, value: Value)
    fun header(field: String): HeaderView
    fun struct(field: String): StructView
    fun stack(field: String): HeaderStackView
}
```

Construction is O(1) — three references, no allocation of contained
values. Two views over the same buffer at the same offset behave as
the same object (P4 reference semantics line up exactly).

### 4. Header stacks: `HeaderStackView` over a flat region of the buffer

A header stack is a fixed-size array of headers plus a `nextIndex`
counter. In the buffer, it's laid out as:

```
[nextIndex: ~8 bits | element[0]: layout.totalBits | element[1] | ... | element[size-1]]
```

A `HeaderStackLayout` captures this:

```kotlin
data class HeaderStackLayout(
    val typeName: String,
    val elementLayout: HeaderLayout,
    val size: Int,
    val nextIndexOffset: Int,    // bit offset of the counter within the stack
    val totalBits: Int,
)
```

A `HeaderStackView` exposes the stack:

```kotlin
class HeaderStackView(
    val buffer: PacketBuffer,
    val layout: HeaderStackLayout,
    val base: Int,
) : Value() {
    var nextIndex: Int
    val size: Int get() = layout.size

    operator fun get(index: Int): HeaderView   // constant-offset for static `index`,
                                               // multiply-add for dynamic
    fun pushFront(count: Int)                  // block memcpy within the buffer
    fun popFront(count: Int)                   // block memcpy within the buffer
}
```

`StructMember` (the sealed class for struct members) gains a
`NestedStack` variant alongside `PrimitiveField`, `NestedHeader`,
and `NestedStruct`. The layout computer's `computeStruct` dispatches
to a new `computeStack` branch when it sees `type.hasHeaderStack()`.

Indexed access `stack[i]`:
- Static `i` (most cases — `stack.next`, `stack[0]`): the layout
  resolves at pipeline load to a constant offset.
- Dynamic `i` (rare — runtime-computed index): one multiply-add at
  access time, then a `HeaderView` over the result.

`HeaderStackVal` (the data class) goes away. `HeaderStackView`
replaces it everywhere it's used.

### 5. Field access in the interpreter goes via pre-resolved `ResolvedSlot`s

The IR's `FieldAccess` nodes are resolved at pipeline load to
`(absoluteOffset, width, kind)` and cached on the Interpreter. At
evaluation time the interpreter never hashes a field name; it reads
bits at a known offset.

```kotlin
data class ResolvedSlot(val offset: Int, val width: Int, val kind: PrimitiveKind)

class Interpreter(config: BehavioralConfig) {
    val packetLayout: PacketLayout = computePacketLayout(config)
    private val resolved: Map<FieldAccess, ResolvedSlot> = preResolve(config, packetLayout)
}
```

The interpreter keeps **one** `evalExpr` method, returning `Value`,
matching today's API. The performance comes from `Value` itself
becoming cheap to allocate (see (6)) — not from splitting into
multiple typed eval methods. A single eval method is clearer and
preserves the current dispatch model unchanged.

```kotlin
inner class Execution(val state: PacketState) {
    fun evalExpr(e: Expr): Value = when {
        e.hasFieldAccess() -> {
            val s = resolved[e.fieldAccess]!!
            readSlotAsValue(s)                  // (6) — one allocation
        }
        e.hasBinaryOp() -> applyBinaryOp(e.binaryOp.op, evalExpr(e.binaryOp.left), evalExpr(e.binaryOp.right))
        // ...
    }

    fun setLValue(lhs: Expr, value: Value) {
        val s = resolved[lhs.fieldAccess]!!
        writeSlotFromValue(s, value)
        state.trace.emitFieldWrite(s.offset, s.width, valueAsBits(value))
    }
}
```

**Decision point:** kept as single `evalExpr(Expr): Value` rather
than split `evalNarrow`/`evalWide`/`evalView`. Single entry point
matches what the existing interpreter does, keeps add-a-new-Expr-kind
to one site, and is what a reader expects. The cost is one `Value`
allocation per primitive eval — kept manageable by (6).

### 6. `BitVal` becomes a sealed class with cheap narrow variant

The current `BitVal(BitVector(BigInteger, width))` is three nested
allocations per primitive read. The optimal-in-our-sense `BitVal`
splits into narrow and wide, with the narrow path holding only a
primitive `Long`:

```kotlin
sealed class BitVal : Value() {
    abstract val width: Int

    /** ≤ 64-bit primitive bit value. One allocation, no nested boxing. */
    data class Narrow(val bits: Long, override val width: Int) : BitVal() {
        init { require(width in 1..64) }
    }

    /** > 64-bit bit value, falls back to BigInteger via BitVector. */
    data class Wide(val bitVector: BitVector) : BitVal() {
        override val width: Int = bitVector.width
    }
}
```

A primitive read of `bit<48>` returns `BitVal.Narrow(bits, 48)` —
one heap object instead of three. Operations on it use Long
arithmetic (a few JVM-intrinsic instructions) and dispatch on
`Narrow` vs `Wide` only when constructing or destructuring; most
arithmetic stays on the `Narrow` path with no `BitVector`
involvement.

Same shape applies to `IntVal` (signed). `BoolVal` stays as today.

### 7. Action locals laid out in the same buffer

Action local variables have static, known widths (P4 doesn't have
unbounded recursion). The layout computer reserves space for them
at the end of the packet buffer, indexed by action name.

No stack frames, no scope-stack `ArrayDeque<MutableMap<String,
Value>>`, no per-call allocation. The `Environment` class shrinks
to "thin shim over `state.buffer` for named-variable access."

### 8. `PacketState` and the fork path

```kotlin
class PacketState(
    val buffer: PacketBuffer,
    val layout: PacketLayout,
    val trace: TraceBuilder = TraceBuilder(),
) {
    fun fork(): PacketState =
        PacketState(buffer.copyOf(), layout, trace.fork())
}
```

Replaces the relevant portions of `Environment`. Fork is one bulk
memcpy of ~256 bytes — typically a single cache-line fill. The
trace forks too: parent's deltas-so-far are shared by reference;
future deltas go to the child's own log.

The trace itself is `(initial bytes, deltas, output bytes)` —
already designed. `TraceBuilder.emitFieldWrite(offset, width, value)`
appends to a list. At the end of the packet,
`TraceBuilder.build()` produces a `PacketTrace` for consumers;
trace consumers materialise `HeaderView`/`StructView` snapshots on
demand via lazy projection.

## End-to-end runtime sketch

```kotlin
class V1ModelArchitecture(...) : Architecture {
    private val interpreterCache = Interpreter.Cache()

    override fun processPacket(
        ingressPort: UInt,
        payload: ByteArray,
        config: BehavioralConfig,
        tableStore: TableStore,
    ): PipelineResult {
        val interp = interpreterCache.get(config)
        val state = PacketState(
            buffer = PacketBuffer(interp.packetLayout.totalBits),
            layout = interp.packetLayout,
        )

        // standard_metadata is a StructView at packetLayout.typeOffsets["standard_metadata_t"].
        val stdMeta = StructView(
            state.buffer,
            interp.packetLayout.structs["standard_metadata_t"]!!,
            interp.packetLayout.typeOffsets["standard_metadata_t"]!!,
        )
        stdMeta["ingress_port"] = BitVal.Narrow(ingressPort.toLong(), portBits)

        interp.Execution(state).run {
            runParser(...)
            runIngressControls(...)
            runDeparser(...)
        }

        return PipelineResult(state.trace.build())
    }
}
```

The hot path is: a few method calls, one allocation per primitive
expression eval (`BitVal.Narrow`), and `readBits`/`writeBits`
against one `ByteArray`. No per-packet object trees, no recursive
deep-copies, no HashMap allocations.

## The architecture in one picture

```
                    ┌──────────────────────┐
                    │  IR (ir.proto)       │
                    │  static schema       │
                    └──────────┬───────────┘
                               │ pipeline load
                               ▼
                    ┌──────────────────────┐
                    │  PacketLayout        │
                    │  + ResolvedSlots     │
                    │  (per pipeline)      │
                    └──────────┬───────────┘
                               │ per packet
                               ▼
   ┌───────────────────────────────────────────────────┐
   │              PacketState                          │
   │   ┌─────────────┐  ┌──────────────┐               │
   │   │PacketBuffer │  │ TraceBuilder │               │
   │   │  ~256 bytes │  │  delta log   │               │
   │   └─────────────┘  └──────────────┘               │
   └───────────────────────────────────────────────────┘
                               ▲
              hot path         │       cold path
              (Value via       │       (HeaderView/StructView)
              BitVal.Narrow)   │
              ┌────────────────┼────────────────┐
              │                                  │
   ┌──────────┴────────┐              ┌─────────┴────────┐
   │  Interpreter      │              │  Tests, trace    │
   │  evalExpr         │              │  inspector, UI   │
   │  setLValue        │              │  views over      │
   │                   │              │  the same buffer │
   └───────────────────┘              └──────────────────┘
```

## What changes from the existing simulator

These bullets describe the end state, not a migration path.

- `HeaderVal`, `StructVal`, `HeaderStackVal` data classes go away.
  Replaced by `HeaderView`, `StructView`, `HeaderStackView` as
  `Value` subtypes.
- `Environment.scopes: ArrayDeque<MutableMap<String, Value>>` goes
  away. Replaced by direct buffer access via the per-pipeline
  `ResolvedSlot` table.
- `BitVal` becomes a sealed class with `Narrow(Long, Int)` and
  `Wide(BitVector)` subtypes. `IntVal` similarly. The current
  `BitVal(BitVector(BigInteger, ...))` triple-allocation goes away
  for narrow primitives.
- `Interpreter.evalExpr` keeps a single signature returning
  `Value`. Field access goes via pre-resolved slots.
- The deep-copy machinery on `Value` goes away. Forking is
  `state.fork()`, which calls `buffer.copyOf()`. Other `Value`
  subtypes are immutable so they don't need copying.
- The trace event proto is replaced by `PacketTrace` /
  `TraceDelta`. The trace inspector reads them and projects
  `HeaderView` snapshots on demand.
- The codebase is smaller, not bigger.

## Concrete consequences of the simplicity choices

These are the things we explicitly *don't* get because we chose
simplicity. Worth being honest about up front.

- **No code generation per pipeline.** The interpreter dispatch
  loop (`when` over `Expr.kind`) stays. Each dispatch is a few
  cycles; bounded but real. We don't get the JIT-friendly
  straight-line code that AOT would produce.
- **No off-heap storage.** GC sees the `PacketBuffer`s. Per-packet
  buffer allocation is small (~256 bytes) but not zero.
- **No SIMD.** Packets are processed scalar-per-thread.
- **No specialised table data structures.** Tables stay as they
  are.
- **Per-eval `BitVal.Narrow` allocation.** One `Value` object per
  primitive eval, instead of the legacy "return cached `BitVal`
  from HashMap" path. We pay this for a single, simple `evalExpr`
  signature; the alternative (split `evalNarrow`/`evalWide` methods
  returning primitives) would eliminate it but requires three eval
  methods instead of one.

The expected result is **2–3× on fork-heavy workloads**. That's
the cost of choosing simplicity. Going to AOT or split-method
evals would push it to 5–10× at the cost of significantly more
complexity.

## Honest complexity assessment and ROI

### Where complexity goes up

- **One new lifecycle stage at pipeline load: layout + resolution.**
  Walking the IR, building `PacketLayout`, pre-resolving every
  `FieldAccess`. ~200 lines of well-tested code, runs once per
  pipeline. Conceptually one new step ("compute layouts") between
  IR load and packet processing.
- **Bit-level math in `PacketBuffer`.** ~100 lines, fully
  encapsulated, tested independently. Correctness-critical but
  contained — no other file does bit math.
- **New types for views and layouts.** ~400 lines of definitions
  (`HeaderView`, `StructView`, `HeaderStackView`, `HeaderLayout`,
  `StructLayout`, `HeaderStackLayout`, `FieldSlot`, sealed
  `StructMember`, `PrimitiveKind`, `PacketLayout`, `ResolvedSlot`).
  Each is small, clearly-purposed, and tested.
- **Sealed `BitVal`.** Adds two subtypes where there was one data
  class. ~30 lines of definition, plus updates to ~30 call sites
  that pattern-match on or construct `BitVal`.
- **Trace delta types.** Already designed. `TraceBuilder` is a few
  dozen lines; `PacketTrace` and `TraceDelta` are existing types.

**Estimated new code: ~800 lines.** All of it small, focused,
testable in isolation.

### Where complexity goes down

- **`HeaderVal`, `StructVal`, `HeaderStackVal` deleted.** ~200 lines
  of class definitions plus the recursive `deepCopy` machinery
  that's hard to reason about. Gone.
- **`Environment.scopes` machinery deleted.** The `ArrayDeque<
  MutableMap<String, Value>>` for nested scopes goes away;
  replaced by buffer-offset lookups. The trickiest piece of state
  in the existing simulator simplifies to pointer arithmetic.
- **`BufferBackedFieldMap` deleted.** Wrong-layer bridge from the
  earlier attempt. ~150 lines of map-adapter complexity that no
  longer needs to exist.
- **HashMap-of-Value field storage everywhere.** No more
  `headerVal.fields[name] = ...`. Field access is via views or
  pre-resolved slots.
- **Recursive `Value.deepCopy()` walk.** Replaced by one
  `buffer.copyOf()`.

**Estimated removed code: ~500 lines, plus harder-to-quantify
"complexity that's gone."**

### Net code change

**+300 to +500 lines of net code.** But the new code is more
regular, more focused, and individually-testable; the removed code
was the "tricky" part of the simulator (recursive Value deep-copy,
nested HashMap storage, scope-stack lifecycle).

### Cognitive load

A reader holding the new model in their head needs to know:

1. The IR has types (already known).
2. Layouts are derived from types at pipeline load.
3. A packet's state is one `PacketBuffer` shaped by the layout.
4. Headers/structs are views over offsets in the buffer.
5. The interpreter resolves field accesses to byte offsets at load
   time and reads bits at runtime.

Five concepts, each named after what it is. Compare with the
current model: HeaderVal → fields(MutableMap) → BitVal →
BitVector → BigInteger, plus the recursive deepCopy semantics
across the tree, plus Environment.scopes' nested HashMap stack,
plus trace event proto generation. The new model is **simpler to
hold in your head** than the current one, even after the new types
are added.

### Performance ROI

Working set per fork branch: ~50 KB → ~256 bytes (~200×
reduction). L3 pressure is the dominant bottleneck per Phase 1.5;
this lifts it.

Per-fork copy: recursive object-tree walk allocating ~50 KB →
single ~256-byte memcpy.

Per-field-access CPU: HashMap lookup + cached BitVal read → one
bit-shift on a Long, plus one `BitVal.Narrow` allocation. Roughly
break-even on read CPU; significant win on the surrounding
allocation pressure.

Per-packet allocations: ~250 wrapper objects → one `PacketBuffer`
plus a small trace log plus per-eval `BitVal.Narrow`s.

**Estimated end-state speedup on `wcmp×128` parallel: 2–3×.**
Sequential performance roughly unchanged (no L3 pressure to
relieve in the single-thread case). Direct L3 forwarding roughly
unchanged (already at 94% efficiency, no fork-heavy state).

### The honest trade-off

We're adding ~400 net lines of code and one new pipeline-load
lifecycle stage. We're getting:

- A 2–3× throughput multiplier on the workload that's currently
  scaling-bound.
- The L3 ceiling lifted (the wcmp scaling curve straightens past
  the current 8-worker knee).
- A simpler runtime model overall (one storage shape for headers,
  structs, stacks, locals — not three).
- A foundation that future optimisations (pooling,
  consumer-aware tracing, more aggressive escape analysis) can
  build on without further architectural changes.

The complexity is **contained to one subsystem** (storage). It
doesn't bleed into the IR, the architectures, the table store, or
the P4Runtime adapter. The interpreter changes are confined to the
field-access primitives and the eval/setLValue methods.

If the throughput target weren't the primary motivation — if 1500
pps were enough — this rewrite wouldn't be worth doing. With the
target being "DVaaS-class throughput" (10K pps and beyond), the
ROI is clearly positive.

## Open design questions

- **Per-thread `PacketBuffer` pooling.** Deferred per your call. If
  benchmarks later show GC pressure mattering, ~30 lines of pool
  code in the architecture, no API impact.
- **Trace cost when traces are unconsumed.** Some workloads (raw
  forwarding throughput) don't read the trace. The trace emission
  cost (one list `add` per field write) is small but not zero. If
  it matters, gate emit on a per-execution flag.
- **Custom error types beyond the seven standard ones.**
  `ErrorCodes` (already on the branch) covers the standard errors.
  Custom errors would be discovered by walking the IR's
  `Literal.errorMember` references at pipeline load and assigned
  codes. Defer until a program needs it.
- **Field assignments where both sides are aggregates** (e.g.,
  `headers.a = headers.b` where both are headers). Becomes a
  buffer-to-buffer block copy at the right offsets. Generated
  inline in `setLValue` for the aggregate case.
- **Externs (hash, checksum, register, counter).** They take and
  return `Value`s today. The interpreter passes `BitVal.Narrow`
  through to externs unchanged; externs operate on `bits.value`
  (Long) directly. For specific hot externs (CRC, simple hashes),
  specialise: read bytes from the buffer directly, compute, write
  back.

## What about table entries?

This design is scoped to **per-packet state** — the heap-tree of
`HeaderVal`/`StructVal` that forks copy recursively and that Phase 1.5
identified as the L3-pressure source. Table entries are a separate
concern, but they share the same underlying principle and deserve
their own parallel design.

Phase 1 profiling found `TableStoreKt.toUnsignedLong(ByteString)` at
65–73% of CPU on direct L3 forwarding — a bigger per-packet cost than
packet-state access on that workload. The cause: match-key bytes are
re-decoded (`ByteString` → `BigInteger` → `Long`) on every lookup for
every installed entry. For 10k LPM entries × 1000s of packets/sec,
that's a lot of redundant work.

The analogous design for table entries looks like:

- **Pre-compute at install time, not lookup time.** When the control
  plane installs an entry, convert its match key to the internal
  `Long`/`LongArray`/`ByteArray` form once. Cache per-entry. Never
  decode a `ByteString` at lookup.
- **Flat byte storage for action parameters.** Action params have
  known widths (the IR carries them); store each entry's params
  contiguously in bytes instead of as a `List<Value>`. At lookup time,
  hand the interpreter a pre-shaped "action invocation" whose params
  are byte ranges at known offsets.
- **Match-kind-specific lookup structures.** Exact-match tables →
  `HashMap<Long, Entry>` (or `Long2ObjectMap` for primitive keys). LPM
  → radix trie keyed by prefix. Ternary → sorted array of (mask,
  value, action) tuples scanned by priority. The generic "linear scan
  with per-entry decode" is catastrophic; specialised structures are
  O(1) or O(W).

These share infrastructure with the packet-state design — the same
`PacketBuffer` primitive can back action-parameter storage, and the
same `FieldSlot`/`PrimitiveKind` machinery describes where each param
lives.

**Scope decision**: out of scope for this document. Table entries
deserve their own design doc (`designs/flat_table_store.md` or
similar) once the packet-state rewrite lands and we have
benchmark-grade confidence in the primitives. The two designs compose
cleanly — neither blocks the other — so sequencing them as separate
pieces of work is safe.

## Measured results and what's left

The branch lands **two layers** of runtime:

1. **Buffer-backed values**: `HeaderVal.bufferBacked` /
   `StructVal.bufferBacked` factories. `BufferBackedFieldMap` replaces
   `HashMap<String, Value>` with a bit-addressable view into a
   `PacketBuffer`. Per-slot `Value` caching makes reads
   zero-allocation after warmup.

2. **Consolidated per-packet buffers** (the "next piece" from a prior
   revision of this section — now landed). `ConsolidatedPacketAllocator`
   lays every top-level type (hdr, meta, standard_metadata) at fixed
   absolute offsets in a single `PacketBuffer` per packet. The full
   value tree — including the nested headers inside a `headers_t`
   container struct — points into that one buffer. `Environment.deepCopy`
   on a fork does **one** `buffer.copyOf()` and a `rewire()` pass that
   swaps each value's buffer reference to the copy, instead of
   per-value `deepCopy()` with per-value memcpy.

**Measured** (AMD Ryzen 9 7950X3D, SAI middleblock, 10K packets for
fork workloads / 500K for direct, intra-packet parallelism off):

| Workload | Baseline | This branch (3-run mean) | Δ |
|---|---|---|---|
| wcmp×128 parallel | 1528 pps | **1625 pps** (1629 / 1625 / 1620) | +6.3% |
| wcmp×128 sequential | 207 pps | 200 pps (200 / 198 / 203) | within noise |
| direct parallel | 39,800 pps | 39,743 pps (39,137 / 40,710 / 39,382) | within noise |

The consolidation is **engaged** — the SAI pipeline's three top-level
types land in a single ~540-byte `PacketBuffer` per packet — the
fork path does one memcpy + rewire, and each rewired
`BufferBackedFieldMap` **inherits a copy of the pre-fork value cache**
so fork branches re-read fields without re-allocating `BitVal` /
`IntVal` / `BoolVal` wrappers. Correctness is rock-solid (73/73 tests)
and the target workload (fork-heavy wcmp×128 parallel) sees a
measurable 6.3% win; other workloads unchanged.

### Where the 6.3% came from

Two layered wins:

1. **One memcpy + rewire per fork** (consolidation): replaces
   ~25 per-value `copyOf()` calls with one
   `packetBuffer.copyOf()` + a `Value.rewire()` walk. The
   packet-data memcpy is tiny (~540 bytes); the savings are the
   absence of per-value HashMap rebuilds and the shared buffer
   locality.

2. **Cache forwarding on rewire**: a buffer-backed
   `HeaderVal` / `StructVal` caches each slot's last-returned
   `Value`. At fork time, the rewire carries the pre-fork cache
   forward into the new `BufferBackedFieldMap` (safe because the
   fresh buffer has identical bits; writes through the new map
   still invalidate per slot). Fork branches re-read the same fields
   as the pre-fork snapshot and return the **already-materialised**
   `BitVal` instead of allocating a fresh one. This is the bulk of
   the win on fork-heavy workloads.

### What still gates 2-3×

The original diagnosis (`designs/parallel_packet_scaling.md`)
pointed at L3 cache exhaustion from ~12 MB of concurrent per-packet
state. Consolidation collapses that to ~540 bytes per packet's
data. But the dominant per-fork cost is still **Java-object churn**
from the wrapper tree (`HeaderVal`, `StructVal`,
`BufferBackedFieldMap`, scope HashMaps) — not the packet data the
diagnosis focused on.

To reach the 2-3× target, the follow-ups most likely to pay off:

- **Pool `HeaderVal` / `BufferBackedFieldMap` / scope HashMap
  instances** across forks. Fork branches currently create ~25
  fresh `HeaderVal` + `BufferBackedFieldMap` pairs each; if those
  came from a thread-local pool, the allocation cost would collapse.

- **Pre-resolve `FieldAccess` to `(offset, width, kind)` at pipeline
  load and read the packet buffer directly on the interpreter hot
  path.** Tried in an earlier revision of this branch and reverted
  because bypassing `BufferBackedFieldMap`'s per-slot `Value` cache
  cost more than it saved — repeated reads of the same field re-
  allocated `BitVal`. The idea is still right, but needs its own
  caching layer to be a win; straightforward to revisit once the
  cache layer exists at interpreter scope rather than per-map.

- **Arena-allocate fork-branch state** in one slab so GC sees
  short-lived, contiguous allocations instead of a wrapper graph.

All three keep the flat-buffer primitive as-is. This branch's
contribution is getting the primitive correct, consolidated, **and
cache-forwarding-friendly** — the scaffolding required to attempt
any of the above. None of them are blocked on more data-layout work.

### Lessons for the next iteration

Two things the original design doc under-weighted:

- Per-fork **object churn** from wrapper allocations dwarfs
  packet-data cache pressure on realistic workloads. The flat-
  buffer primitive reduces it somewhat, but doesn't eliminate it.

- The `BufferBackedFieldMap` per-slot cache is load-bearing for
  performance. Any future optimisation that reads buffer bits
  directly (bypassing the cache) needs a matching cache at its
  layer — the repeat-access pattern inside a single packet's
  pipeline stage really is the hot path.

## Non-goals

- Backward compatibility of the trace proto wire format. We break
  and re-issue it.
- Backward compatibility of any of the simulator's internal types
  outside `Value` (and even `Value`'s subtypes change shape).
- Keeping any layer of the legacy object-tree storage alongside
  the new design. There's one runtime representation.
- Migrating one architecture at a time. Either the storage rewrite
  is in or it's out; partial migration creates two execution paths.
