# Parallel Packet Scaling

**Status: Phase 1 complete (profiled and diagnosed)**

## North star

**Packet processing should scale linearly with physical cores.** For N
cores, the parallel `InjectPackets` RPC should deliver N× the throughput
of a single-threaded run. Each packet is independent of every other
packet (modulo a few shared stateful externs, which have their own
lock-free data structures) — the algorithm is embarrassingly parallel.
Anything well below linear is wasted hardware.

This is the benchmark we want to hit:

```
throughput(N cores) = N × throughput(1 core)
```

Real-world overheads mean we won't hit exactly linear. On a well-optimized
CPU-bound JVM workload with heavy allocation, 85-90% on 16 cores is
ambitious but reachable; 50% is common without work. Anything more than
~10% off demands an explanation.

### Terminology

**Parallel** = multiple tasks executing literally at the same instant on
different cores. **Concurrent** = multiple tasks making progress over
overlapping time, possibly interleaved on one core. Linear scaling is a
**parallelism** concept. The benchmark runs packets on distinct worker
threads via `ForkJoinPool`, scheduled onto distinct physical cores — that
is parallelism, not concurrency. The code uses "parallel" in its naming
(`measureParallel`, `parallelPoints`).

### Baseline methodology

"Single-thread" means exactly one core doing all the work for one packet
at a time. This requires temporarily disabling the within-packet
`parallelStream()` in `V1ModelArchitecture.buildTraceTree` — without
that change, single-threaded `InjectPacket` runs actually spread each
packet's fork branches across `ForkJoinPool`, using ~5 cores per packet
rather than 1. The right comparison for "N× on N cores" is **one thread
doing all the work for one packet** vs **N threads each doing their own
packets**.

## Current state

Verified measurements on a 16 physical core / 32 SMT thread AMD Ryzen 9
7950X3D, SAI P4 middleblock, 10k routes.

| Workload | Single-thread | Parallel | Speedup | **Efficiency vs 16 cores** | CPU utilization (parallel) |
|---|---|---|---|---|---|
| direct | 2,512 pps | 38,147 pps | **15.19×** | **95%** | 66% — idle headroom |
| wcmp×16+mirr | 678 pps | 5,534 pps | **8.16×** | **51%** | 94% — saturated |
| wcmp×128 | 203 pps | 1,521 pps | **7.49×** | **47%** | 83% — saturated |

**Direct L3 is essentially at linear scaling.** 15.19× on 16 physical
cores = 95% efficiency. The machine isn't even CPU-saturated (JVM
averaged 66% of total machine CPU), so the remaining 5% gap is dispatch
overhead (gRPC, coroutines, `ForkJoinPool` task submission serial
fraction), not compute. Not worth chasing.

**Fork-heavy workloads are at ~50% efficiency** and **efficiency degrades
slightly with higher fork counts** (51% at 32 branches per packet, 47% at
128). These workloads saturate the machine but each packet costs ~2× more
CPU under parallel load than under single-thread.

> **CPU utilization ≠ efficiency.** CPU utilization = how much of the
> machine is being used. Efficiency = speedup / theoretical max. Direct
> has low CPU utilization but high efficiency (finishes fast without
> needing all the cores). wcmp has high CPU utilization but low
> efficiency (uses all the cores but each packet costs more).

### The four-cell breakdown (wcmp128, for intuition)

The within-packet `parallelStream()` complicates the story. Here are all
four combinations, to disentangle:

| Mode | parallelStream | Throughput | Speedup vs true ST | CPU util |
|---|---|---|---|---|
| Sequential (1 packet at a time) | OFF | 203 pps | 1.00× | 3.4% |
| Sequential (1 packet at a time) | ON | 633 pps | 3.12× | — |
| Parallel (many packets on many cores) | OFF | 1,521 pps | **7.49×** | 83% |
| Parallel (many packets on many cores) | ON | 1,506 pps | 7.42× | 88% |

Observations:

1. **`parallelStream` ON vs OFF in the parallel row is a wash on
   throughput** (1,506 vs 1,521 — noise). Within-packet parallelism
   neither helps nor hurts multi-packet throughput. It costs ~5% more
   CPU for nothing (88% vs 83% utilization at the same throughput), but
   the difference is small.
2. **`parallelStream` ON helps single-packet latency 3×** (633 vs 203).
   Worth keeping for CLI, STF, and playground use cases where there's
   only one packet at a time.
3. **The true single-thread baseline is parallelStream OFF** (203 pps).
   That's what divides into parallel throughput to give the speedup.

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
   L1/L2. At 15 parallel threads, the combined ephemeral working set
   (HashMaps, BigIntegers, builders per thread) spills into L3, evicting
   each other. What was an L2 hit becomes an L3 hit (3× slower), or
   worse, a DRAM access (10× slower).

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
wcmp×128 single-thread vs wcmp×128 parallel. Both sides have
`parallelStream()` disabled to get a clean apples-to-apples comparison.
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

**Parallel scaling overhead (clean diff, both sides parallelStream OFF):**

| Delta | Frame | Source |
|---|---|---|
| **+7.3%** | `BigInteger.<init>` | Per-fork arithmetic intermediates |
| **+7.0%** | `HashMap.putVal` | Per-fork header/struct deep copy |
| **+2.4%** | `TraceEvent$Builder.buildPartial` | Per-fork trace event allocation |
| **+2.4%** | `BitVector.<init>` | Related to `BigInteger` allocation |
| **+2.3%** | `HashMap.put` | Per-fork header/struct deep copy |
| +0.9% | `HashMap.resize` | HashMap growth during deep copy |

HashMap put operations combined (`put` + `putVal` + `resize`) account
for **+10.2%** — the single largest overhead bucket. BigInteger
allocation is a close second at ~8% combined. Total allocation-related
scaling overhead: **~25-26%**.

### The mechanism, concretely

When 15 threads each process a fork-heavy packet, they all call
`HeaderVal.deepCopy()` and `StructVal.deepCopy()`, which do
`fields.mapValuesTo(mutableMapOf()) { ... }`. That allocates:

1. A new empty HashMap (default capacity 16)
2. New nodes for each key-value pair inserted
3. A resize when the map exceeds load factor

With 15 threads × 128 branches per packet × multiple HeaderVals/StructVals
per branch, the allocator is hit millions of times per second across all
threads. TLAB refills become frequent; HashMap resize compounds the
issue; `BigInteger.<init>` allocates fresh arrays for bit field
arithmetic on every operation. Each of these is cheap in isolation, but
in aggregate they dominate the scaling overhead.

This is the bottleneck. It's specifically a **fork-induced
allocation-pressure** problem, not a general scaling problem.

## The discipline

**Measure first, optimize second.** This is Track 10 Phase 1's mantra and
it applied to the lock-free dataplane too (where we discovered the lock
wasn't the bottleneck after all). The same principle applies here: any
optimization without a profiler-backed hypothesis is a guess.

### Phase 2: Optimize

**Discipline.** Performance work is bounded by simplicity. The project
rules are explicit: "correctness over performance" and "readability over
performance." An optimization that makes the code harder to read or
reason about is not worth it, even if the benchmark improves. Each step
must clear that bar before it lands.

**Approach.** One optimization at a time. Measure → fix the smallest
thing → re-measure → decide what's next. Don't pre-commit to a multi-step
plan beyond the next step.

**Scope.** Phase 1 showed the scaling gap is **specific to fork-heavy
workloads**. Direct L3 is already at 95% efficiency with no meaningful
work left to do. So "optimize" here means "close the wcmp×128 gap" —
bring 47% efficiency closer to linear.

The gap comes from per-fork allocation pressure. At realistic fork
counts (128+) the two biggest buckets are roughly equal:

- **~10% HashMap operations** from per-fork header/struct deep copies
- **~8% BigInteger allocation** from per-fork bit field arithmetic
- **~5% TraceEvent builders + BitVector init** — harder to avoid

Candidate optimizations, in order of complexity:

1. **HashMap preallocation in deepCopy.** Eliminates resize churn.
   Two-line change. Expected impact: ~2% (measured — real but small).

2. **Copy-on-write for HeaderVal/StructVal.** Share the underlying map
   between fork branches; copy only on write. Most branches touch a
   small subset of fields, so most of the deep-copy work is wasted.
   Structural change — mutation model goes from in-place to persistent.
   Expected impact: most of the ~10% HashMap bucket.

3. **`Long` fast path for narrow bit fields.** Use `Long` instead of
   `BigInteger` for `bit<N>` ≤ 63. Partially exists in
   `matchesFieldMatch`; extend to `BitVector` arithmetic. Expected
   impact: most of the ~8% BigInteger bucket.

**The honest realization:** fixing any one of these only closes a
fraction of the gap. Getting to truly linear scaling would require
fixing *all* of them — a broader refactor than any single PR. A single
win of 10% (HashMap) or 8% (BigInteger) takes efficiency from 47% to
maybe 55% — meaningful but still well below linear.

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
- **Disabling within-packet `parallelStream()`.** Throughput-neutral in
  parallel mode (1,506 vs 1,521 pps — noise) but 3× regression in
  single-packet latency. Keep it for CLI/STF/playground users.

**Also off the table:** optimizing lookup (`toUnsignedLong`, hash index,
LPM trie) for scaling reasons. Lookup cost is real but does not affect
scaling — direct L3 proves it scales cleanly. Lookup optimization is a
separate absolute-throughput concern, not part of this north star.

### Phase 3: Validate

For every optimization, run the benchmark and verify:

1. **True single-thread throughput doesn't regress** — measured with
   within-packet `parallelStream()` disabled.
2. **Parallel scaling efficiency improves** — the ratio of
   `parallel_pps / (single_thread_pps × 16)` moves toward 1.0.
3. **No correctness regressions** — all tests still pass.

**Done when:** wcmp×128 parallel efficiency reaches ≥80% of linear on
16 physical cores. (Direct L3 is already at 95%.)

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

- **Why is parallel direct at 66% CPU utilization, not 100%?** The JVM
  has idle capacity that isn't being used — meaning the bottleneck isn't
  compute, it's dispatch (gRPC, coroutines, or `ForkJoinPool` task
  submission serial fraction). Not worth chasing — direct is already at
  95% efficiency despite leaving cores idle.
- **Is `InjectPackets` (streaming RPC) the right benchmark?** Real DVaaS
  workloads may use many short-lived streams, not one long stream of
  10k packets. Scaling characteristics could differ.
- **How much does copy-on-write help in practice?** The 17.9% HashMap
  overhead is the ceiling; actual gain depends on how much of that is
  allocation volume vs allocator contention. Measurement will tell.
