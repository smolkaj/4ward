# Flat Packet Buffer Rewrite

**Status: Discarded.** Explored end-to-end in
[smolkaj/4ward#522](https://github.com/smolkaj/4ward/pull/522); the
full consolidated-buffer implementation passed all tests and delivered
+6.3% on the target fork-heavy workload (see measured table below),
but fell well short of the 2-3× goal. The gap wasn't addressable
through further data-layout work — the diagnosis that motivated this
design was wrong about which cost to attack. Kept here as a
retrospective so future attempts start from what we learned.

## Original hypothesis

From `designs/parallel_packet_scaling.md`: inter-packet parallelism
stops scaling around 8 worker threads on wcmp×128-style workloads,
with the scaling-cliff symptoms matching L3 cache exhaustion. The
measurement was real; the interpretation was that ~12 MB of
per-packet state (`HeaderVal` / `StructVal` / HashMap trees) was
thrashing L3.

**Proposed fix.** Replace the heap-of-objects packet state with one
contiguous byte buffer per packet (~500 bytes), plus thin views at
statically known offsets. Fork becomes one `memcpy` of the packet
buffer plus a pointer rewire instead of a deep Value-tree copy.
Expected win: ~2-3× on `wcmp×128` parallel.

## What got built

The PR landed three complete layers, all passing 73/73 tests:

1. **Foundation types** — `PacketBuffer`, `HeaderLayout` /
   `StructLayout` / `HeaderStackLayout`, `HeaderView` / `StructView`
   / `HeaderStackView`, `PacketLayout`, `LayoutComputer`, `ErrorCodes`.
2. **Buffer-backed values** — `BufferBackedFieldMap` as a drop-in
   replacement for the `HashMap<String, Value>` that backs `HeaderVal.fields`
   / `StructVal.fields`, with per-slot `Value` cache.
3. **Consolidated per-packet buffers** — `ConsolidatedPacketAllocator`
   places every top-level value (hdr, metadata, standard_metadata)
   at fixed offsets in one shared `PacketBuffer`; `Environment.deepCopy`
   does one `buffer.copyOf()` plus a `Value.rewire` walk.

## What got measured

AMD Ryzen 9 7950X3D, SAI middleblock, best 3-run mean:

| Workload | Baseline | Full implementation | Δ |
|---|---|---|---|
| wcmp×128 parallel | 1528 pps | **1625 pps** | +6.3% |
| wcmp×128 sequential | 207 pps | 200 pps | within noise |
| direct parallel | 39,800 pps | 39,743 pps | within noise |

A `skipDeepCopy()` oracle benchmark capped the maximum possible win
from fork-copy elimination at ~16% — the 2-3× target was incompatible
with a data-layout-only change from the start.

## Why it fell short

**The "12 MB per packet" figure was mostly wrapper objects, not field
data.** The flat-buffer rewrite shrinks the field data (to ~500 bytes
per packet), but the `HeaderVal` / `StructVal` / `BufferBackedFieldMap`
/ scope-HashMap wrappers still have to be rebuilt per fork. ~55 Java
object allocations per fork branch, barely changed by consolidation.

**The interpreter is the real hot path.** Per-fork time breakdown:
~9% `deepCopy`, ~91% pipeline re-execution (expression evaluation,
table lookups, action dispatch). None of that moves when the field
storage rearranges.

**Caches are load-bearing.** Every optimisation attempted in the
final session that bypassed the `BufferBackedFieldMap` per-slot cache
regressed:

- Pre-resolved field-access fast path — −14%. Direct buffer reads
  lose the repeat-access caching that `HashMap.get` benefits from.
- Copy-on-write cache on fork — −2%. The shared-flag check on every
  mutation outweighs the saved HashMap copies; SAI branches mutate
  often enough.
- Identity-based rewire dedup for aliased scope entries — −2%.
  `IdentityHashMap` overhead on a 5-entry scope is larger than the
  per-alias allocation savings.
- Array-backed cache indexed by slot position — −8%. Replacing one
  HashMap lookup (cache) with another (slot→index) wins nothing;
  bounds-check overhead on the array load costs extra.

The thing that actually delivered the +6% was **cache forwarding on
fork**: `BufferBackedFieldMap.withBuffer` copying the pre-fork value
cache forward onto the rewired map, so post-fork reads return the
already-materialised `BitVal` instead of re-allocating. That tells
the story — the flat buffer itself barely moved the needle; the win
came from not re-allocating the wrappers on top of it.

## What would a real 2-3× look like

Per the retrospective: the dominant cost is per-fork Java-object
churn (wrapper tree + GC traversal) and per-read `Value` wrapper
allocation on the interpreter hot path. None are addressable via
data layout. Three follow-ups remain plausible:

1. **Pool the wrapper tree across forks.** Pre-allocate `HeaderVal`
   / `BufferBackedFieldMap` / scope HashMap instances per worker
   thread; reset them on each fork instead of allocating fresh. Risks:
   lifecycle management, thread-affinity correctness.
2. **Pre-resolve `FieldAccess` with an interpreter-scoped cache.**
   The fast-path attempt failed because it bypassed the per-slot
   cache. A correct version needs its own caching layer — per-packet
   `Map<ResolvedSlot, Value>` with proper invalidation on writes.
3. **Arena-allocate fork-branch state.** One slab per fork branch
   releases everything at once; GC sees contiguous short-lived
   allocations instead of a graph.

All three are interpreter-level changes, not data-layout. Picking
them up doesn't require this PR's primitives — the `Value`
hierarchy plus `HashMap<String, Value>` is fine scaffolding for any
of them.

## Lessons

- **Profile before theorising.** The hypothesis that L3 cache pressure
  mapped to packet-state bytes was plausible-looking and wrong. A
  flame graph of the baseline would have shown interpreter dispatch
  and `TableStore.get` dominating, with fork-copy as a minor slice —
  and pointed us at the interpreter from day one.
- **Build a "what if this optimisation were free?" oracle first.**
  The `skipDeepCopy` one-liner showed the ceiling was 16%. That
  number doesn't justify a multi-week layout rewrite. Running it in
  Phase 1 would have redirected the project.
- **Caches aren't details.** The `BufferBackedFieldMap` per-slot
  cache is doing structural work for performance. Any change that
  bypasses it needs its own cache story or it will regress.
- **Caches hide correctness bugs too.** `PacketBuffer.writeBigInt`
  had the chunk order reversed for the entire implementation; the
  cache returned the original wrapper on reads and never touched
  the buffer. Only the (reverted) fast-path bypass surfaced it.
  Takeaway: when writing a new cache, also write a no-cache test mode
  that exercises the underlying path.
- **Variance matters.** 3-run means can be 10% off due to thermal /
  background load on this hardware. Report medians across ≥5 runs,
  not best-of-3, for anything you'd act on.

## References

- PR: [smolkaj/4ward#522](https://github.com/smolkaj/4ward/pull/522)
  — full implementation, benchmark data, all experiments
- `designs/parallel_packet_scaling.md` — original scaling diagnosis
  (the measurement is sound; the root-cause attribution was not)
