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

### `InjectPackets`

Client-streaming RPC for bulk packet injection. Packets are processed
concurrently as they arrive from the stream. Results are **not** returned
in the response — use [`SubscribeResults`](#subscriberesults) to collect them.

```protobuf
rpc InjectPackets(stream InjectPacketRequest) returns (InjectPacketsResponse);
message InjectPacketsResponse {}  // empty — results via SubscribeResults
```

**Recommended pattern for DVaaS / bulk workloads:**

1. Open a `SubscribeResults` stream.
2. Wait for the `SubscriptionActive` message — this confirms the
   subscription is registered and no results will be missed.
3. Send all packets via `InjectPackets`.
4. Collect results from the subscription (exactly one per injected
   packet).

Packets process concurrently across available cores, with trace tree fork
branches (WCMP groups, multicast, clones) also parallelized within each
packet.

### `SubscribeResults`

Server-streaming RPC that delivers results from all packet sources
(InjectPacket, InjectPackets, PacketOut, etc.).

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

### Matching results to injected packets

Each `ProcessPacketResult` in the `SubscribeResults` stream includes the
full `InputPacket` (ingress port + payload). Match results to injected
packets by comparing the payload bytes.

!!! tip
    For DVaaS workloads, embed a unique tag in each test packet (e.g., in
    an unused header field or the payload body) to make matching
    unambiguous.

**Ordering:** With concurrent processing (`InjectPackets`), results may
arrive in any order. Do not assume the result stream matches the
injection order.

**Completeness:** You will receive exactly one `ProcessPacketResult` per
injected packet. Count results to know when you're done.

**Relationship to P4Runtime PacketIn:** When a packet triggers
copy-to-CPU (e.g., SAI P4's `acl_copy` or `acl_trap`), two things
happen:

- The CPU-bound clone appears as a **PacketIn** on the P4Runtime
  `StreamChannel`.
- The complete result (all outputs including the clone, plus the trace
  tree) appears in **`SubscribeResults`**.

`SubscribeResults` gives the full picture for every packet.
`StreamChannel` PacketIn only carries the CPU-port copies — it's the
standard P4Runtime mechanism for packets punted to the controller.

!!! note "SubscribeResults vs PacketIn"
    **`SubscribeResults`** delivers exactly N results for N injected
    packets, so you always know when you're done.

    **`PacketIn`** on `StreamChannel` is convenient because it carries
    `@controller_header` metadata already parsed — but there's no
    end-of-batch marker. To know when you've seen all PacketIns, count
    CPU-port outputs in `SubscribeResults` — that tells you exactly how
    many PacketIns to expect.

### Dual port encoding

Output packets carry both port representations when translation is available:

```protobuf
message OutputPacket {
  uint32 dataplane_egress_port = 1;  // always present
  bytes p4rt_egress_port = 3;        // only when translated
  bytes payload = 2;
}
```

## Performance

While 4ward optimizes for correctness and simplicity over raw speed, it
achieves practical throughput for production test workloads.

Representative numbers on SAI P4 middleblock with 10k table entries.
Measured on AMD Ryzen 9 7950X3D (16 cores, 128 MB L3), OpenJDK 21. Throughput in packets/sec (higher is better).

| Workload | Sequential, 1 core | Sequential, 16 cores | Batch, 1 core | Batch, 16 cores |
|----------|--------------------|----------------------|---------------|-----------------|
| L3 forwarding | 1,800 | 1,900 | 1,800 | 9,700 |
| WCMP ×16 members | 1,200 | 1,400 | 950 | 5,400 |
| WCMP ×16 + mirror | 810 | 1,050 | 600 | 3,500 |

Sequential = `InjectPacket` (one packet at a time).
Batch = `InjectPackets` (1000 packets streamed concurrently).

- **L3 forwarding** — VRF → LPM → nexthop → MAC rewrite. No forks.
- **WCMP ×16** — 16-member action selector (16 trace tree branches).
- **WCMP ×16 + mirror** — WCMP ×16 + ingress clone (32 branches).
- **1 core** — no parallelism. Useful for estimating per-packet cost.
- **16 cores** — `InjectPacket` parallelizes fork branches within each
  packet. `InjectPackets` adds cross-packet parallelism.

Throughput scales with available cores. See [Track 10](https://github.com/smolkaj/4ward/blob/main/docs/ROADMAP.md#track-10-dataplane-performance)
in the roadmap for methodology and optimization details.

## Error codes

| Situation | gRPC status |
|-----------|-------------|
| No pipeline loaded | `FAILED_PRECONDITION` |
| P4RT port requested without port translation | `FAILED_PRECONDITION` |
| Invalid request | `INVALID_ARGUMENT` |
| Entity already exists (INSERT) | `ALREADY_EXISTS` |
| Entity not found (MODIFY/DELETE) | `NOT_FOUND` |
| Not primary for role | `PERMISSION_DENIED` |
