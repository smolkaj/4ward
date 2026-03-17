# DataplaneService Port Encoding

**Status: proposal**

## Goal

Make the DataplaneService ergonomic for both simulator-level tooling (which
thinks in dataplane port numbers) and P4Runtime-level tooling (which thinks in
P4RT port IDs). Today, only dataplane port numbers (`uint32`) are supported,
forcing P4Runtime clients to convert back and forth.

Longer term, the same dual-encoding principle should extend to trace trees and
the various trace viewers (CLI, web playground) so that ports, table entry
values, and other translated fields are displayed in their P4Runtime
representation alongside the raw dataplane values. That broader effort is out
of scope for this design — see
[LIMITATIONS.md](../docs/LIMITATIONS.md#dataplane-service) for tracking.

## Background

Ports have two representations in the P4Runtime world:

- **Dataplane ports** — `uint32` values matching `standard_metadata.ingress_port`
  / `egress_port`. This is what the simulator operates on internally.
- **P4RT port IDs** — opaque `bytes` whose encoding depends on the P4 program's
  `@p4runtime_translation` annotation on the port type. Could be a numeric
  encoding (`bit<32>`), a string encoding (`sdn_string`), or any other `bytes`
  value. The `TypeTranslator` maps between the two representations based on the
  loaded pipeline. If a P4 program does not use `@p4runtime_translation` on its
  port type, the two encodings are identical — the P4RT port ID is just the
  dataplane port number in its native binary encoding.

The DataplaneService currently uses `uint32` ports everywhere (via
`simulator.proto`'s `InputPacket`/`OutputPacket`). This is natural for
STF tests and simulator-level tooling, but awkward for DVaaS and other
P4Runtime-oriented consumers that pass port IDs as opaque bytes.

## Requirements

1. **Accept either port encoding in requests.** Callers should be able to
   specify ingress ports as dataplane `uint32` or P4RT `bytes`, whichever is
   natural for their context.

2. **Provide both port encodings in responses.** Output packets should include
   both the dataplane port number and the P4RT port ID, so consumers in either
   world can read ports without conversion.

3. **P4RT port encoding is `bytes`.** The P4Runtime spec defines port IDs as
   opaque bytes — the actual encoding depends on `@p4runtime_translation`. The
   DataplaneService must not assume any particular encoding (not string, not
   fixed-width integer).

4. **Dataplane ports remain available.** Simulator-level tooling (STF runner,
   trace tree tests, BMv2 differential tests) should continue to work with
   `uint32` ports without needing a loaded pipeline or translation table.

5. **P4RT ports require a loaded pipeline.** The mapping between dataplane and
   P4RT ports depends on `@p4runtime_translation` in the loaded pipeline. The
   server should reject P4RT port input if no pipeline is loaded (or if the
   port doesn't map). P4RT port output is only populated when translation is
   available.

6. **`simulator.proto` is unchanged.** `InputPacket`/`OutputPacket` in
   `simulator.proto` remain `uint32` — they are internal types shared across
   the simulator, trace trees, and STF infrastructure. The DataplaneService
   defines its own request/response types at the API boundary.

## Design

### Proto schema (`dataplane.proto`)

The DataplaneService introduces its own output packet type with dual encoding
and adds a P4RT port option to the request:

```proto
// Input packet with dual port encoding. The P4Runtime field is only
// populated when port translation is available.
message InputPacket {
  uint32 dataplane_ingress_port = 1;
  // P4Runtime port ID — opaque bytes whose encoding depends on
  // @p4runtime_translation. Only populated when a pipeline with
  // port translation is loaded.
  bytes p4rt_ingress_port = 3;
  bytes payload = 2;
}

// Output packet with dual port encoding. The P4Runtime field is only
// populated when port translation is available.
message OutputPacket {
  uint32 dataplane_egress_port = 1;
  // P4Runtime port ID — opaque bytes whose encoding depends on
  // @p4runtime_translation. Only populated when a pipeline with
  // port translation is loaded.
  bytes p4rt_egress_port = 3;
  bytes payload = 2;
}

message InjectPacketRequest {
  oneof ingress_port {
    // Dataplane port number (e.g. 0, 1, 510).
    uint32 dataplane_ingress_port = 1;
    // P4Runtime port ID — opaque bytes whose encoding depends on
    // @p4runtime_translation. Requires a loaded pipeline with port
    // translation; otherwise the RPC fails with FAILED_PRECONDITION.
    bytes p4rt_ingress_port = 2;
  }
  bytes payload = 3;
}

message InjectPacketResponse {
  repeated OutputPacket output_packets = 1;
  fourward.sim.TraceTree trace = 2;
}

message ProcessPacketResult {
  InputPacket input_packet = 1;
  repeated OutputPacket output_packets = 2;
  fourward.sim.TraceTree trace = 3;
}
```

Key decisions:

- `InjectPacketRequest` uses a **`oneof ingress_port`** — the caller picks
  either the dataplane port number or the P4RT port ID, not both.
  `sim.InputPacket` is no longer part of the request; it stays a pure
  simulator-internal type.
- `OutputPacket` is **redefined in `dataplane.proto`** with dual encoding.
  It replaces `fourward.sim.OutputPacket` in `InjectPacketResponse` and
  `ProcessPacketResult`. The `fourward.sim.OutputPacket` in `simulator.proto`
  is unchanged and continues to be used in trace trees.
- The trace tree inside responses still uses `fourward.sim.OutputPacket` (no
  P4RT ports). See [Future work](#future-work).

### Port translation

A `PortTranslator` wraps the existing `TypeTranslator` with the port-specific
URI and encoding type, providing bidirectional conversion between P4Runtime
port IDs and dataplane port numbers. (The `TypeTranslator` API uses "SDN"
terminology inherited from the P4 spec; renaming to "P4RT" is tracked in
[REFACTORING.md](../docs/REFACTORING.md#rename-sdn-to-p4rt-in-typetranslator).)

The port URI is derived at pipeline load time from
`controller_packet_metadata` in the p4info. Each metadata field has an
optional
[`type_name`](https://github.com/p4lang/p4runtime/blob/main/proto/p4/config/v1/p4info.proto#L453)
that resolves to a type in `type_info`. If that type has a
`@p4runtime_translation` annotation, its URI and encoding identify the port
translation. For SAI P4, this is `""` (from
`@p4runtime_translation("", string)` on `port_id_t`). If no translated port
type is found in `controller_packet_metadata`, port translation is unavailable
and the `DataplaneService` operates in dataplane-only mode.

### DataplaneService changes

The `DataplaneService` receives a `PortTranslator` provider from
`P4RuntimeService`:

- **`injectPacket`**: resolves the `oneof ingress_port` — either uses the
  dataplane port directly, or translates the P4Runtime port via the
  `TypeTranslator`. Fails with `FAILED_PRECONDITION` if a P4Runtime port is
  specified but no translator is available.
- **Responses**: maps each `sim.OutputPacket` to a `dataplane.OutputPacket`,
  adding `p4rt_egress_port` when port translation is available.
- **`subscribeResults`**: same dual-encoding logic for `ProcessPacketResult`.

## What changes

| Component | Change |
|---|---|
| `dataplane.proto` | New `InputPacket`/`OutputPacket` with dual encoding; `oneof ingress_port` on request |
| `PortTranslator` | Wraps `TypeTranslator` + port URI for bidirectional port conversion |
| `DataplaneService.kt` | Translates ports on inject and populates P4Runtime ports in responses |
| `P4RuntimeService.kt` | Derives port URI from p4info `controller_packet_metadata` at pipeline load |
| `P4RuntimeServer.kt` | Wires provider from service to dataplane service |

## What doesn't change

- **`simulator.proto`** — `InputPacket`/`OutputPacket` remain `uint32`.
- **Simulator** — no changes. Operates exclusively on dataplane port numbers.
- **STF runner** — no changes. Uses `sim.InputPacket`/`sim.OutputPacket`
  directly.
- **P4Runtime Write/Read** — type translation for table entries and PacketIO
  metadata is handled by the existing `TypeTranslator` in `P4RuntimeService`.
- **Trace trees** — continue using `sim.OutputPacket` with dataplane ports
  only.
- **Existing `InjectPacket` callers** — fully backward compatible. If
  `p4rt_ingress_port` is absent, behavior is identical to today.

## Future work

Trace trees and the various trace viewers (CLI `4ward sim`, web playground)
currently show only raw dataplane values for ports, table entry fields, and
action parameters. Enriching them with P4Runtime representations would make
debugging much easier — a trace showing `port="Ethernet0"` is more useful than
`port=0`. This is a natural extension of the dual-encoding principle but
requires changes across the simulator, trace tree proto, and all consumers.
Tracked in [LIMITATIONS.md](../docs/LIMITATIONS.md#dataplane-service).

## Sonic-pins integration

On the sonic-pins side
([PR #1](https://github.com/smolkaj/sonic-pins/pull/1)), the local copy of
`dataplane.proto` in `fourward/` adopts the same schema, and
`FourwardBackend` drops its manual port conversion code. See
[REFACTORING.md](../docs/REFACTORING.md) for tracking.
