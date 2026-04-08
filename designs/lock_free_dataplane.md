# Lock-Free Dataplane

**Status: proposed**

## Problem

Dataplane packet processing is embarrassingly parallel, but throughput
doesn't scale with cores. The bottleneck is a global `ReadWriteMutex` in
`P4RuntimeService` — every packet must acquire the read lock before touching
the simulator, and every control-plane write takes the write lock, blocking
all packets. Even read locks aren't free: `ReentrantReadWriteLock` uses
atomic CAS and memory barriers internally, so N threads hammering
`readLock().lock()` contend on the lock's internal sync object regardless of
whether any writer is present.

## Design

Replace the global read-write lock with **immutable forwarding snapshots**
and a **volatile pointer swap**. The result is zero synchronization on the
packet path and a simpler correctness argument ("the snapshot is immutable by
construction" vs. "did every caller remember the lock?").

### Core mechanism

```
                Control plane                    Data plane
                ─────────────                    ──────────
  1. deepCopy() current state         ┌─→  volatile read (one pointer load)
  2. apply mutations to the copy      │    process packet against snapshot
  3. volatile write ─────────────────┘    (no lock, no CAS, no contention)
```

A single `@Volatile` field holds the current `ForwardingState`. Packet
threads read the pointer (one memory barrier — free on x86) and execute
against the immutable snapshot. Control-plane writes build a new state via
`deepCopy()`, apply mutations, and swap the pointer. Writers synchronize
only with each other (a simple `Mutex` suffices since P4Runtime already
serializes writes).

### Batch consistency

The stream API's `InjectPackets` RPC processes a batch of packets. Today,
each packet independently acquires and releases the read lock, so a
concurrent control-plane write can slip in mid-batch. The new design is
strictly better: grab the snapshot once at the start of the batch, pass it
to every packet:

```kotlin
val snapshot = simulator.currentState  // one volatile read
requests.collect { req ->
    ForkJoinPool.commonPool().submit {
        broker.processPacket(snapshot, req.port, req.payload)
    }
}
```

Single-packet injection and `streamChannel` read `currentState` per packet,
getting whatever version is current at that moment.

### Stateful objects

Tables, default actions, clone sessions, multicast groups, action profiles,
and value sets are read-only during forwarding — they fit the
immutable-snapshot model perfectly.

Two object types are mutated during packet processing:

**Direct counters** — incremented on every table hit via
`directCounterIncrement()` (currently `@Synchronized`). This is a
read-modify-write on an `IdentityHashMap<TableEntry, CounterData>`. Replace
with a `ConcurrentHashMap<TableEntry, AtomicLongArray>` where each entry
holds `[packetCount, byteCount]`. The increment becomes two lock-free
`addAndGet` calls — no global synchronization, no per-counter lock.

Control-plane reads of direct counters (P4Runtime `Read`) snapshot the
atomic values at call time. This is inherently racy (the counter may be
mid-increment from another packet), but that's the correct semantic — P4
counters are best-effort by spec.

**Registers** — read via `registerRead()`, written via `registerWrite()`
during forwarding. Replace the inner `MutableMap<Int, Value>` with a
`ConcurrentHashMap<Int, Value>`. Reads and writes become lock-free `get` /
`put` calls. Note: concurrent packets writing the same register index is
already a data race at the P4 level (the program is wrong), so we don't
need to provide stronger guarantees than the physical hardware would.

**Meters** — currently return hardcoded GREEN without touching state. No
change needed. If real metering is implemented later, the token bucket state
can use `AtomicLong` for the token count and last-update timestamp.

### What gets deleted

- `ReadWriteMutex.kt` (46 lines) — deleted entirely.
- All 9 lock acquisition sites across `P4RuntimeService.kt`,
  `DataplaneService.kt`, and `WebServer.kt` — replaced with a volatile
  read or removed.

### What changes

- `TableStore.WriteState` becomes the published immutable snapshot. The
  existing `deepCopy()` method is already the right shape — it copies
  containers while sharing entries.
- `P4RuntimeService` replaces its `ReadWriteMutex` field with a
  `@Volatile var currentState: ForwardingState`.
- `directCounterData` moves from `IdentityHashMap` to
  `ConcurrentHashMap` + `AtomicLongArray`.
- `registers` inner maps move from `MutableMap` to `ConcurrentHashMap`.
- Control-plane writes (`P4RuntimeService.write`, pipeline config) take a
  simple `Mutex` to serialize with each other. The atomic-rollback pattern
  (`snapshotWriteState` / `restore`) still works — it's just copy, mutate,
  and swap-or-discard instead of copy, mutate, and restore-under-lock.

### Cost structure

| Path | Before | After |
|------|--------|-------|
| Packet processing (hot) | acquire + release read lock (CAS + memory barrier) | one volatile read (pointer load) |
| Control-plane write (rare) | acquire + release write lock | `deepCopy()` + volatile write |
| Batch injection | per-packet read lock (N lock acquisitions) | one volatile read for entire batch |

The `deepCopy()` cost is proportional to the number of table entries — 
microseconds for a realistic forwarding state, negligible next to the gRPC
and protobuf overhead already paid by every control-plane RPC. If this ever
became a concern (millions of entries), swapping to persistent collections
(`kotlinx.collections.immutable`) would make copy-and-modify O(log n)
without changing the architecture.

## What this doesn't change

- The simulator's single-threaded execution model *within* a packet is
  unchanged. Each packet still runs sequentially through the pipeline.
  Parallelism is across packets, not within them.
- The `@Volatile pipeline` field in `P4RuntimeService` already follows
  this pattern for the pipeline reference. This design generalizes it to
  the forwarding state.
- Trace tree construction (`parallelStream()` in `V1ModelArchitecture`)
  for clone/multicast fan-out is orthogonal and unchanged.
