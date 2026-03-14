# Packet I/O Design

**Status: implemented**

## Assumptions

- There is a designated **CPU port** — see
  [intrinsic_ports.md](intrinsic_ports.md) for details.

## Requirements

1. **The data plane (simulator) processes packets.** A packet enters on a port,
   goes through the pipeline, and may produce zero or more output packets on
   various ports.

2. **The CPU port connects data plane (simulator) to control plane (P4Runtime).**
   Packets exiting on the CPU port become PacketIn. PacketOut produces packets
   entering on the CPU port.

3. **No output packets are lost.** All output packets are deliverable/observable,
   regardless of egress port or how the input packet was injected.

4. **All output packets from PacketOut are observable.** When a PacketOut
   produces output on data-plane ports, those packets must be visible to
   data-plane observers — not silently dropped.

5. **Completion is observable.** After injecting a packet — whether directly on
   the data plane or indirectly via PacketOut — the injector can determine
   when it has received all output packets produced as a result of that input
   packet.
   - *Motivation*: BMv2 does not have this property — the question "which
     outputs were produced as a result of my input?" is subject to race
     conditions, making deterministic testing difficult. As a testing and
     development tool, 4ward must do better.

6. **Strictly P4Runtime compliant.** The StreamChannel PacketIn/PacketOut
   behavior must conform to the
   [P4Runtime spec](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html).
   Non-standard extensions (DataplaneService) are additive — they must not
   alter the spec-defined behavior.

## Design

### Dataplane gRPC service

The Dataplane service is redesigned around two independent RPCs — one for
injection, one for observation:

```proto
service Dataplane {
  // Inject a single packet into the data plane. Returns the result inline.
  rpc InjectPacket(InjectPacketRequest) returns (InjectPacketResponse);

  // Observe results from ALL injection sources (InjectPacket, PacketOut,
  // other callers). Each result bundles the input with all its outputs.
  rpc SubscribeResults(SubscribeResultsRequest)
      returns (stream SubscribeResultsResponse);
}

message InputPacket {
  uint32 ingress_port = 1;
  bytes payload = 2;
}

message OutputPacket {
  uint32 egress_port = 1;
  bytes payload = 2;
}

message InjectPacketRequest {
  InputPacket packet = 1;
}

message InjectPacketResponse {
  repeated OutputPacket output_packets = 1;
  TraceTree trace = 2;
}

message ProcessPacketResult {
  InputPacket input = 1;
  repeated OutputPacket output_packets = 2;
  TraceTree trace = 3;
}

message SubscribeResultsRequest {}

message SubscribeResultsResponse {
  oneof event {
    // Sent exactly once as the first message. Confirms the subscription
    // is registered and no results will be missed. The client must wait
    // for this before injecting packets.
    SubscriptionActive active = 1;
    ProcessPacketResult result = 2;
  }
}

message SubscriptionActive {}
```

`TraceTree` is defined in `simulator.proto`.

`InjectPacket` is a simple unary RPC that returns the result inline. The
simulator is synchronous, so the result is available before the RPC returns.
This makes the simple case — inject a packet, see what happened — trivial,
with no need for a `SubscribeResults` subscription. The same result is also
delivered to `SubscribeResults` subscribers for fan-out.

There are two result shapes: `InjectPacketResponse` omits the input (the
caller already has it), while `ProcessPacketResult` includes it (subscribers
need to know which input produced the result).

Key properties:

- **Input-output correlation**: each `ProcessPacketResult` bundles the input
  packet with all its outputs and the trace tree. No ambiguity about which
  outputs belong to which input.
- **Injection and observation are decoupled**: inject via `InjectPacket`,
  observe via `SubscribeResults`. They are independent — you can subscribe
  without injecting, or inject without subscribing.
- **Fan-out**: multiple independent subscribers can observe results
  concurrently (e.g., a test harness and the web UI).
- **All sources visible**: `SubscribeResults` delivers results from all
  injection sources — `InjectPacket`, StreamChannel PacketOut, and any
  future sources.

#### Subscription handshake

The `SubscriptionActive` message solves a race condition: the client must
know the subscription is registered before injecting packets (via any source),
otherwise results could be missed. The protocol contract is: exactly one
`SubscriptionActive` first, then zero or more `ProcessPacketResult`. This
ordering is documented but cannot be expressed in the proto type system.

### PacketBroker

Fan-out layer between packet sources and the simulator. All callers —
`DataplaneService.injectPacket`, `P4RuntimeService` PacketOut — go through
`broker.processPacket()`. The broker delegates to the simulator and delivers
results to `SubscribeResults` subscribers.

CPU-port routing (PacketOut → PacketIn) is handled by
`P4RuntimeService.handlePacketOut`, not the broker, because it requires
pipeline state (`PacketHeaderCodec`, `TypeTranslator`) that only
P4RuntimeService has.

```
  P4Runtime StreamChannel                   Dataplane gRPC
  (control plane)                           (data plane)
        │                                         │
        │ PacketOut                  InjectPacket │
        ▼                                         ▼
  ┌─────────────────────────────────────────────────────┐
  │                    PacketBroker                     │
  │                                                     │
  │  1. Call simulator.processPacket()                  │
  │  2. Deliver result to SubscribeResults subscribers  │
  │  3. Return result to caller                         │
  └─────────────────────┬───────────────────────────────┘
                        │
              simulator.processPacket()
```

`broker.processPacket(ingressPort, payload)` does:

1. Call `simulator.processPacket(ingressPort, payload)`.
2. Deliver the result to all `SubscribeResults` subscribers.
3. Return the outputs and trace to the caller.

### PacketOut

1. Controller sends PacketOut on the StreamChannel.
2. P4RuntimeService translates metadata and serializes the packet header.
3. P4RuntimeService calls `broker.processPacket(cpuPort, payload)`.
4. The broker processes the packet and delivers results to subscribers
   (requirement 4 — PacketOut outputs visible to data-plane observers).
5. P4RuntimeService filters CPU-port outputs from the result and sends them
   as PacketIn on the StreamChannel (requirement 6 — only CPU-port outputs
   appear on the StreamChannel, per P4Runtime spec §16.1).
6. All outputs have been dispatched.

### PacketIn

PacketIn is produced by `P4RuntimeService.handlePacketOut` when a PacketOut
produces output on the CPU port. The service filters the broker's result,
builds PacketIn metadata via `PacketHeaderCodec`, translates via
`TypeTranslator`, and sends the PacketIn on the StreamChannel.

Data-plane injections via `InjectPacket` that happen to egress on the CPU
port do NOT produce PacketIn — they go through `DataplaneService`, which has
no access to `PacketHeaderCodec` or the StreamChannel. CPU-port outputs from
data-plane injections are visible via `SubscribeResults` only.

When no StreamChannel is active, PacketOut is rejected (no primary controller).

### Completion

The simulator is synchronous: `processPacket()` runs the full pipeline and
returns all output packets atomically.

- **`SubscribeResults` subscriber**: receives a `ProcessPacketResult` with
  all outputs bundled. Receiving the message IS the completion signal.
- **StreamChannel PacketOut**: PacketIn messages are delivered on the stream.
  Data-plane outputs are delivered to `SubscribeResults` subscribers.
  The controller itself has no completion signal for data-plane outputs —
  but a test harness subscribed via `SubscribeResults` does.
- **Direct injection via `InjectPacket`**: the RPC response contains the
  outputs and trace directly. Completion is implicit — when the call
  returns, all outputs have been produced.

### What changes where

| Component | Today | After |
|---|---|---|
| `Dataplane` service | Two unary RPCs (`ProcessPacket`, `ProcessPacketWithTraceTree`) | Unary `InjectPacket` + server-streaming `SubscribeResults` |
| `P4RuntimeService` | Calls simulator directly; wraps all outputs as PacketIn | Calls broker; filters CPU-port outputs into PacketIn |
| `PacketBroker` | Does not exist | New: wraps simulator, fan-out to SubscribeResults subscribers |
| `PacketHeaderCodec` | Owned by P4RuntimeService | No change — stays in P4RuntimeService |
| `TypeTranslator` | Owned by P4RuntimeService | No change — stays in P4RuntimeService |
| `Simulator` | No changes | No changes |
