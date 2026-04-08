# Entry Points

4ward can be used in several ways, depending on whether you're debugging at the
command line, integrating with a P4Runtime controller, building a browser-based
demo, or writing automated tests. Each entry point wraps the same core
`Simulator` — they differ in how you feed it programs and packets, and what you
get back.

```
                ┌──────────────────────────────────────────────┐
                │                  Simulator                   │
                │         (pipeline + packet processing)       │
                └──────────────────────────────────────────────┘
                  ▲          ▲            ▲            ▲
                  │          │            │            │
              CLI (4ward)  P4Runtime   Web         STF runner /
              compile/     server     playground   test harness
              sim/run
```

## CLI

**Target:** `//cli:4ward`
**Start:** `bazel run //cli:4ward -- <subcommand>` (or set up a shell alias:
`alias 4ward='bazel run //cli:4ward --'`)

The CLI is the simplest way to use 4ward. Three subcommands:

| Subcommand | What it does |
|---|---|
| `4ward compile <program.p4> -o <output.txtpb>` | Compile P4 to proto IR (no simulation) |
| `4ward sim <pipeline.txtpb> <test.stf>` | Simulate a pre-compiled pipeline against an STF test |
| `4ward run <program.p4> <test.stf>` | Compile + simulate in one step |

Both `sim` and `run` accept `--format=human|textproto` (default: `human`).
Use `-` as the STF path to read from stdin.

**When to use:** Quick iteration on a P4 program. You write P4, inject packets,
read the trace. No servers, no setup — just a command and an answer.

See the [tutorial](../examples/tutorial.t) for a full walkthrough.

## P4Runtime server

**Target:** `//p4runtime:p4runtime_server`
**Start:** `bazel run //p4runtime:p4runtime_server -- [flags]`

A standalone gRPC server implementing the full
[P4Runtime spec](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html).
Load a pipeline with `SetForwardingPipelineConfig`, install table entries with
`Write`, and use `StreamChannel` for PacketOut/PacketIn — exactly as you would
with a hardware switch or BMv2.

| Flag | Default | Description |
|------|---------|-------------|
| `--port=<N>` | 9559 | gRPC listen port |
| `--device-id=<N>` | 1 | P4Runtime device ID |
| `--drop-port=<N>` | *(derived)* | Override the drop port value |
| `--cpu-port=<N>` | *(derived)* | Override the CPU port value |

In addition to the standard P4Runtime RPCs, the server exposes a **Dataplane
service** for direct packet injection:

- `InjectPacket` — send a packet and get output packets + trace tree back.
- `SubscribeResults` — observe all packet results (from any source) as a stream.

**When to use:** Integration with P4Runtime controllers, DVaaS, or any tool
that speaks gRPC. This is the entry point for treating 4ward as a drop-in
replacement for BMv2.

## Web playground

**Target:** `//web:playground`
**Start:** `bazel run //web:playground` (opens `http://localhost:8080`
automatically)

A browser-based IDE that bundles the P4 compiler, simulator, and P4Runtime
server into a single feedback loop. Write P4 with syntax highlighting, install
table entries, inject packets, and explore what happened — all without leaving
the browser.

Highlights:

- **Trace playback** — step through events with arrow keys; the active P4
  source line and control-flow graph node stay in sync.
- **Control-flow graph** — visual pipeline diagram showing tables, conditions,
  and control flow.
- **Packet decoding** — output packets decoded into named header fields using
  the program's own deparser.

Under the hood, the playground runs a gRPC server (port 9559) and an HTTP
server (port 8080) sharing a single `Simulator`. The HTTP server exposes a REST
API (`/api/compile-and-load`, `/api/packet`, etc.) that the frontend calls.

**When to use:** Learning, demos, visual debugging. Best for interactive
exploration where you want to *see* what happened, not script it.

## STF runner

**Target:** `//e2e_tests/stf:stf_runner` (library, not a binary)
**API:** `StfRunner` / `runStfTest()` in Kotlin

The STF runner drives the simulator directly from Kotlin test code. It parses
[STF files](https://github.com/p4lang/p4c/blob/main/backends/bmv2/testing/stf/README.md)
(the standard P4 test format: `packet`, `expect`, `add`, etc.), loads the
pipeline, installs table entries, injects packets, and checks that outputs
match expectations.

```kotlin
@Test
fun `basic table forwarding`() {
  val result = runStfTest("basic_table")
  if (result is TestResult.Failure) fail(result.message)
}
```

**When to use:** Automated conformance and regression testing. Every feature in
4ward has an STF test that exercises it. The STF runner is also used for
corpus-level bulk testing (87 tests from p4c's own suite) and differential
testing against BMv2.

## P4Runtime test harness

**Target:** `//p4runtime:p4runtime_lib` (library)
**API:** `P4RuntimeTestHarness` in Kotlin

An in-process test harness that stands up a full P4Runtime + Dataplane gRPC
server without opening any network ports. Uses gRPC's `InProcessTransport` for
zero-overhead, deterministic test execution.

```kotlin
P4RuntimeTestHarness().use { harness ->
  harness.loadPipeline(configPath)
  harness.write(listOf(tableEntry))
  val response = harness.injectPacket(ingressPort = 0, payload)
  // assert on response.outputPackets, response.trace, ...
}
```

**When to use:** Testing P4Runtime behavior — write validation, pipeline
loading, PacketOut/PacketIn routing, `@p4runtime_translation`. Gives you full
gRPC semantics (status codes, streaming) without network flakiness.

## Intrinsic port configuration

All entry points share the same defaults for intrinsic ports (drop port, CPU
port), derived from the P4 program's port width. Each entry point lets you
override them in whatever way fits its context. See the
[intrinsic ports design](../designs/intrinsic_ports.md) for full details.

| Entry point | Drop port override | CPU port override |
|---|---|---|
| **CLI** | `--drop-port` flag | `--cpu-port` flag |
| **P4Runtime server** | `--drop-port` flag | `--cpu-port` flag |
| **Web playground** | Same as P4Runtime server | Same as P4Runtime server |
| **STF runner** | Constructor param | N/A (no P4Runtime layer) |
| **Test harness** | Constructor param | Constructor param |

### Defaults

| Port | Default value | Derived from | Enabled |
|------|---------------|--------------|---------|
| Drop port | `2^N - 1` (511 for 9-bit ports) | `standard_metadata` port field width | Always |
| CPU port | `2^N - 2` (510 for 9-bit ports) | `controller_packet_metadata` field width | Only when p4info has `ControllerPacketMetadata` |
