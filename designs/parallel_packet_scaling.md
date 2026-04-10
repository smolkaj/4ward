# Parallel Packet Scaling

**Status: Phase 1 complete (profiled and diagnosed)**

## North star

**Packet processing should scale linearly with cores.** For N cores, the
concurrent `InjectPackets` RPC should deliver N× the throughput of the
sequential `InjectPacket` RPC. Packet processing is embarrassingly
parallel — each packet is independent of every other packet (modulo a few
shared stateful externs, which have their own lock-free data structures).
Anything less than linear scaling is wasted hardware.

This is the benchmark we want to hit:

```
throughput(N cores) = N × throughput(1 core)
```

It's ambitious by design. Real-world overheads mean we won't hit exactly
linear, but anything more than ~10% off (e.g., 14.4x on 16 physical cores)
should demand an explanation.

**Baseline methodology.** "Single-thread" means exactly one core doing
all the work — including any within-packet parallelism that would
otherwise fan out to the `ForkJoinPool`. This matters because
`V1ModelArchitecture.buildTraceTree` uses `parallelStream()` for fork
branches, so the "sequential" `InjectPacket` RPC actually uses multiple
cores per packet. The right comparison is "one thread doing all the
work" vs "N threads each doing their own packets" — measured with the
`parallelStream()` temporarily disabled for the single-thread baseline.

## Current state

Verified measurements on a 16 physical core / 32 SMT thread AMD Ryzen 9
7950X3D, SAI P4 middleblock, 10k routes. All numbers are **true
single-thread** vs **concurrent** (`InjectPackets` RPC with 50k packets
in flight). Single-thread is measured with the within-packet
`parallelStream()` in `V1ModelArchitecture.buildTraceTree` temporarily
disabled, so exactly one core does all the work.

| Workload | Single-thread | Concurrent | Physical cores used (con) | Speedup | **Efficiency vs 16 cores** |
|---|---|---|---|---|---|
| direct | 2,512 pps | 38,147 pps | 10.6 | **15.19×** | **95%** |
| wcmp×16+mirr | 678 pps | 5,534 pps | 15.0 | **8.16×** | **51%** |

**Direct L3 is essentially at linear scaling.** 15.19× on 16 cores = 95%
efficiency. The machine isn't even saturated (10.6/16 cores used). No
meaningful work to do here.

**Fork-heavy workloads (wcmp×16+mirr) are at ~51% efficiency.** That's
the real scaling gap. Concurrent mode saturates the machine (15/16 cores)
but each packet costs ~2× more CPU in concurrent than in single-thread.

## Why the gap? (Phase 1 profile findings)

Two JFR profiles compared: direct single-thread vs direct concurrent, and
wcmp×16+mirr single-thread vs wcmp×16+mirr concurrent. The diffs tell
completely different stories.

### Direct L3 — scales cleanly

**Dominant cost (same in both modes):** `TableStoreKt.toUnsignedLong(ByteString)`
at 65-73% of CPU. The O(n) linear scan over 10k LPM entries converts each
entry's match field bytes to a Long for comparison.

**Concurrent scaling overhead:** `TableStore.lookup` +4.3% — modest cache
pressure from 10 threads each scanning the same 10k-entry table. The
working set fits in L2/L3 well enough that contention is minimal.

**Why direct scales well:** no trace tree forks → no header/struct deep
copies → no allocation-heavy per-fork work. Each thread does the same
lookup work independently. Cache-friendly, allocator-friendly.

### wcmp×16+mirr — allocator contention

The workload has a 16-way action selector fork (WCMP) and a 2-way clone
fork (mirror), producing 32 branches per packet. Each branch must
deep-copy the packet state (headers + structs) so the branches don't
interfere with each other.

**Concurrent scaling overhead (diff vs single-thread):**

| Delta | Frame | Source |
|---|---|---|
| **+17.9%** | `HashMap.put` / `putVal` / `getNode` / `resize` | Per-fork header/struct deep copy |
| **+5.9%** | `BigInteger.<init>` | Per-fork arithmetic intermediates |
| **+2.6%** | `TraceEvent$Builder.buildPartial` | Per-fork trace event allocation |
| -21.8% | `toUnsignedLong` | Same absolute cost; smaller % of concurrent total |

**~26% of concurrent CPU is allocation-related overhead** — time spent in
HashMap, BigInteger, and protobuf builder construction that only shows
up under multi-threaded load. Under single-thread, these paths exist but
don't dominate. Under concurrent load, allocator contention (TLAB
exhaustion, shared-heap contention on node allocation, HashMap resize
under pressure) inflates their cost per operation.

### The mechanism, concretely

When 15 threads each process a fork-heavy packet, they all call
`HeaderVal.deepCopy()` and `StructVal.deepCopy()`, which do
`fields.mapValuesTo(mutableMapOf()) { ... }`. That allocates:

1. A new empty HashMap (default capacity 16)
2. New nodes for each key-value pair inserted
3. A resize when the map exceeds load factor

With 15 threads × ~32 branches per packet × multiple HeaderVals/StructVals
per branch, the allocator is hit hundreds of thousands of times per
second from many threads at once. TLAB exhaustion forces threads into
the shared allocator path; HashMap resize compounds the issue; cache
lines for the HashMap's internal `table` array bounce between cores.

This is the bottleneck. It's specifically a **fork-induced
allocation-pressure** problem, not a general scaling problem.

## The discipline

**Measure first, optimize second.** This is Track 10 Phase 1's mantra and
it applied to the lock-free dataplane too (where we discovered the lock
wasn't the bottleneck after all). The same principle applies here: any
optimization without a profiler-backed hypothesis is a guess.

### Phase 1: Profile

Wire `async-profiler` (CPU + allocation) into the `DataplaneBenchmark`
target. Collect flame graphs for sequential and concurrent runs on the
same workload. Diff them. The concurrent flame graph should show where
time is spent that the sequential graph doesn't — that's the scaling
bottleneck.

Specific questions the profile should answer:

- **CPU time**: is the concurrent path CPU-bound (good, means we're
  running), GC-bound (bad, means allocation is the bottleneck), or
  lock-bound (shouldn't happen post-lock-free, but worth checking)?
- **Allocation**: what objects are allocated per packet? Which are the
  fattest? Are any allocations avoidable?
- **GC**: what's the young-gen pause rate and total pause time?
- **Hot paths**: top 10 self-time methods in each run. Any surprises?

**Done when:** we have a documented hypothesis ("the bottleneck is X,
because Y in the flame graph shows Z") and a concrete optimization target.

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
work left to do. So "optimize" here means "close the wcmp×16+mirr gap" —
bring 51% efficiency closer to direct's 95%.

The gap comes from per-fork header/struct deep copies allocating new
HashMaps under heavy concurrent load. To close it, we need to **reduce
per-fork allocation volume**. The available mechanisms, in order of
complexity:

1. **HashMap preallocation in deepCopy.** Eliminates resize churn.
   Two-line change. Expected impact: ~2% (measured — the resize cost
   is real but small).

2. **Copy-on-write for HeaderVal/StructVal.** Share the underlying map
   between branches; copy only on write. Most forks touch a small
   subset of fields, so most of the deep-copy work is wasted. Biggest
   potential impact: approaches the 17.9% HashMap overhead budget.

3. **Value arena allocation.** Per-thread pools for BitVal/BitVector
   objects. Targets the 5.9% BigInteger allocation.

**Next step: prototype copy-on-write HeaderVal/StructVal.** It's the
largest potential win and it's contained to `Values.kt`. The API stays
the same; only the deepCopy semantics change. Risk: mutation semantics
during a fork branch can't accidentally affect sibling branches — the
copy-on-write mechanism must be correct.

**Before committing:** measure it. If copy-on-write doesn't close a
significant part of the gap, don't land it. The simplicity budget is
real and the test is "did efficiency improve measurably?"

**Explicitly off the table:** replacing `for (x in list)` with
index-based loops to reduce iterator allocation. Kotlin idiom is the
for-loop; index loops are a readability regression. Iterator allocation
is not the current bottleneck (GC pauses <0.5% of wall time).

**Also off the table:** optimizing lookup (`toUnsignedLong`, hash index,
LPM trie) for scaling reasons. Lookup cost is real but does not affect
scaling — direct L3 proves it scales cleanly. Lookup optimization is a
separate absolute-throughput concern, not part of this north star.

### Phase 3: Validate

For every optimization, run the benchmark and verify:

1. **True single-thread throughput doesn't regress** — measured with
   within-packet `parallelStream()` disabled.
2. **Concurrent scaling efficiency improves** — the ratio of
   `concurrent_pps / (single_thread_pps × 16)` moves toward 1.0.
3. **No correctness regressions** — all tests still pass.

**Done when:** wcmp×16+mirr concurrent efficiency reaches ≥80% of
linear on 16 physical cores. (Direct L3 is already at 95%.)

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

- **Why does concurrent direct use only 10.6 physical cores (not 16)?**
  Direct completes 50k packets in 1.3s; the machine isn't even saturated.
  Likely the gRPC server's request dispatch or the `ForkJoinPool` task
  submission path is the serial bottleneck. Not worth chasing — direct
  is already at 95% efficiency on the cores it does use.
- **Is `InjectPackets` (streaming RPC) the right benchmark?** Real DVaaS
  workloads may use many short-lived streams, not one long stream of
  10k packets. Scaling characteristics could differ.
- **How much does copy-on-write help in practice?** The 17.9% HashMap
  overhead is the ceiling; actual gain depends on how much of that is
  allocation volume vs allocator contention. Measurement will tell.
