# 4ward Architecture

This is the "how does this thing actually work?" document. It walks through the
components, how they talk to each other, and why we made the choices we did.

If you just want to use 4ward, the [README](README.md) is all you need. If you
want to hack on it, keep reading.

## Design goal: spec-compliant reference implementation

4ward's primary goal is to be a faithful implementation of the
[P4₁₆ language specification](https://p4.org/wp-content/uploads/sites/53/2024/10/P4-16-spec-v1.2.5.html).
Every language feature should behave exactly as the spec describes. When the
spec is ambiguous, we follow p4c's reference compiler behaviour and document
the ambiguity. When the spec is clear, we follow it — even if BMv2 or other
implementations do something different.

This means correctness always wins over convenience, performance, or
compatibility with non-standard behaviour. The simulator is meant to be the
implementation you trust when you need to know what a P4 program *should* do.

The north star is to replace BMv2 as the reference simulator in
[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas). See
[ROADMAP.md](ROADMAP.md) for the full picture.

## The big picture

```
┌─────────────────────────────────────────────────────────────────┐
│ compile time                                                    │
│                                                                 │
│  program.p4 ──▶ p4c + 4ward backend ──▶ PipelineConfig.txtpb  │
│                        (C++)               (proto text format)  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│ runtime                                                         │
│                                                                 │
│  Controller ──P4Runtime──▶ P4Runtime server                     │
│                                  │                              │
│                                  ▼                              │
│  packet ──────────────────▶ 4ward sim ──▶ output packets       │
│                               (Kotlin)  └▶ execution trace      │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### p4c backend (`p4c_backend/`) — the translator

A C++ p4c backend plugin that turns your P4 source into something the simulator
can understand. It runs after p4c's midend simplification passes and emits a
`PipelineConfig` proto. By this point the program is fully elaborated:
generics instantiated, constants folded, header stacks concretized, no abstract
types. Nice and clean for the simulator to interpret.

### Proto IR (`simulator/ir.proto`) — the contract

This is the heart of the project: the intermediate representation that the
backend produces and the simulator consumes. Two design choices worth
highlighting:

**Names, not IDs.** Everything is referenced by human-readable string names.
Numeric IDs only show up in p4info (for P4Runtime). This means you can actually
read a `PipelineConfig` without a lookup table — just open the textproto and
it makes sense.

**Type-complete expressions.** Every `Expr` node carries a `Type` annotation.
The simulator never guesses bit widths; p4c already figured that out.

### Simulator service protocol (`simulator/simulator.proto`) — the wire

How does the outside world talk to the simulator? Length-delimited proto messages
over stdin/stdout (4-byte big-endian length prefix + serialised bytes). Same
pattern as the Language Server Protocol — boring, reliable, easy to debug.

The protocol is refreshingly simple: load a pipeline, send packets, read and
write table entries. That's about it.

### Simulator (`simulator/`) — where the magic happens

A Kotlin/JVM interpreter that walks the proto IR and actually *runs* your P4
program, one packet at a time. Here's the lay of the land:

```
Main.kt                  Front door: stdin/stdout framing, request dispatch
Simulator.kt             Top-level state: pipeline config, table entries
Interpreter.kt           The big one: IR tree-walker for parsers, controls, actions
Environment.kt           Variable bindings, packet state (headers + metadata)
Values.kt                Runtime value types (BitVector, BoolVal, HeaderVal, ...)
BitVector.kt             Bit-precise integer arithmetic (backed by BigInteger)
Architecture.kt          Interface for architecture-specific behaviour
V1ModelArchitecture.kt   v1model specifics: recirculate, clone, resubmit, drop
```

We use `BigInteger` for all `bit<N>` arithmetic because life is too short for
overflow bugs at arbitrary bit widths.

## Architecture genericity

P4 has several architectures (v1model, PSA, PNA, TNA) and they all do things a
little differently:

1. **Pipeline structure**: which parsers/controls run and in what order.
2. **Standard metadata**: the metadata struct passed between stages.
3. **Extern semantics**: what `clone3()`, `resubmit()`, etc. actually *do*.

4ward handles this cleanly: point 1 is captured structurally in
`Architecture.stages`. Points 2 and 3 live in per-architecture Kotlin code —
`Architecture.kt` defines the interface, and each architecture gets its own
implementation (currently just `V1ModelArchitecture.kt`).

Want to add a new one? You need:
- A new `Architecture.kt` implementation in `simulator/`.
- Any new externs in the `ExternTypeDecl` list of the `Architecture` proto.
- A new `--arch` flag value in the p4c backend.

## Testing strategy

Here's the fun part: p4c ships hundreds of STF (Simple Test Framework) tests,
and each one is basically a self-contained feature spec. An STF test says "here's
a P4 program, here are some table entries and packets — now tell me what comes
out." The STF runner compiles the program, loads it into the simulator, feeds the
packets in, and diffs the output.

The failing-test list *is* the feature backlog. Pick a failing test, make it
pass, and you've shipped a feature. It's surprisingly satisfying.

We also have:
- **Unit tests** for the tricky stuff: bit-precise arithmetic, match kinds
  (LPM, ternary), select expression semantics.
- **P4TestGen** (planned): symbolic execution to generate path-covering tests.
- **BMv2 diff testing** (maybe, someday): run the same inputs through BMv2 and
  4ward and compare outputs.

## Trace trees

4ward's output format is a **trace tree** (`TraceTree` in `simulator.proto`) —
a recursive structure where each node contains a sequence of events and an
optional fork. At non-deterministic choice points (action selectors, clone,
multicast), execution forks into branches, one per possible outcome. A program
with no non-determinism produces a zero-fork tree that is structurally
equivalent to a flat trace — there is no separate "flat trace" format.

The key insight: even when choices like action selector hashing are technically
deterministic, it's often more useful to reason about them as
non-deterministic — "what *could* happen to my packet?" rather than "what
happens with this specific hash seed?"

**Status:** The `TraceTree` proto schema is defined and the simulator produces
zero-fork trees. Forking (action selectors, clone, multicast) is under active
development — see [ROADMAP.md](ROADMAP.md) Track 3.

**Why this matters:**

No other P4 tool gives you this. BMv2 picks one path. Hardware picks one path.
4ward can show you *all* paths — making it a powerful tool for testing,
verification, and understanding complex P4 programs.

## P4Runtime (`p4runtime/`)

The P4Runtime gRPC server is a thin translation layer between standard
P4Runtime RPCs and the simulator's `SimRequest`/`SimResponse` protocol.
All P4 logic stays in the simulator — the server just speaks gRPC.

```
Controller ──gRPC──▶ P4RuntimeService ──SimRequest──▶ Simulator
                           │                              │
                     translates protos              runs your program
                     enforces preconditions          returns SimResponse
```

**Implemented RPCs:**

| RPC | Status |
|-----|--------|
| `SetForwardingPipelineConfig` | Working — parses `P4BehavioralConfig` from `p4_device_config` bytes |
| `Write` | Working — forwards `Update` protos directly to the simulator |
| `Read` | Working — returns all table entries (wildcard read; filtering is TODO) |
| `StreamChannel` | Working — arbitration + PacketOut→PacketIn via the simulator |
| `GetForwardingPipelineConfig` | Stub (UNIMPLEMENTED) |
| `Capabilities` | Stub (UNIMPLEMENTED) |

**Key design decisions:**

- **In-process, not subprocess.** The server calls `Simulator.handle()` directly
  rather than piping through stdin/stdout. Same `SimRequest`/`SimResponse`
  contract, just no serialization overhead.
- **grpc-kotlin with coroutine Flows.** `StreamChannel` is a bidirectional
  stream — Kotlin Flows map naturally to gRPC's streaming model.
- **`synchronized(simulator)` for thread safety.** The simulator is
  single-threaded; the gRPC server serializes concurrent requests with a lock.
  Good enough for a reference implementation.
- **Single controller.** First connection is master. No multi-controller
  arbitration, no election ID tracking.

**What's not here yet:** p4-constraints validation, `@p4runtime_translation`,
digest support, idle timeout notifications, atomic write batches. See
[ROADMAP.md](ROADMAP.md) Track 4 for the full scope.

## Why these languages?

- **Kotlin**: sealed classes and `when` expressions are *perfect* for
  interpreting a tree-structured IR. Plus, `BigInteger` handles arbitrary-width
  bit vectors without us having to write our own bignum library (no thank you).
- **C++**: p4c is C++, so the backend has to be C++. Simple as that.

## Why proto edition 2024?

Because we'd rather adopt the latest thing from day one than migrate later. Proto
editions replace the old proto2/proto3 split with fine-grained feature flags —
less baggage, more flexibility.
