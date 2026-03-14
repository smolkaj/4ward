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

6. **Strictly P4Runtime compliant.** The StreamChannel PacketIn/PacketOut
   behavior must conform to the
   [P4Runtime spec](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html).
   Non-standard extensions (DataplaneService, data-plane listener) are
   additive — they must not alter the spec-defined behavior.

## Current state

`P4RuntimeService.handlePacketOut()` calls `simulator.processPacket()` and
synchronously wraps *all* output packets as PacketIn responses. This is wrong
in three ways:

- **Non-compliant**: output packets on data-plane ports are sent as PacketIn on
  the StreamChannel. Only packets egressing on the CPU port should become
  PacketIn.
- **Coupled**: PacketOut synchronously returns PacketIn. A PacketOut may produce
  output on data-plane ports, not just the CPU port. These are independent.
- **Incomplete**: PacketIn is only generated for packets originating from
  PacketOut. Any packet egressing on the CPU port — regardless of how it
  entered the pipeline — should produce a PacketIn.

## Design

### PacketBroker

A new component that sits between packet sources (DataplaneService, P4Runtime
StreamChannel) and the simulator. It owns the CPU port routing logic.

```
                    ┌──────────────┐
  PacketOut ───────>│              │──── data-plane outputs ───> DataplaneService
                    │ PacketBroker │
  DataplaneService ─>│              │──── CPU port outputs ────> StreamChannel
                    └──────┬───────┘                             (as PacketIn)
                           │
                    simulator.processPacket()
```

Responsibilities:
- Accepts packets from any source (data-plane port or CPU port via PacketOut).
- Calls `simulator.processPacket()` synchronously.
- Partitions output packets by egress port:
  - **CPU port** → delivered as PacketIn on the active StreamChannel (if any).
  - **All ports** (including CPU) → returned to the caller.
- Handles `@controller_header` serialization/deserialization (currently in
  `PacketHeaderCodec`) and `@p4runtime_translation` (currently in
  `TypeTranslator`).

### PacketOut flow

1. Controller sends PacketOut on StreamChannel.
2. P4RuntimeService extracts metadata, translates if needed, and calls
   `broker.processPacket(cpuPort, payload)`.
3. The broker calls `simulator.processPacket()` and gets output packets.
4. CPU-port outputs are delivered as PacketIn on the StreamChannel.
5. Data-plane outputs are delivered to registered data-plane listeners
   (see [Data-plane listener](#data-plane-listener) below).
6. **Completion**: the broker call returns only after all outputs have been
   dispatched. The StreamChannel sender can treat the return as confirmation
   that the packet has been fully processed.

### PacketIn flow

1. Any packet source (DataplaneService, PacketOut, or future sources) calls
   `broker.processPacket(ingressPort, payload)`.
2. The broker calls `simulator.processPacket()`.
3. If any output packet has `egressPort == cpuPort` and there is an active
   StreamChannel, the broker builds a PacketIn message (using
   `PacketHeaderCodec` and `TypeTranslator`) and sends it on the stream.
4. If there is no active StreamChannel, CPU-port packets are logged and
   dropped (or buffered — TBD).

### DataplaneService changes

`DataplaneService.processPacket()` calls the broker instead of the simulator
directly. The synchronous response still contains *all* output packets
(including CPU-port ones), preserving full visibility for tests and debugging.
PacketIn delivery on the StreamChannel is an additional side effect.

### Data-plane listener

The broker supports registering a **data-plane listener** — a callback invoked
for every output packet on a non-CPU port, regardless of how the input packet
was injected. This is how data-plane outputs from PacketOut become observable
(requirement 4).

The DataplaneService registers itself as the data-plane listener. When a
PacketOut produces output on data-plane ports, those packets are delivered to
the listener alongside any PacketIn messages on the StreamChannel.

When no listener is registered, data-plane outputs from PacketOut are logged
and dropped.

### Completion

Requirement 5 is satisfied because `simulator.processPacket()` is synchronous:
it runs the full pipeline and returns all output packets atomically. The broker
adds routing on top but introduces no asynchrony — all output dispatch
(PacketIn delivery, data-plane listener callbacks) happens before the broker
call returns. The injector knows processing is complete when the call returns.

This holds for all paths:
- **DataplaneService**: the gRPC response contains all outputs. Done.
- **PacketOut**: the broker call in `handlePacketOut()` returns after all
  outputs are dispatched (PacketIn on the stream, data-plane outputs to the
  listener). The StreamChannel handler can proceed to the next message.
- **PacketOut completion for the controller**: the controller knows its
  PacketOut has been fully processed because the broker processes packets
  sequentially — the next StreamChannel message is not processed until the
  previous one completes.
