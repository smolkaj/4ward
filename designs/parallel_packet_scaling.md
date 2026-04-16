# Parallel Packet Scaling

**Status: Phase 2 complete — flat-buffer attempt refuted the
working-set-bytes hypothesis; diagnosis refined to per-fork
wrapper-object churn and per-read Value allocation. See
`designs/flat_packet_buffer.md` and
[smolkaj/4ward#522](https://github.com/smolkaj/4ward/pull/522).**

## North star

**Inter-packet parallelism should scale linearly with physical cores.**
For N cores, the parallel `InjectPackets` RPC should deliver N× the
throughput of a single-threaded run. Each packet is independent of every
other packet (modulo a few shared stateful externs, which have their own
lock-free data structures) — the algorithm is truly embarrassingly
parallel at the packet level. Anything well below linear is wasted
hardware.

This is the benchmark we want to hit:

```
throughput(N cores) = N × throughput(1 core)
```

Real-world overheads mean we won't hit exactly linear. On a well-optimized
CPU-bound JVM workload with heavy allocation, 85-90% on 16 cores is
ambitious but reachable; 50% is common without work. Anything more than
~10% off demands an explanation.

### Why inter-packet parallelism specifically

The simulator actually has two independent parallelism mechanisms:

- **Inter-packet parallelism**: multiple packets process on separate
  threads simultaneously. Each packet is an independent task with no
  shared state — genuinely embarrassingly parallel. Implemented by
  `InjectPackets` dispatching each packet to `ForkJoinPool.commonPool()`.
- **Intra-packet parallelism**: within a single packet, trace-tree fork
  branches (clone, multicast, action selector) run on separate threads.
  Branches start from a shared packet state that must be deep-copied
  per branch — parallelism, but not embarrassingly so. Implemented in
  `V1ModelArchitecture.buildTraceTree` via `parallelStream()`.

**The north star is about inter-packet parallelism only**, because:

1. It's the only genuinely embarrassingly parallel axis (no shared
   starting state between tasks).
2. It's the axis that matters for throughput-oriented workloads
   (DVaaS, replay testing, fuzz testing) — users who need to process
   many packets.
3. It composes cleanly with single-threaded reasoning: "one thread does
   one packet, N threads do N packets."

Intra-packet parallelism is an orthogonal optimization for a different
use case (single-packet latency — CLI, STF tests, playground) and is
out of scope for this document. Measurements below confirm that intra-
and inter-packet parallelism don't meaningfully compose — inter-packet
alone saturates the machine, so intra-packet on top adds nothing.

### Baseline methodology

"Single-thread" means exactly one core doing all the work for one packet
at a time. Both parallelism axes are disabled:

- **Inter-packet**: sequential `InjectPacket` calls (one packet at a time).
- **Intra-packet**: `parallelStream()` in `buildTraceTree` disabled via
  the `fourward.simulator.intraPacketParallelism=false` system property.

"Parallel" means many packets in flight via `InjectPackets`, with
intra-packet parallelism **also disabled** — apples-to-apples with the
single-thread baseline. The comparison measures pure inter-packet
speedup with no confounding from intra-packet behavior.

## Current state

Verified measurements on a 16 physical core / 32 SMT thread AMD Ryzen 9
7950X3D, SAI P4 middleblock, 10k routes. Both sides have intra-packet
parallelism disabled (`-Dfourward.simulator.intraPacketParallelism=false`).
Measured via the `profile focused` test in `DataplaneBenchmark`.

| Workload | Single-thread | Parallel | Speedup | **Efficiency vs 16 cores** | CPU utilization (parallel) |
|---|---|---|---|---|---|
| direct | 2,568 pps | 38,449 pps | **14.97×** | **94%** | 65% — idle headroom |
| wcmp×16+mirr | 664 pps | 5,624 pps | **8.47×** | **53%** | 91% — saturated |
| wcmp×128 | 207 pps | 1,501 pps | **7.25×** | **45%** | 81% — saturated |

**Direct L3 is essentially at linear scaling.** 14.97× on 16 physical
cores = 94% efficiency. The machine isn't even CPU-saturated (JVM
averaged 65% of total machine CPU), so the remaining 6% gap is dispatch
overhead (gRPC, coroutines, `ForkJoinPool` task submission serial
fraction), not compute. Not worth chasing.

**Fork-heavy workloads are at ~45-53% efficiency** and **efficiency
degrades with higher fork counts** (53% at 32 branches per packet, 45%
at 128). These workloads saturate the machine but each packet costs ~2×
more CPU under parallel load than under single-thread.

> **CPU utilization ≠ efficiency.** CPU utilization = how much of the
> machine is being used. Efficiency = speedup / theoretical max. Direct
> has low CPU utilization but high efficiency (finishes fast without
> needing all the cores). wcmp has high CPU utilization but low
> efficiency (uses all the cores but each packet costs more).

### Aside: intra-packet parallelism is orthogonal

Confirming the claim above, wcmp×128 throughput with intra-packet
parallelism toggled on and off:

| Mode | Intra-packet | Throughput | CPU util |
|---|---|---|---|
| Single-thread | OFF | 207 pps | 3.4% |
| Single-thread | ON | 633 pps | — |
| Parallel (inter-packet) | OFF | 1,501 pps | 81% |
| Parallel (inter-packet) | ON | 1,506 pps | 88% |

Under parallel load, intra-packet is a wash on throughput (1,506 vs
1,501 — noise) but burns ~7% more CPU. Under single-packet load, it's
a 3× latency win (633 vs 207). Keep it enabled by default — CLI, STF,
and playground users benefit, parallel users don't pay. The scaling
measurements above use intra-packet **off** on both sides to keep the
comparison clean.

## Why embarrassingly parallel isn't linear in practice

First, the theory. "Embarrassingly parallel" is a statement about the
algorithm: the tasks have no inter-task dependencies, no synchronization,
no shared state. That's true for packet processing (the snapshot is
read-only at the data-plane layer; each packet is independent).

But **the algorithm and the runtime live in different worlds**. The
algorithm doesn't know about the machine's shared physical resources,
and six of them could plausibly be in play. Bracketed verdicts below
are the results of measuring each mechanism directly — see Phase 1.5.

1. **The JVM allocator.** Allocations normally go into a per-thread TLAB
   (thread-local allocation buffer) — fast, no contention. But when a
   TLAB fills, the thread has to request a new one from the shared heap,
   which is a coordination point. At high allocation rates, TLAB refills
   become frequent and contention becomes visible.
   **[Refuted — see Phase 1.5 #1.]**

2. **L3 cache pressure.** Each physical core has private L1/L2; all
   cores share L3. Single-threaded, one packet's working set fits in
   L1/L2. With many threads running in parallel, their combined
   ephemeral working sets (HashMaps, BigIntegers, builders per thread)
   spill into L3, evicting each other. What was an L2 hit becomes an
   L3 hit (3× slower), or worse, a DRAM access (10× slower).
   **[Confirmed — the dominant mechanism. See Phase 1.5 #6, #7.]**

3. **Memory bandwidth.** DRAM has finite bandwidth. Fresh allocations
   touch new cache lines; at gigabytes per second of allocation across
   all threads, memory bandwidth becomes a real constraint.
   **[Contributory — shares root cause with (2). See Phase 1.5 #6, #7.]**

4. **Garbage collection.** G1GC steals CPU cycles for concurrent marking
   and evacuation. At high allocation rates, GC runs more often,
   reducing effective throughput even when pause times look small.
   **[Refuted — see Phase 1.5 #3, #5, #9.]**

5. **Cache coherency.** Even read-only data has coherency cost if
   writes happen nearby (false sharing). JVM object allocations are
   typically on fresh cache lines, so this is usually a non-issue — but
   it can bite.
   **[Refuted — see Phase 1.5 #8.]**

6. **SMT interference.** Two SMT threads on the same physical core
   share L1, L2, and execution units. For memory-bound work, they can
   slow each other down.
   **[Refuted — see Phase 1.5 #6.]**

**For CPU-bound JVM workloads with heavy allocation, 50-60% efficiency
on 16 cores is a normal outcome, not a failure.** The algorithm's
embarrassing parallelism is preserved in the *logical* decomposition —
each packet is still independent. But the *physical* execution shares
allocator, caches, and memory bandwidth across all threads.

This means: closing the gap to linear scaling requires reducing shared
resource pressure, not restructuring the algorithm. Specifically:

- **Reduce working-set size** → less L3 pressure, fewer DRAM misses
- **Reduce per-allocation size** → fewer cache lines touched per object

## Phase 1 profile findings

JFR CPU profiles tell us *where* time is spent, not *why*. The numbers
below are raw sampling observations; the Phase 1.5 deep-dive
reinterprets them using hardware counters.

Two JFR profile pairs: direct single-thread vs direct parallel, and
wcmp×128 single-thread vs wcmp×128 parallel. Both sides have intra-packet
parallelism disabled to get a clean apples-to-apples comparison (with it
on, sequential mode uses ~5 cores per packet, which confuses the diff).
The diffs tell completely different stories.

### Direct L3 — scales cleanly

**Dominant cost (same in both modes):** `TableStoreKt.toUnsignedLong(ByteString)`
at 65-73% of CPU. The O(n) linear scan over 10k LPM entries converts each
entry's match field bytes to a Long for comparison.

**Parallel scaling overhead:** `TableStore.lookup` +4.3% — modest cache
pressure from threads each scanning the same 10k-entry table. The
read-only table stays in L2/L3 well enough that contention is minimal.

**Why direct scales well:** no trace tree forks → no header/struct deep
copies → no allocation-heavy per-fork work. Each thread does the same
lookup work independently on the shared (read-only) snapshot.
Cache-friendly and allocator-friendly.

### wcmp×128 — allocation-adjacent hot spots

The workload has a 128-way action selector fork, producing 128 branches
per packet. Each branch must deep-copy the packet state (headers +
structs) so branches don't interfere with each other.

**Parallel scaling overhead (clean diff, both sides intra-packet parallelism OFF):**

| Delta | Frame | Source |
|---|---|---|
| **+7.7%** | `BigInteger.<init>(int[], int)` | Per-fork arithmetic intermediates |
| **+7.4%** | `HashMap.putVal` | Per-fork header/struct deep copy |
| **+2.9%** | `TraceEvent$Builder.buildPartial` | Per-fork trace event allocation |
| **+2.1%** | `BitVector.<init>(BigInteger, int)` | Related to `BigInteger` allocation |
| **+1.4%** | `HashMap.put` | Per-fork header/struct deep copy |
| +0.9% | `BitVector.concat` | Bit field operations |
| +0.7% | `BigInteger.<init>(int[])` | More BigInteger allocation |

**BigInteger allocation is the single largest overhead bucket at ~9%
combined.** HashMap put operations (`put` + `putVal`) are a close second
at ~9% combined. The overhead inside these frames totals **~23-25%**
of parallel-mode CPU — but Phase 1.5 shows those cycles are
DRAM-stall cycles, not allocator-fast-path cycles.

### What the profile diff pointed at

The hot methods in the parallel-overhead bucket are all
allocation-adjacent: `HashMap.putVal` is called from
`HeaderVal.deepCopy()` / `StructVal.deepCopy()`, which each fork branch
invokes to get an independent copy of packet state.
`BigInteger.<init>` is called from `BitVector` arithmetic, which fires
on every bit-field operation. At 128 fork branches per packet × 30-odd
active workers, the allocator is hit millions of times per second.

Taken at face value, this looks like **fork-induced allocation
pressure** — the fix would be "allocate less." But the profile alone
can't distinguish "`HashMap.putVal` is slow because the allocator is
contended" from "`HashMap.putVal` is slow because the new node's cache
line has to be fetched from DRAM." The first would be solved by
reducing allocation count; the second would be solved by reducing
working-set size. These are correlated but not identical, and the
right fix depends on which is actually happening.

Phase 1.5 answers that question.

## Phase 1.5: empirical deep-dive

**TL;DR.** Of the six candidate mechanisms, only one is confirmed:
**L3 capacity exhaustion**. Each thread's IPC collapses from 3.02 to
0.88 in parallel — same total instructions, unchanged L1/L2 hit rates,
but 3× more loads per packet miss all caches and reach DRAM. The
allocator-adjacent hot spots from the Phase 1 profile are
*memory-stall cycles* inside those methods, not allocator CPU cost.

Nine experiments on the same machine and workload (AMD Ryzen 9
7950X3D, SAI P4 middleblock, wcmp×128, intra-packet parallelism off on
both sides):

1. **JFR TLAB events** — `ObjectAllocationInNewTLAB` / `OutsideTLAB`
2. **JFR allocation rate** — bytes per packet, bytes per second
3. **JFR GC events** — pause share, young-gen sizing
4. **Scaling curve** — wcmp×128 at 1/2/4/8/16/32 workers
5. **Heap size sweep** — 2 GB / 4 GB / 8 GB / 16 GB
6. **CPU pinning** — physical-only, single-CCD, both CCDs
7. **`perf stat`** — IPC, cache hits, DRAM fills
8. **`perf c2c`** — false-sharing detection via HITM events
9. **GC collector swap** — G1 vs ParallelGC vs ZGC vs Shenandoah

### Results summary

| # | Mechanism | Status | Key evidence |
|---|---|---|---|
| 1 | TLAB contention | **Refuted** | Per-packet TLAB refills 4.1 → 8.1 (2×), but refill is an atomic bump — not a scaling bottleneck |
| 2 | **L3 / DRAM stalls** | **CONFIRMED** | **IPC 3.02 → 0.88** (−3.4×), **DRAM fills/packet 73K → 228K** (+3.1×) |
| 3 | Memory bandwidth | Contributory | Aggregate ~13 GB/s allocation + 3× DRAM traffic per packet; shares root cause with (2). See Phase 1.5 #7. |
| 4 | GC | **Refuted** | Pause share 1.8% of wall time; 2 GB ↔ 16 GB heap unchanged; best GC swap (ParallelGC) +1.9% |
| 5 | False sharing | **Refuted** | 850 HITM events / 874K samples = 0.1%; top contended line 11 HITMs |
| 6 | SMT interference | **Refuted** | Physical-only pinning (16 workers) 1436 pps vs unpinned 16-worker 1443 pps — within noise |

### The smoking gun: `perf stat`

Single-threaded wcmp×128 vs parallel wcmp×128, both intra-packet off:

| Metric | Sequential | Parallel | Δ |
|---|---|---|---|
| Total instructions | 886 B | 870 B | ~identical |
| **IPC** | **3.02** | **0.88** | **−3.4×** |
| Loads (dispatched) | 245 B | 238 B | ~same |
| L2 fills (L2→L1) | 15.4 B | 13.0 B | ~same |
| L3 (local CCX) fills | 2.10 B | 2.34 B | ~same |
| **DRAM fills (all)** | **0.74 B** | **2.28 B** | **+3.1×** |
| **DRAM fills / packet** | 73K | 228K | **+3.1×** |
| Cross-CCD fills | 0 | 0 | — |

Read the top row carefully: **the algorithm executes the same number
of total instructions** (886 B vs 870 B for the same 10,000 packets).
Parallel isn't doing more work; it's doing the same work much slower
per cycle. IPC collapses from 3.02 (near-ideal for Zen 4) to 0.88 —
the hallmark of a memory-stall-bound workload.

L1 and L2 hit rates are essentially unchanged between modes. What
*does* change is the small tail of loads that miss all caches: **3×
more per packet** reach DRAM. Each DRAM miss is roughly 200 cycles of
stall. `(228K − 73K) × 200 cycles ≈ 31M extra cycles per packet`, or
roughly 6–9 ms of per-core stall (depending on effective frequency).
Parallel per-packet core-time is ~16 ms vs sequential ~5 ms, so the
extra DRAM stalls account for the bulk of the 11 ms gap.

### The scaling curve

Running wcmp×128 at varying `ForkJoinPool.common.parallelism`, all via
the parallel (streaming `InjectPackets`) path so per-RPC overhead is
amortized across the batch and only pure compute is being measured
(this is why the 1-worker baseline below is 229 pps, not the 207 pps
from the Current state table — the latter uses unary `InjectPacket`
which pays per-call gRPC cost):

| Workers | pps | Speedup vs 1 | Efficiency |
|---|---|---|---|
| 1 | 229 | 1.00× | 100% |
| 2 | 446 | 1.95× | 97% |
| **4** | **858** | **3.75×** | **94%** |
| **8** | **1235** | **5.39×** | **67%** |
| 16 | 1443 | 6.30× | 39% |
| 32 (SMT) | 1538 | 6.72× | 21% |

The curve has a **clear knee between 4 and 8 workers** (94% → 67% in
one doubling). That's a hard resource ceiling, not a graceful
falloff. Doubling from 16 to 32 threads recovers only 95 pps — SMT
provides almost no benefit on this workload, which is exactly what
you'd expect when siblings are both waiting on DRAM instead of
competing for execution units.

### CCD topology

The 7950X3D has two CCDs: 8 cores each, each with its own 32 MB L3,
one with an additional 64 MB 3D V-cache stack (96 MB total on that
CCD). Pinning experiments:

| Config | Cores | Workers | pps |
|---|---|---|---|
| CCD0 only (CPUs 0–7) | 8 | 8 | 1227 |
| CCD1 only (CPUs 8–15) | 8 | 8 | 1206 |
| Both CCDs, physical only | 16 | 16 | 1436 |

Three observations, with the punchline first:

1. **The two CCDs don't scale additively, implicating DRAM bandwidth.**
   CCD0 alone 1227 + CCD1 alone 1206 "should" sum to ~2400. Running
   both together: 1436. The second CCD contributes only 17% of its
   standalone capacity once the first is active. The only *relevant*
   shared resource for this workload is the memory controller / DRAM
   bandwidth — which is exactly what a DRAM-bound diagnosis predicts.

2. **Saturation within a single CCD.** 8 workers on one CCD ≈ 8
   workers unpinned (1227 ≈ 1235 from the scaling curve). The
   bottleneck fills up at 8 threads on a single CCD — within one L3
   domain, we hit the ceiling long before we run out of cores.

3. **3D V-cache doesn't help this workload.** CCD0 and CCD1 perform
   identically (1227 vs 1206). If V-cache were buying us anything,
   the V-cache CCD should be noticeably faster. Either the working
   set already exceeds 96 MB, or the allocation churn pattern doesn't
   benefit from the extra capacity. Either way: not a factor.

Also: `ls_any_fills_from_sys.far_cache` is **zero** in both modes. No
cross-CCD coherency traffic. The OS scheduler keeps threads on their
home CCD. Inter-CCD contention is not the story.

### Reframed diagnosis

The extra cycles on the Phase 1 parallel-overhead diff *do* happen
inside `HashMap.putVal` and `BigInteger.<init>`. They are not
allocator fast-path cycles. They are memory-stall cycles: each
`putVal` at +7% on the CPU profile is spending hundreds of cycles
waiting for its new node's cache line to arrive from DRAM, because
the L3 has been evicted by other threads doing the same thing.

**The root cause is L3 capacity exhaustion under concurrent allocation
churn.** Each wcmp×128 packet allocates on the order of 12 MB of
ephemeral state (128 fork branches × deep-copied `HeaderVal` and
`StructVal` maps, plus `BigInteger` intermediates from every bit-field
operation). Single-threaded, that working set fits comfortably in L2/L3.
With 8+ threads each doing the same, combined working sets exceed L3
capacity — even on the V-cache CCD's 96 MB — and what used to be L3
hits become DRAM round-trips.

**Implication for Phase 2.** The right metric to minimize is
**per-thread working-set size**, not allocation count. These correlate
but they aren't the same:

- `HashMap` preallocation eliminates resize churn (fewer allocations)
  but leaves the final map size unchanged → small working-set effect.
- Copy-on-write `HeaderVal` / `StructVal` leaves allocation count
  roughly the same but **shares** the underlying map across fork
  branches → potentially order-of-magnitude working-set reduction on
  fork-heavy workloads.
- A `Long` fast path replaces `BigInteger` (~40 bytes of header + payload)
  with a primitive `long` (8 bytes, often register-resident after escape
  analysis) → shrinks every bit-field value by ~5×.

The highest-leverage changes are the ones that reduce how many bytes
each thread *touches* per packet. Allocation-count optimizations are
the secondary axis, valuable only to the degree that they translate
into footprint reduction.

> **Phase 2 postscript:** this framing turned out to be only half
> right. See the next section — the byte-footprint-reduction hypothesis
> was tested end-to-end in #522 and capped at +6.3%. The IPC / DRAM
> stalls Phase 1.5 measured are real, but they're driven by per-fork
> wrapper-object *churn* (allocation count × cache-line thrash), not
> by the byte count of the resident state. The diagnosis above
> correctly identified the cache-pressure symptom; the
> working-set-bytes framing was the wrong lever to pull.

## Phase 2: flat-buffer attempt (refuted direction)

Phase 1.5 identified "per-thread working-set bytes exceed L3" as the
bottleneck and pointed at shrinking per-packet state as the primary
lever. [smolkaj/4ward#522](https://github.com/smolkaj/4ward/pull/522)
tested that hypothesis end-to-end: replaced the heap-of-objects packet
state (many `HashMap`s across `HeaderVal` / `StructVal`) with one
contiguous `PacketBuffer` per packet plus thin views at static offsets.
The packet's field data dropped from ~12 MB of `HeaderVal` graph to
~540 bytes of bit-packed buffer. Fork became one `buffer.copyOf()` plus
a `rewire()` walk instead of a Value-tree `deepCopy`.

**Measured outcome** (AMD Ryzen 9 7950X3D, best 3-run mean):

| Workload | Baseline | Flat buffer + consolidation | Δ |
|---|---|---|---|
| wcmp×128 parallel | 1,528 pps | 1,625 pps | **+6.3%** |
| wcmp×128 sequential | 207 pps | 200 pps | within noise |
| direct parallel | 39,800 pps | 39,743 pps | within noise |

Short of the 2-3× target by a wide margin. A `skipDeepCopy()` oracle
benchmark in the same session capped the maximum possible win from
fork-copy elimination at ~16% — the 2-3× target was incompatible with
a data-layout-only change from the start.

**What the flat-buffer result means for the diagnosis.** The packet's
raw field bytes were never the bottleneck. What Phase 1.5's IPC
counters detected is real, but the cache pressure comes from:

- **Per-fork wrapper-object churn.** `HeaderVal`, `StructVal`,
  `HashMap`, and scope entries — ~55 Java object allocations per fork
  branch, mostly unchanged by consolidating the underlying byte
  storage. Each allocation touches a fresh cache line; the allocator,
  GC, and JIT traversals dominate the L3 footprint.
- **Per-read Value-wrapper allocation on the interpreter hot path.**
  Every `target.fields[name]` returns a `BitVal` / `IntVal` / `BoolVal`
  object. Absent a cache, fork branches re-allocate these for every
  field they read. The flat-buffer PR's `BufferBackedFieldMap` per-slot
  cache is what preserved parity on direct workloads — bypassing it in
  any follow-on optimization regressed by 8-14%.

**Refined diagnosis.** Shrinking per-packet byte footprint has a low
ceiling (the `skipDeepCopy` oracle, ~16%). The real lever is the
**per-fork wrapper-object graph** and the **per-read `Value` allocation
on the interpreter hot path**. Those are interpreter-level concerns,
not data-layout concerns — the existing `HeaderVal` / `StructVal` /
`HashMap` representation is fine scaffolding for the follow-ups that
actually move the needle.

**Specific optimisations tested and reverted in #522** (numbers in the
retrospective):

- Pre-resolved `FieldAccess` fast path that bypassed the per-slot cache
  → −14%.
- Copy-on-write on the per-slot cache → −2% (shared-flag check outweighs
  save; SAI branches write enough).
- Identity-based rewire dedup for aliased scope entries → −2%.
- Array-backed cache indexed by slot position → −8%.

These are catalogued so future attempts don't re-run them. See
`designs/flat_packet_buffer.md` for the full retrospective.

## Path forward

**Discipline.** Performance work is bounded by simplicity. The project
rules are explicit: "correctness over performance" and "readability over
performance." An optimization that makes the code harder to read or
reason about is not worth it, even if the benchmark improves. Each step
must clear that bar before it lands.

**Approach.** Measure first, optimize second — same mantra that governed
the lock-free dataplane work (where we discovered the lock wasn't the
bottleneck after all). **Profile with async-profiler before committing
to a direction.** Phase 2 is a case study in what happens when you skip
that step: the flat-buffer rewrite was a week of work motivated by a
hypothesis a 15-minute flame graph would have put lower on the list.

**Approach, concretely.** Build a "what if this optimization were
free?" oracle benchmark before writing the optimization. In Phase 2's
case, `skipDeepCopy()` was a three-line change that would have capped
the project's upside at 16% in Phase 1. Use similar oracles (stub out
table lookup, stub out `evalExpr`) to bound the ceiling on any future
candidate before starting.

**Scope.** Direct L3 is already at 94% efficiency with no meaningful
work left to do. "Optimize" here means closing the wcmp×128 gap —
bringing 45% efficiency closer to linear.

**What to optimize for.** Per the refined diagnosis above: per-fork
wrapper-object allocation count and per-read `Value` allocation on
the interpreter hot path. The earlier framing ("working-set bytes")
is a symptom, not a cause.

Candidate optimizations, ordered by expected impact (but *profile
before committing* — the oracle step):

0. **Interpreter hot-path refactor (new primary lever).** Three
   complementary directions, all interpreter-level:
   - Pre-resolve `FieldAccess` chains at pipeline load *and* give the
     fast path its own per-packet cache (the #522 attempt failed because
     it bypassed `BufferBackedFieldMap`'s cache — a correct version
     would maintain equivalent caching at interpreter scope).
   - Pool `HeaderVal` / `BufferBackedFieldMap` / scope HashMap
     instances in a thread-local scratch; reset on each fork rather
     than allocating fresh.
   - Arena-allocate fork-branch state in one slab so GC sees contiguous
     short-lived allocations instead of a graph.

   All three work on `main`'s current data representation; none of
   them require the #522 primitives. Run the oracle benchmark for each
   before starting.

1. **Copy-on-write `HeaderVal` / `StructVal` (persistent-map variant).**
   Historical top pick (prior to Phase 2). Share the underlying map
   between fork branches; copy only on first write to a given field,
   with the mutation model going from in-place to persistent. **Not to
   be confused with** the narrower "COW on the per-slot value cache"
   that #522 tested and reverted (−2%; shared-flag check outweighed
   savings). Phase 2 also tested a related cache-forwarding variant
   that delivered the +6.3% — the full persistent-map COW has a larger
   surface but shares the same ceiling dynamic — fork-copy is ~9% of
   per-fork time, so a perfect COW can't exceed that. Worth considering
   as a stacking optimisation on top of (0), not a replacement.

2. **`Long` fast path for narrow bit fields.** Use `Long` instead of
   `BigInteger` for `bit<N>` with N ≤ 63. A partial fast path exists
   in `matchesFieldMatch`; extend it to `BitVector` arithmetic. Shrinks
   each bit-field value from ~40 bytes (`BigInteger` object + int[]
   payload) to 8 bytes of primitive `long` (often register-resident
   after escape analysis). Orthogonal to (0) and (1); stacks on either.
   **Expected impact:** ~5× smaller per-value footprint, compounded
   across dozens of bit-fields per header. Hard to forecast as a
   single number without measurement.

3. **`HashMap` preallocation in `deepCopy`.** Two-line change;
   eliminates resize churn. Same final maps, just fewer intermediate
   allocations along the way. Worth doing for cheap, but don't expect
   it to move the scaling needle.
   **Expected impact:** ~2% (measured in isolation before Phase 1.5).

**The honest realization.** None of these alone guarantees linear
scaling. The interpreter hot path (0) is now the primary lever — the
flat-buffer attempt established that data layout alone caps at ~16%.
Hitting the north star likely requires (0) to deliver big, with (1)
and (2) stacking on top.

**Before committing to anything:** the simplicity budget matters. The
lock-free dataplane PR was a clear simplification AND had measurable
value (correctness improvement). A performance-only PR that adds
complexity for a bounded gain needs a stronger justification: a concrete
DVaaS workload that's blocked by the current throughput.

**Explicitly off the table:**

- **Replacing `for (x in list)` with index-based loops.** Kotlin idiom
  is the for-loop; index loops are a readability regression. Iterator
  allocation is real but not the current bottleneck.
- **Optimizing lookup (`toUnsignedLong`, hash index, LPM trie) for
  scaling reasons.** Lookup cost is real but does not affect scaling —
  direct L3 proves it scales cleanly. Lookup optimization is a separate
  absolute-throughput concern, not part of the scaling north star.
- **Disabling intra-packet parallelism.** Throughput-neutral in the
  parallel (inter-packet) mode (1,506 vs 1,501 pps — noise) but 3×
  regression in single-packet latency. Keep it for CLI/STF/playground
  users.

**Validation.** For every optimization, run the benchmark and verify:

1. **True single-thread throughput doesn't regress** — measured with
   intra-packet parallelism disabled (set
   `-Dfourward.simulator.intraPacketParallelism=false`).
2. **Parallel scaling efficiency improves** — the ratio
   `parallel_pps / (single_thread_pps × 16)` moves toward 1.0.
3. **No correctness regressions** — all tests still pass.

**Done when:** wcmp×128 parallel efficiency reaches ≥80% of linear on
16 physical cores. Hitting that target is not marginal tuning; the
flat-buffer experiment showed it requires interpreter-level work
(candidate 0 above), not just data-layout compaction.

## Non-goals

- **Beating BMv2 by more than we already do.** BMv2 is 6× slower on L3
  forwarding per the existing benchmark. Further widening the gap is
  nice, not required.
- **Optimizing at the expense of correctness or readability.** The
  project rule is "correctness over performance" and "readability over
  performance." An optimization that obfuscates the trace tree semantics
  or makes the simulator harder to reason about is not worth it.
- **Optimizing cold paths.** Pipeline loading, table writes, error
  handling — these are rare and their latency doesn't matter.

## Open questions

- **Why is parallel direct at 65% CPU utilization, not 100%?** The JVM
  has idle capacity that isn't being used — meaning the bottleneck isn't
  compute, it's dispatch (gRPC, coroutines, or `ForkJoinPool` task
  submission serial fraction). Not worth chasing — direct is already at
  94% efficiency despite leaving cores idle.
- **How much of the interpreter's per-fork cost is field access vs.
  table lookup vs. action dispatch?** Phase 2 established that fork-
  copy is only ~9%; the remaining ~91% is interpretation. Which slice
  of that is the biggest single target? A flame graph on wcmp×128
  parallel would answer this in minutes and is the prerequisite for
  candidate (0).
- **Why doesn't the 7950X3D's V-cache help?** Both CCDs measured
  identically despite one having 3× the L3 capacity. Possibilities: the
  working set genuinely exceeds 96 MB (but see Phase 2 — shrinking byte
  footprint didn't help, so the V-cache shouldn't have either);
  V-cache allocation policy disfavors short-lived allocation churn; or
  the test process was scheduled onto the non-V-cache CCD. Consistent
  with the refined diagnosis that cache-line churn, not total bytes,
  is the bottleneck.
