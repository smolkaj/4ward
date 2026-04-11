# P4 Compiler Backend

A [p4c](https://github.com/p4lang/p4c) (the P4 compiler) backend
plugin that lowers P4 programs to 4ward's behavioral intermediate
representation ([`simulator/ir.proto`](../simulator/ir.proto)). Normally
invoked via the [`fourward_pipeline`](../bazel/fourward_pipeline.bzl)
Bazel rule. See [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
