# Simulator

The Kotlin simulator — the heart of 4ward and the source of truth for
all dataplane state. [`ir.proto`](ir.proto) is the behavioral IR
(intermediate representation), the core contract with the p4c (P4
compiler) backend. See [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
