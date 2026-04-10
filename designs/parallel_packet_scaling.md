# Parallel Packet Scaling

**Status: Phase 1 complete (profiled)**

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
linear, but anything more than ~10% off (e.g., 14.4x on 16 cores) should
demand an explanation.

## Current state

Baseline measurements on a 16-core (32-thread SMT) AMD Ryzen 9 7950X3D,
SAI P4 middleblock, 10k routes:

| Metric | Sequential | Concurrent | Speedup |
|--------|-----------|------------|---------|
| direct | 2,551 pps | 32,040 pps | **12.6×** |
| wcmp×16 | 1,743 pps | 10,561 pps | **6.1×** |
| wcmp×16+mirr | 1,194 pps | 5,555 pps | **4.7×** |

Direct is at 12.6× of 16× (79% efficiency). WCMP is worse — only 6.1× on
16 cores (38% efficiency). The trace-tree-heavy workloads scale the worst.

## Why the gap from 16×?

We don't know. This is the most important sentence in the document.

Possible causes, ranked by my prior:

1. **GC pressure.** Packet processing allocates heavily: protobuf trace
   tree builders, `BigInteger` wrappers for bit<N> arithmetic, lists for
   match scoring, fresh `TraceTree` objects per fork. 16 threads allocating
   in parallel stress the JVM allocator and trigger frequent young-gen
   collections. GC pauses serialize all threads.

2. **Protobuf allocation on the hot path.** Every table hit builds a new
   `TableEntry`, every trace event builds a new event proto. Protobuf
   builders are not cheap — each `.build()` call walks all fields and
   allocates.

3. **Memory bandwidth / L3 cache pressure.** 16 threads reading the same
   forwarding snapshot means the cache lines are replicated across 16
   L1/L2 caches. L3 is shared but limited. `BigInteger` objects and
   protobuf messages are heap-allocated, not inlined.

4. **`BigInteger` overhead.** Every `bit<N>` operation allocates a new
   `BigInteger`. For wide fields (IPv6 at 128 bits) this is unavoidable,
   but for narrow fields (ports, VRF IDs) it's wasteful.

5. **Amdahl's law — serial fraction.** Some work is inherently serial:
   gRPC stream collection, `ForkJoinPool` task submission, result joining.
   At high parallelism, the serial fraction caps speedup.

6. **Thread scheduling overhead.** `ForkJoinPool` task submission has
   per-task overhead. For packets that process in ~400μs each, scheduling
   overhead could be significant.

7. **WCMP trace tree fan-out.** WCMP×16 forks 16 ways, creating 16× more
   trace events and 16× more allocation per packet. This could explain why
   WCMP scales worse than direct.

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

#### Phase 1 results

JFR profile of the full `DataplaneBenchmark` run (sequential + concurrent
workloads, SAI P4 middleblock, 10k routes, 16-core machine). 1507 CPU
samples, ~18s of runtime.

**Top packet-processing CPU (274 samples in `lookup` / `processPacket`
paths):**

| % of run | Method | Why |
|---|---|---|
| **68%** | `TableStoreKt.toUnsignedLong(ByteString)` | Per-FieldMatch byte→long conversion in `matchLong` |
| 11% | `TableStore.lookup` self | The for-loop scanning all 10k entries |
| 11% | `HashMap.getNode` | Delegating-property lookups (`snapshot.tables`) |
| <2% | `matchLong` self | The actual comparison after conversion |
| <2% | `scoreEntry` self | LPM prefix + ternary priority bookkeeping |

**The bottleneck is `toUnsignedLong`.** Every table lookup walks every
entry (O(n) scan, 10k entries) and for each entry calls `toUnsignedLong`
on each match field's `ByteString`, allocating no objects but doing a
loop over `byteAt(i)` per byte. For 10k LPM entries on a 32-bit IPv4
field, that's 10k × 4 bytes = 40k `byteAt` calls per packet lookup.

**Allocation profile (27 GB sampled across 18s):**

| % | Class | Source |
|---|---|---|
| 42% | `ArrayList$Itr` | Kotlin `for (x in list)` everywhere |
| 14% | `Collections$UnmodifiableCollection$1` | Iterators for unmodifiable collections |
| 6% | `BigInteger` | Wide bit field arithmetic |
| 3% | `TraceEvent$Builder` | Protobuf trace event allocation |

GC was active but pauses were short (~1ms each, ~0.3% of wall time). GC
is not the dominant bottleneck — allocation pressure exists but isn't
causing pauses long enough to matter at this benchmark scale.

**Conclusion:** The scaling ceiling is **table lookup cost**, not
synchronization, GC, or allocation. The fix is structural: avoid the
O(n) byte-by-byte conversion, either by caching the long values at
insert time or by replacing the linear scan with an O(1) hash index for
exact-match tables.

### Phase 2: Optimize

Targets ordered by expected impact, based on Phase 1 profile:

1. **Pre-compute Long values for match field bytes at insert time.**
   Instead of converting `ByteString → Long` on every match, store the
   converted Long alongside each entry. Eliminates the 68% hot spot.
   Easy: single field on the per-table entry storage. Caveat: only works
   for fields ≤ 63 bits; wider fields fall back to `BigInteger`.

2. **Hash index for exact-match tables.** O(1) lookup instead of O(n)
   linear scan. Even with optimization #1, the linear scan over 10k
   entries is wasteful for exact-match. Already on the roadmap.

3. **LPM trie for LPM-only tables.** O(W) lookup where W is the bit
   width, instead of O(n). For SAI P4 routing tables, W=32 (IPv4) or
   W=128 (IPv6); n is in the thousands, so the trie wins.

4. **Iterator allocation reduction.** Replace `for (x in list)` with
   index-based loops in the hot path (`scoreEntry`, `lookup`,
   `matchesFieldMatch`). Eliminates the 56% iterator allocation churn.
   Per-packet GC pressure isn't the bottleneck *yet*, but it would be
   after the lookup optimizations land.

5. **`Long` fast path for narrow bit fields.** Use `Long` instead of
   `BigInteger` for `bit<N>` ≤ 63. Already partially done in
   `matchesFieldMatch`; extend to `BitVector` arithmetic.

Items 1 and 2 should be done first. Item 3 is bigger but could be
deferred until 1+2 ship and we re-measure. Items 4 and 5 are
allocation-side optimizations that pay off once compute is no longer
the bottleneck.

### Phase 3: Validate

For every optimization, run the benchmark on both sequential and
concurrent paths and verify:

1. **Sequential throughput doesn't regress** (no CPU cost added to the
   single-core path).
2. **Concurrent scaling improves** (closer to N× on N cores).
3. **No correctness regressions** (all 63+ tests still pass).

**Done when:** concurrent throughput reaches ≥90% of N× sequential
throughput on the SAI P4 middleblock workload.

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

- **Phase 1 used JFR, not async-profiler.** JFR's allocation sampling is
  coarser. async-profiler would give a more precise allocation flame graph
  if we want one later. JFR's CPU sampling was sufficient to identify the
  68% hot spot.
- **Is `InjectPackets` (streaming RPC) the right benchmark?** Real DVaaS
  workloads may use many short-lived streams, not one long stream of
  10,000 packets. The scaling characteristics could differ.
- **What's the theoretical floor for table lookup?** Even with a hash
  index, an exact-match lookup needs to hash the key, walk a bucket,
  and compare. ~50ns per lookup is plausible — that's 20M lookups/sec
  per core, vs the current ~2,500 packets/sec/core sequential. The gap
  would be in trace tree construction, not lookup.
