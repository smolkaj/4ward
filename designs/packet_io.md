# Packet I/O Design

**Status: proposed**

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
   Non-standard extensions (DataplaneService, data-plane listener) are
   additive — they must not alter the spec-defined behavior.

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

message InjectPacketRequest {
  InputPacket packet = 1;
}

message InjectPacketResponse {
  ProcessPacketResult result = 1;
}

message ProcessPacketResult {
  InputPacket input = 1;
  repeated OutputPacket output_packets = 2;
  TraceTree trace = 3;
}

message SubscribeResultsResponse {
  oneof event {
    // Sent exactly once as the first message. Confirms the subscription
    // callback is registered and no results will be missed. The client
    // must wait for this before injecting packets.
    SubscriptionActive active = 1;
    ProcessPacketResult result = 2;
  }
}
```

`InjectPacket` is a simple unary RPC that returns the result inline. The
simulator is synchronous, so the result is available before the RPC returns.
This makes the simple case — inject a packet, see what happened — trivial,
with no need for a `SubscribeResults` subscription. The same result is also
delivered to `SubscribeResults` subscribers for fan-out.

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
know the callback is registered before injecting packets (via any source),
otherwise results could be missed. The protocol contract is: exactly one
`SubscriptionActive` first, then zero or more `ProcessPacketResult`. This
ordering is documented but cannot be expressed in the proto type system.

### PacketBroker

A new internal component that sits between all packet sources and the
simulator. It owns the CPU port routing logic and callback dispatch.

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
  │  2. Build ProcessPacketResult (input + outputs)     │
  │  3. Deliver result to SubscribeResults subscribers  │
  │  4. Route CPU-port outputs:                         │
  │     • egressPort == cpuPort → PacketIn on stream    │
  └─────────────────────┬───────────────────────────────┘
                        │
              simulator.processPacket()
```

`broker.processPacket(ingressPort, payload)` does:

1. Call `simulator.processPacket(ingressPort, payload)`.
2. Bundle the input and all outputs into a `ProcessPacketResult`.
3. Deliver the result to all `SubscribeResults` subscribers.
4. For each output where `egressPort == cpuPort`, build a PacketIn message
   (metadata via `PacketHeaderCodec`, translation via `TypeTranslator`)
   and send it on the active StreamChannel.
5. Return. All dispatch is synchronous — callbacks fire before the call
   returns.

### PacketOut

1. Controller sends PacketOut on the StreamChannel.
2. P4RuntimeService translates metadata and serializes the packet header.
3. P4RuntimeService calls `broker.processPacket(cpuPort, payload)`.
4. The broker processes the packet:
   - All subscribers receive a `ProcessPacketResult` (requirement 4).
   - CPU-port outputs become PacketIn on the StreamChannel (requirement 6 —
     only CPU-port outputs appear on the StreamChannel).
5. The broker call returns. All outputs have been dispatched.

### PacketIn

PacketIn is not tied to PacketOut. It is a side effect of *any*
`broker.processPacket()` call that produces output on the CPU port:

- A data-plane injection produces a punt to CPU port → PacketIn callback
  fires → controller receives PacketIn on StreamChannel.
- A PacketOut produces a clone to CPU → same path.

When no StreamChannel is active (no PacketIn callback registered), CPU-port
outputs are logged and dropped.

### Completion

The simulator is synchronous: `processPacket()` runs the full pipeline and
returns all output packets atomically. The broker dispatches all callbacks
before returning — no asynchrony.

- **`SubscribeResults` subscriber**: receives a `ProcessPacketResult` with
  all outputs bundled. Receiving the message IS the completion signal.
- **StreamChannel PacketOut**: PacketIn messages are delivered on the stream.
  Data-plane outputs are delivered to `SubscribeResults` subscribers.
  The controller itself has no completion signal for data-plane outputs —
  but a test harness subscribed via `SubscribeResults` does.
- **Direct injection via `InjectPacket`**: the RPC response contains the
  `ProcessPacketResult` directly. Completion is implicit — when the call
  returns, all outputs have been produced.

### What changes where

| Component | Today | After |
|---|---|---|
| `Dataplane` service | Two unary RPCs (`ProcessPacket`, `ProcessPacketWithTraceTree`) | Unary `InjectPacket` + server-streaming `SubscribeResults` |
| `P4RuntimeService` | Calls simulator directly; wraps all outputs as PacketIn | Calls broker; registers PacketIn callback on StreamChannel open |
| `PacketBroker` | Does not exist | New: wraps simulator, routes outputs, manages subscriptions |
| `PacketHeaderCodec` | Owned by P4RuntimeService | Moved into broker |
| `TypeTranslator` | Owned by P4RuntimeService | PacketIn/Out translation moved into broker |
| `Simulator` | No changes | No changes |
