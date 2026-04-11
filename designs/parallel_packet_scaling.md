# Parallel Packet Scaling

**Status: Phase 1.5 complete (empirically validated; diagnosis refined)**

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
and there are several that matter. These are the six candidate
mechanisms we went into Phase 1.5 with; the verdicts in brackets come
from the measurement work below.

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
   **[Contributory — shares root cause with (2). See Phase 1.5 #6.]**

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

- **Reduce working set size** → less L3 pressure, fewer DRAM misses
- **Reduce per-allocation size** → fewer cache lines touched per object
- **Reduce allocation volume** → less memory bandwidth usage, fewer
  TLAB refills

## Phase 1 profile findings

JFR CPU profiles tell us *where* time is spent but not *why*. What
follows are the raw profile observations from Phase 1; the Phase 1.5
deep-dive reinterprets them in light of hardware counter measurements.
Read them together — the profile numbers are right, the initial
interpretation ("allocator-CPU-bound") turned out to be partially
wrong.

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
at ~9% combined. Total allocation-related scaling overhead: **~23-25%**.

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

The Phase 1 profile told us *where* CPU time was spent
(`HashMap.putVal`, `BigInteger.<init>`, etc.). It did not tell us
*why*. Without that, Phase 2 priorities are guesses. Phase 1.5
measures the six candidate mechanisms directly using JFR event data,
hardware performance counters, and CPU pinning experiments.

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
| 6 | SMT interference | **Refuted** | Physical-only pinning 1436 vs 1460 baseline, within noise |

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
stall. `(228K − 73K) × 200 cycles ≈ 31M cycles ≈ 9 ms` of extra
per-core stall per packet. The parallel per-packet core-time is ~16 ms
vs sequential ~5 ms; the 9 ms DRAM-stall budget accounts for most of
the 11 ms gap.

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

Three observations:

1. **Saturation within a single CCD.** 8 workers on one CCD ≈ 8
   workers unpinned (1227 ≈ 1235 from the scaling curve). The
   bottleneck fills up at 8 threads on a single CCD — within one L3
   domain, we hit the ceiling long before we run out of cores.

2. **3D V-cache doesn't help this workload.** CCD0 and CCD1 perform
   identically (1227 vs 1206). If V-cache were buying us anything,
   the V-cache CCD should be noticeably faster. Either the working
   set already exceeds 96 MB, or the allocation churn pattern doesn't
   benefit from the extra capacity. Either way: not a factor.

3. **The two CCDs don't scale additively.** CCD0 alone 1227 +
   CCD1 alone 1206 "should" sum to ~2400. Running both together: 1436.
   The second CCD contributes only 17% of its standalone capacity once
   the first is active. The only resource the CCDs share is the
   memory controller / DRAM bandwidth — which is exactly what a
   DRAM-bound diagnosis predicts.

Also: `ls_any_fills_from_sys.far_cache` is **zero** in both modes. No
cross-CCD coherency traffic. The OS scheduler keeps threads on their
home CCD. Inter-CCD contention is not the story.

### Reframed diagnosis

The Phase 1 profile showed +9% HashMap and +9% BigInteger in the
parallel-overhead diff, and the initial read was "allocator CPU cost."
Phase 1.5 shows that framing was misleading.

The extra cycles are real, and they *do* happen inside `HashMap.putVal`
and `BigInteger.<init>`. But they're not allocator fast-path cycles —
they're memory-stall cycles. Each `putVal` that shows up at +7% on the
CPU profile is spending hundreds of cycles waiting for its new node's
cache line to arrive from DRAM, because the L3 has been evicted by
other threads doing the same thing.

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

## Phase 2: optimize (proposed)

**Discipline.** Performance work is bounded by simplicity. The project
rules are explicit: "correctness over performance" and "readability over
performance." An optimization that makes the code harder to read or
reason about is not worth it, even if the benchmark improves. Each step
must clear that bar before it lands.

**Approach.** Measure first, optimize second — same mantra that governed
the lock-free dataplane work (where we discovered the lock wasn't the
bottleneck after all). One optimization at a time. Measure → fix the
smallest thing → re-measure → decide what's next. Don't pre-commit to a
multi-step plan beyond the next step.

**Scope.** Direct L3 is already at 94% efficiency with no meaningful
work left to do. "Optimize" here means closing the wcmp×128 gap —
bringing 45% efficiency closer to linear.

**What to optimize for.** Phase 1.5 reframed the target. Minimize
**per-thread working-set size**, not allocation count. Every candidate
should be evaluated on "does it reduce the bytes each thread touches
per packet" — that's what directly reduces L3 footprint and therefore
DRAM miss rate. Allocation count reductions help only to the degree
they shrink the hot working set.

Candidate optimizations, reordered by expected impact on working-set
size:

1. **Copy-on-write `HeaderVal` / `StructVal`.** Share the underlying
   map between fork branches; copy only on first write. Each fork
   branch typically touches a small subset of fields, so most of the
   deep-copy work produces bytes that are allocated, filled, and
   evicted without ever being read. COW is the only optimization on
   this list that *directly* shrinks per-thread working set — on
   fork-heavy workloads, potentially by an order of magnitude.
   Structural change (mutation model goes from in-place to persistent)
   but the public interface stays the same.
   **Expected impact:** this is where the ceiling lives. Actual gain
   depends on what fraction of deep-copied fields are never read by
   the branch; measurement will tell.

2. **`Long` fast path for narrow bit fields.** Use `Long` instead of
   `BigInteger` for `bit<N>` with N ≤ 63. A partial fast path exists
   in `matchesFieldMatch`; extend it to `BitVector` arithmetic.
   Doesn't change map structure, but shrinks each bit-field value from
   ~40 bytes of `BigInteger` object + int[] payload to 8 bytes of
   primitive `long` (often register-resident after escape analysis).
   Bit-fields are densely packed in header maps, so this compounds
   the working-set win from (1).
   **Expected impact:** ~5× smaller per-value footprint, compounded
   across dozens of bit-fields per header. Hard to forecast as a
   single number without measurement.

3. **`HashMap` preallocation in `deepCopy`.** Two-line change;
   eliminates resize churn. Same final maps, just fewer intermediate
   allocations along the way. Worth doing for cheap, but don't expect
   it to move the scaling needle.
   **Expected impact:** ~2% (measured in isolation before Phase 1.5).

**The honest realization.** None of these alone guarantees linear
scaling. Working-set reduction from (1) has the highest ceiling by
far, but its actual size depends on how much of the deep-copy work is
wasted in the average fork branch — an open empirical question.
Getting to truly linear would likely require (1) to deliver big *and*
stacking (2) on top.

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
16 physical cores. (Direct L3 is already at 94%.) Phase 1.5 showed the
current ceiling is a hard one — hitting ≥80% is not a marginal
improvement but requires breaking the L3-capacity bottleneck, which is
exactly what (1) and (2) above are designed to do.

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
- **How much does copy-on-write help in practice?** The ceiling is set
  by what fraction of deep-copied fields are *never read* in the
  average fork branch. For wcmp×128 we don't know this fraction yet; it
  could be small (5-10%) if most branches read most of the header, or
  large (≥90%) if branches mostly touch the fields affected by the
  selected action. The first prototype should measure this before
  committing to the full refactor.
- **Why doesn't the 7950X3D's V-cache help?** Both CCDs measured
  identically despite one having 3× the L3 capacity. Possibilities: the
  working set genuinely exceeds 96 MB; V-cache allocation policy
  disfavors short-lived allocation churn; or the test process was
  scheduled onto the non-V-cache CCD. Not worth chasing for the scaling
  question (the answer is still "reduce working-set size") but would be
  nice to understand.
