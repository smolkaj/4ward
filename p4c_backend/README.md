# p4c_backend

A [p4c](https://github.com/p4lang/p4c) backend plugin, written in C++,
that lowers a P4 program to 4ward's behavioral IR
([`simulator/ir.proto`](../simulator/ir.proto)). The plugin runs after
p4c's midend, so it sees a simplified, fully-resolved program.

**Target:** `//p4c_backend:p4c-4ward`. Normally invoked via the
[`fourward_pipeline`](../bazel/fourward_pipeline.bzl) Bazel rule rather
than directly. See [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md)
for where this fits in the overall pipeline.
