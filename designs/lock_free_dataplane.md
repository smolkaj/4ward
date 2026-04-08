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

A single `@Volatile` field holds the current `ForwardingSnapshot`. Packet
threads read the pointer (one memory barrier — free on x86) and execute
against the immutable snapshot. Control-plane writes build a new state via
`deepCopy()`, apply mutations, and swap the pointer. Writers synchronize
only with each other (a simple `Mutex` suffices since P4Runtime already
serializes writes).

Naming: the current `WriteState` is renamed to `ForwardingSnapshot` to
reflect its new role. The old name carries "write" connotations that are
wrong for an immutable object.

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

This changes the keying semantics from identity (`IdentityHashMap`) to value
equality (`ConcurrentHashMap` uses `equals`/`hashCode`). Since `TableEntry`
is a protobuf message with value-based equality, this is safe — and arguably
more correct, since identity-based keying is a fragile implementation detail
that `deepCopy()` already has to carefully preserve (see the "do NOT
deep-copy the TableEntry objects" comment in `WriteState`). Value-based
keying eliminates that invariant entirely.

Control-plane reads of direct counters (P4Runtime `Read`) snapshot the
atomic values at call time. This is inherently racy (the counter may be
mid-increment from another packet), but that's the correct semantic — P4
counters are best-effort by spec.

**Registers** — read via `registerRead()`, written via `registerWrite()`
during forwarding. Replace the inner `MutableMap<Int, Value>` with a
`ConcurrentHashMap<Int, Value>`. Reads and writes become lock-free `get` /
`put` calls. Note: a P4 program can perform a logical read-modify-write
(`register.read(val, idx); val = val + 1; register.write(val, idx)`) split
across two extern calls. With concurrent packets, this is a lost-update
race. This is intentional — we don't provide atomicity for this pattern,
matching hardware semantics. Concurrent packets writing the same register
index is a data race at the P4 level (the program is wrong).

**Meters** — currently return hardcoded GREEN without touching state. No
change needed. If real metering is implemented later, the token bucket state
can use `AtomicLong` for the token count and last-update timestamp.

### Pre-packet hook

The pre-packet hook (`fireHookIfRegistered`) sends the current forwarding
state to a client, receives P4Runtime updates, and applies them — all before
the batch's packets execute. Today this acquires the write lock for the
entire batch to ensure the hook's updates are visible to every packet.

With immutable snapshots, the hook interaction simplifies:

1. Acquire the control-plane `Mutex` (serialize with other writers).
2. Fire the hook — it reads the current snapshot and applies updates,
   which publishes a new snapshot via `deepCopy()` + volatile write.
3. Read the (now-updated) snapshot.
4. Release the `Mutex`.
5. Process the entire batch against the captured snapshot — no lock held.

The critical property is preserved: every packet in the batch sees the
hook's updates. The improvement is that the `Mutex` is held only for the
hook interaction (steps 1–4), not for the entire duration of packet
processing. Today the write lock is held until all packets complete, which
blocks all other readers — including unrelated single-packet injections and
PacketOut processing.

### Snapshot lifetime

With immutable snapshots, old versions stay alive as long as any packet
thread holds a reference. Under sustained high-throughput injection with
slow packets (e.g., deep trace trees), several snapshot generations could
coexist in memory. This is bounded by the number of in-flight packets —
not a concern for a development and testing tool, and natural JVM GC
handles the cleanup.

### What gets deleted

- `ReadWriteMutex.kt` (46 lines) — deleted entirely.
- All 9 lock acquisition sites across `P4RuntimeService.kt`,
  `DataplaneService.kt`, and `WebServer.kt` — replaced with a volatile
  read or removed.

### What changes

- `TableStore.WriteState` is renamed to `ForwardingSnapshot`. The existing
  `deepCopy()` method is already the right shape — it copies containers
  while sharing entries.
- `P4RuntimeService` replaces its `ReadWriteMutex` field with a
  `@Volatile var currentState: ForwardingSnapshot`.
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

## Complexity

This design is simpler than the status quo. The complexity reduction comes
from two shifts:

**Synchronization moves from callers to data structures.** Today, 9 call
sites across 3 files must each choose the right lock mode (`withReadLock`,
`withWriteLock`, `withReadLockBlocking`), hold it for the right scope, and
avoid deadlocks. This is protocol-level complexity — it's not enforced by
the compiler and can only be verified by careful code review. After the
change, the snapshot is immutable by construction (no lock needed) and the
few mutable objects (counters, registers) encapsulate their own
synchronization. There is nothing for callers to get wrong.

**The hook path becomes uniform with the non-hook path.** Today,
`injectPackets` has two completely different code paths: with-hook (write
lock for the entire batch, `ForkJoinPool` tasks under the lock) and
without-hook (per-packet read lock from `ForkJoinPool`). After the change,
both paths grab a snapshot and fan out — the only difference is that the
hook path acquires the `Mutex` briefly to fire the hook first. The
`ReadWriteMutex` and its blocking/suspending variants (`withReadLock` vs.
`withReadLockBlocking` vs. `withWriteLock`) disappear entirely.

**Net delta:**

| Metric | Before | After |
|--------|--------|-------|
| Synchronization primitives | `ReadWriteMutex` (4 methods), `@Synchronized`, `@Volatile` | `Mutex`, `@Volatile`, `ConcurrentHashMap`, `AtomicLongArray` |
| Lock acquisition sites | 9 (across 3 files) | 0 on the packet path; `Mutex` in control-plane writes only |
| Code paths in `injectPackets` | 2 (fundamentally different) | 1 (hook just adds a preamble) |
| Files deleted | — | `ReadWriteMutex.kt` |
| Correctness argument | "every caller acquires the right lock" | "the snapshot is immutable; mutable state is encapsulated" |

The new primitives (`ConcurrentHashMap`, `AtomicLongArray`) are standard
library types with well-understood semantics — simpler than the custom
`ReadWriteMutex` wrapper with its coroutine-to-blocking bridging
(`withReadLockBlocking`, `Dispatchers.IO` delegation, thread-affinity
constraints for `ReentrantReadWriteLock`).

## What this doesn't change

- The simulator's single-threaded execution model *within* a packet is
  unchanged. Each packet still runs sequentially through the pipeline.
  Parallelism is across packets, not within them.
- The `@Volatile pipeline` field in `P4RuntimeService` already follows
  this pattern for the pipeline reference. This design generalizes it to
  the forwarding state.
- Trace tree construction (`parallelStream()` in `V1ModelArchitecture`)
  for clone/multicast fan-out is orthogonal and unchanged.
