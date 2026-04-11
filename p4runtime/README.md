# p4runtime

A [P4Runtime](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html)
gRPC server and in-process test harness. This package is a thin adapter
over `//simulator:simulator_lib` — it holds **no** P4 state of its own;
every request is forwarded to the simulator, which remains the single
source of truth for dataplane state.

Beyond the standard P4Runtime RPCs, the server exposes a Dataplane
service for direct packet injection (`InjectPacket`, `SubscribeResults`) —
the glue that makes 4ward a drop-in BMv2 replacement for tools like
DVaaS.

**Target:** `//p4runtime:p4runtime_server` (standalone) or
`//p4runtime:p4runtime_lib` (in-process `P4RuntimeTestHarness`). See
[`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#p4runtime-server) and
[`docs/P4RUNTIME_COMPLIANCE.md`](../docs/P4RUNTIME_COMPLIANCE.md).
