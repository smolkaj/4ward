# 4ward Architecture

This document explains the design of 4ward: what the components are, how they
fit together, and why key decisions were made the way they were.

## The big picture

```
┌─────────────────────────────────────────────────────────────────┐
│ compile time                                                    │
│                                                                 │
│  program.p4 ──▶ p4c + 4ward backend ──▶ PipelineConfig.pb     │
│                        (C++)               (proto binary)       │
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

### p4c backend (`p4c_backend/`)

A C++ p4c backend plugin that runs after p4c's midend passes and emits a
`PipelineConfig` proto binary. It links against p4c as a Bazel dependency
(no fork required once upstreamed; use `git_override` in the interim).

The backend runs after all midend simplification passes, so the IR it emits
reflects a fully-elaborated, type-resolved P4 program: generics instantiated,
constants folded, header stacks concretized, no abstract types.

### Proto IR (`simulator/ir.proto`)

The core contract between the backend and the simulator. Two key design choices:

**Names, not IDs.** All cross-references use string names. Numeric IDs from
p4info are for the control-plane API (P4Runtime) only. This makes the IR
readable and debuggable without a p4info lookup table.

**Type-complete expressions.** Every `Expr` node carries a `Type` annotation
populated by p4c. The simulator never needs to infer types; it always knows
the exact bit width of every value it manipulates.

### Simulator service protocol (`simulator/simulator.proto`)

The IPC protocol between the controller and the simulator subprocess.
Framing: length-delimited proto messages over stdin/stdout (4-byte big-endian
length prefix + serialised bytes). This is the Language Server Protocol pattern,
well-proven in practice.

The protocol is intentionally simple: load a pipeline, process packets, read and
write table entries. The P4Runtime server (when added) is a thin translation
layer from the P4Runtime gRPC API to this protocol.

### Simulator (`simulator/`)

A Kotlin/JVM interpreter for the proto IR. Architecture:

```
Main.kt                  stdin/stdout framing, request dispatch
Simulator.kt             top-level state (pipeline config, table entries)
Interpreter.kt           IR tree-walker: parsers, controls, actions
Environment.kt           variable bindings, packet state (headers + metadata)
Values.kt                runtime value types (BitVector, BoolVal, HeaderVal, ...)
BitVector.kt             bit-precise integer arithmetic (backed by BigInteger)
Architecture.kt          interface for architecture-specific behaviour
V1ModelArchitecture.kt   v1model: recirculation, clone, resubmit, mark_to_drop
```

The simulator prioritises correctness and readability over performance. `BigInteger`
is used for all `bit<N>` arithmetic to handle arbitrary widths without overflow
surprises.

## Architecture genericity

Different P4 architectures (v1model, PSA, PNA, TNA) differ in:

1. **Pipeline structure**: which parsers/controls run and in what order.
2. **Standard metadata**: the metadata struct passed between stages.
3. **Extern semantics**: what `clone3()`, `resubmit()`, etc. actually do.

Point 1 is captured structurally in `Architecture.stages`. Points 2 and 3
require code in the simulator — `Architecture.kt` defines the interface, and
each architecture gets an implementation (e.g. `V1ModelArch.kt`).

Adding a new architecture requires:
- A new `Architecture.kt` implementation in `simulator/`.
- Any new externs declared in the `ExternTypeDecl` list of the `Architecture`
  proto message.
- A new `--arch` flag value in the p4c backend.

## Testing strategy

Testing is driven by the p4c STF (Simple Test Framework) corpus. Each STF test
is a tuple of (P4 program, table entries, input packets, expected output
packets). The STF runner compiles the P4 program to a `PipelineConfig` proto
using the 4ward backend, loads it into the simulator, feeds the input packets,
and diffs the output against the STF expectations.

This gives us hundreds of regression tests for free. The failing-test list *is*
the feature backlog: pick a failing STF test, implement what it needs, make it
green.

Supplementary testing:
- **P4TestGen**: uses symbolic execution to generate path-covering tests.
- **Unit tests**: bit-precise arithmetic, match kinds (LPM, ternary), select
  expression semantics — anything where correctness is subtle.
- **BMv2 diff testing** (maybe): for programs not in the STF corpus, run the 
  same inputs through BMv2 and 4ward and compare outputs.

## P4Runtime (future)

The P4Runtime server is deliberately deferred until the simulator is solid.
When added, it will be a Go binary that:
1. Speaks P4Runtime gRPC to the controller.
2. Translates P4Runtime requests to `SimRequest` proto messages.
3. Forwards them to the simulator subprocess over stdin/stdout.

The Go server is a thin adapter; all P4 execution logic stays in the Kotlin sim.

## Why these languages?

- **Kotlin**: sealed classes and `when` expressions are the right abstraction for
  an IR interpreter. The JVM's `BigInteger` handles arbitrary-width bit vectors.
  Google's Kotlin style guide is followed throughout.
- **C++ (backend)**: p4c is C++. The backend is a p4c plugin; there is no
  other practical choice.
- **Go (future P4Runtime server)**: gRPC is a first-class citizen in Go.
  The server is a thin layer; Go's simplicity keeps it readable.

## Why proto edition 2024?

Proto editions replace the proto2/proto3 distinction with fine-grained feature
flags, giving us forward compatibility without legacy baggage. We adopt the
latest edition from the start rather than migrating later.
