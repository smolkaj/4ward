---
description: "4ward gRPC API reference: P4Runtime and Dataplane service RPCs, connection setup, and proto message formats."
---

# gRPC API Reference

4ward exposes two gRPC services on the same port (default: **9559**).

## Server

```sh
bazel run //p4runtime:p4runtime_server -- [flags]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--port` | 9559 | gRPC listen port |
| `--device-id` | 1 | P4Runtime device ID |
| `--drop-port` | `2^N - 1` | Override drop port (e.g., 511 for 9-bit ports) |
| `--cpu-port` | `2^N - 2` | Override CPU port (e.g., 510 for 9-bit ports; auto-enabled when `@controller_header` is present) |

## P4Runtime service

Standard [P4Runtime](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html) gRPC API (reports version
**1.5.0**). All six RPCs are implemented:

| RPC | Description |
|-----|-------------|
| `SetForwardingPipelineConfig` | Load or replace a P4 pipeline |
| `GetForwardingPipelineConfig` | Retrieve the loaded pipeline |
| `Write` | Insert, modify, or delete entities (table entries, action profiles, clone sessions, multicast groups) |
| `Read` | Query entities (streaming response) |
| `StreamChannel` | Bidirectional stream for master arbitration, PacketOut, and PacketIn |
| `Capabilities` | Report P4Runtime protocol version |

### Arbitration

Multi-controller arbitration is fully supported with role-based access
control. Open a `StreamChannel` and send a `MasterArbitrationUpdate` to
become primary for a role. The highest `election_id` wins.

### Write atomicity

| Mode | Behavior |
|------|----------|
| `CONTINUE_ON_ERROR` | Attempt all updates; report per-update status |
| `ROLLBACK_ON_ERROR` | All-or-none (snapshot-based rollback) |
| `DATAPLANE_ATOMIC` | Same as rollback (the write lock ensures atomicity) |

## Dataplane service

Defined in [`dataplane.proto`](https://github.com/smolkaj/4ward/blob/main/p4runtime/dataplane.proto).
For packet injection and result observation — not part of the P4Runtime spec.

### `InjectPacket`

Inject a single packet and get the result inline.

**Request:**

```protobuf
message InjectPacketRequest {
  oneof ingress_port {
    uint32 dataplane_ingress_port = 1;  // e.g., 0
    bytes p4rt_ingress_port = 2;        // e.g., "Ethernet0"
  }
  bytes payload = 3;
}
```

The `p4rt_ingress_port` variant requires a loaded pipeline with
[`@p4runtime_translation`](../concepts/type-translation.md) on the port type.

**Response:**

```protobuf
message InjectPacketResponse {
  repeated OutputPacket output_packets = 1;
  TraceTree trace = 2;  // P4RT-enriched when translation is available
}
```

### `SubscribeResults`

Server-streaming RPC that delivers results from all packet sources
(InjectPacket, PacketOut, etc.).

```protobuf
// First message confirms the subscription.
SubscribeResultsResponse { active: {} }
// Subsequent messages carry results.
SubscribeResultsResponse {
  result: {
    input_packet: { ... }
    output_packets: [ ... ]
    trace: { ... }
  }
}
```

### Dual port encoding

Output packets carry both port representations when translation is available:

```protobuf
message OutputPacket {
  uint32 dataplane_egress_port = 1;  // always present
  bytes p4rt_egress_port = 3;        // only when translated
  bytes payload = 2;
}
```

## Error codes

| Situation | gRPC status |
|-----------|-------------|
| No pipeline loaded | `FAILED_PRECONDITION` |
| P4RT port requested without port translation | `FAILED_PRECONDITION` |
| Invalid request | `INVALID_ARGUMENT` |
| Entity already exists (INSERT) | `ALREADY_EXISTS` |
| Entity not found (MODIFY/DELETE) | `NOT_FOUND` |
| Not primary for role | `PERMISSION_DENIED` |
