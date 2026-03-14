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
