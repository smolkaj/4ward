# Parallel Packet Scaling

**Status: Phase 1 complete (profiled and diagnosed)**

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
and there are several that matter:

1. **The JVM allocator.** Allocations normally go into a per-thread TLAB
   (thread-local allocation buffer) — fast, no contention. But when a
   TLAB fills, the thread has to request a new one from the shared heap,
   which is a coordination point. At high allocation rates, TLAB refills
   become frequent and contention becomes visible.

2. **L3 cache pressure.** Each physical core has private L1/L2; all
   cores share L3. Single-threaded, one packet's working set fits in
   L1/L2. With many threads running in parallel, their combined
   ephemeral working sets (HashMaps, BigIntegers, builders per thread)
   spill into L3, evicting each other. What was an L2 hit becomes an
   L3 hit (3× slower), or worse, a DRAM access (10× slower).

3. **Memory bandwidth.** DRAM has finite bandwidth. Fresh allocations
   touch new cache lines; at gigabytes per second of allocation across
   all threads, memory bandwidth becomes a real constraint.

4. **Garbage collection.** G1GC steals CPU cycles for concurrent marking
   and evacuation. At high allocation rates, GC runs more often,
   reducing effective throughput even when pause times look small.

5. **Cache coherency.** Even read-only data has coherency cost if
   writes happen nearby (false sharing). JVM object allocations are
   typically on fresh cache lines, so this is usually a non-issue — but
   it can bite.

6. **SMT interference.** Two SMT threads on the same physical core
   share L1, L2, and execution units. For memory-bound work, they can
   slow each other down.

**For CPU-bound JVM workloads with heavy allocation, 50-60% efficiency
on 16 cores is a normal outcome, not a failure.** The algorithm's
embarrassing parallelism is preserved in the *logical* decomposition —
each packet is still independent. But the *physical* execution shares
allocator, caches, and memory bandwidth across all threads.

This means: closing the gap to linear scaling requires reducing shared
resource pressure, not restructuring the algorithm. Specifically:

- **Reduce allocation volume** → less TLAB contention, less GC, less
  memory bandwidth usage
- **Reduce working set size** → less L3 pressure, more L2 hits
- **Reduce per-allocation size** → fewer cache line fetches

## Phase 1 profile findings

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

### wcmp×128 — allocator contention

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

### The mechanism, concretely

When many `ForkJoinPool` workers each process a fork-heavy packet at
once (roughly 25-30 hw threads busy on this machine, judging by the
81% CPU utilization in the wcmp×128 parallel run), they all call
`HeaderVal.deepCopy()` and `StructVal.deepCopy()`, which do
`fields.mapValuesTo(mutableMapOf()) { ... }`. That allocates:

1. A new empty HashMap (default capacity 16)
2. New nodes for each key-value pair inserted
3. A resize when the map exceeds load factor

With many threads × 128 branches per packet × multiple HeaderVals/StructVals
per branch, the allocator is hit millions of times per second across all
threads. TLAB refills become frequent; HashMap resize compounds the
issue; `BigInteger.<init>` allocates fresh arrays for bit field
arithmetic on every operation. Each of these is cheap in isolation, but
in aggregate they dominate the scaling overhead.

This is the bottleneck. It's specifically a **fork-induced
allocation-pressure** problem, not a general scaling problem.

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

**Scope.** Phase 1 showed the scaling gap is **specific to fork-heavy
workloads**. Direct L3 is already at 94% efficiency with no meaningful
work left to do. So "optimize" here means "close the wcmp×128 gap" —
bring 45% efficiency closer to linear.

The gap comes from per-fork allocation pressure. At realistic fork
counts (128+) the two biggest buckets are roughly equal:

- **~9% BigInteger allocation** from per-fork bit field arithmetic
- **~9% HashMap operations** from per-fork header/struct deep copies
- **~5% TraceEvent builders + BitVector init** — harder to avoid

Candidate optimizations, in order of complexity:

1. **HashMap preallocation in deepCopy.** Eliminates resize churn.
   Two-line change. Expected impact: ~2% (measured — real but small).

2. **`Long` fast path for narrow bit fields.** Use `Long` instead of
   `BigInteger` for `bit<N>` ≤ 63. Partially exists in
   `matchesFieldMatch`; extend to `BitVector` arithmetic. Expected
   impact: most of the ~9% BigInteger bucket.

3. **Copy-on-write for HeaderVal/StructVal.** Share the underlying map
   between fork branches; copy only on write. Most branches touch a
   small subset of fields, so most of the deep-copy work is wasted.
   Structural change — mutation model goes from in-place to persistent.
   Expected impact: most of the ~9% HashMap bucket.

**The honest realization:** fixing any one of these only closes a
fraction of the gap. Getting to truly linear scaling would require
fixing *all* of them — a broader refactor than any single PR. A single
win of ~9% takes efficiency from 45% to maybe 55% — meaningful but
still well below linear.

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
16 physical cores. (Direct L3 is already at 94%.)

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
- **How much does copy-on-write help in practice?** The ~9% HashMap
  overhead is the ceiling; actual gain depends on how much of that is
  allocation volume vs allocator contention. Measurement will tell.
