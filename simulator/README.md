# simulator

The Kotlin simulator — the heart of 4ward. Interprets a compiled P4
program against a packet and produces output packets plus a trace of
everything that happened along the way. The simulator is the **source
of truth** for all dataplane state: table entries, counters, registers,
multicast groups. Every other part of 4ward (CLI, P4Runtime, web,
tests) talks to it through `Simulator.kt`.

[`ir.proto`](ir.proto) is the behavioral IR — the core contract between
the p4c backend and everything downstream. See
[`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for the full design
rationale.
