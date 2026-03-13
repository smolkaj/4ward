# Packet I/O Design

## Assumptions

- There is a designated **CPU port** — a well-known port number that bridges
  data plane and control plane. The CPU port is not defined by the P4
  architecture; in BMv2 it is a runtime flag (`--cpu-port`), and in 4ward it is
  derived from the p4info's `controller_packet_metadata` field widths (currently
  `2^portBits - 2`, e.g. port 510 for 9-bit ports).

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
