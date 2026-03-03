# 4ward Architecture

This is the "how does this thing actually work?" document. It walks through the
components, how they talk to each other, and why we made the choices we did.

If you just want to use 4ward, the [README](README.md) is all you need. If you
want to hack on it, keep reading.

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
│  Controller ──P4Runtime──▶ 4ward server (Go, future)           │
│                                  │                              │
│                           SimRequest/Response                   │
│                           (proto over stdin/stdout)             │
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

## P4Runtime (future)

Not yet! We're deliberately building the simulator first and getting it solid
before adding the P4Runtime layer. When the time comes, it'll be a Go binary
that:

1. Speaks P4Runtime gRPC to the controller.
2. Translates requests into `SimRequest` proto messages.
3. Forwards them to the simulator over stdin/stdout.

The Go server will be a thin adapter — all the real P4 logic stays in the Kotlin
simulator where it belongs.

## Why these languages?

- **Kotlin**: sealed classes and `when` expressions are *perfect* for
  interpreting a tree-structured IR. Plus, `BigInteger` handles arbitrary-width
  bit vectors without us having to write our own bignum library (no thank you).
- **C++**: p4c is C++, so the backend has to be C++. Simple as that.
- **Go** (future P4Runtime server): gRPC is a first-class citizen in Go, and the
  server will be thin enough that Go's simplicity is a feature, not a limitation.

## Why proto edition 2024?

Because we'd rather adopt the latest thing from day one than migrate later. Proto
editions replace the old proto2/proto3 split with fine-grained feature flags —
less baggage, more flexibility.
