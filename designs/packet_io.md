# Packet I/O Design

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

4. **Completion is observable.** After injecting a packet — whether directly on
   the data plane or indirectly via PacketOut — the caller can determine when
   all output packets have been produced.
