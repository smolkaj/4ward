# web

The 4ward web playground: a browser-based IDE that bundles the P4
compiler, simulator, and P4Runtime server into a single feedback loop.
Write P4, install table entries, inject packets, and step through the
trace — all without leaving the browser. Under the hood it runs a gRPC
server (9559) and an HTTP server (8080) sharing a single `Simulator`.

**Target:** `//web:playground`
**Start:** `bazel run //web:playground` (opens `http://localhost:8080`)

See [`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#web-playground) and
[`docs/PLAYGROUND_VISION.md`](../docs/PLAYGROUND_VISION.md).
