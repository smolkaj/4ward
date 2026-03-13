# Intrinsic Ports Design

## What makes a port intrinsic?

All non-intrinsic ports are equal and interchangeable — the data plane treats
them identically. Intrinsic ports are special because the system attaches
semantics to them beyond just forwarding:

- **Drop port**: packets egressing on this port are discarded. No output is
  produced.
- **CPU port**: packets egressing on this port are delivered to the control plane
  (P4Runtime) as PacketIn. Packets from the control plane (PacketOut) enter the
  data plane on this port.

### v1model specifics

In v1model, both ports are derived from the port width (`bit<N>`, typically
`N=9`):

| Port | Default value |
|------|---------------|
| Drop port | `2^N - 1` (511) |
| CPU port | `2^N - 2` (510) |

`mark_to_drop()` does nothing more than set `egress_spec` to the drop port —
dropping *is* egressing on the drop port.

BMv2 makes both configurable at runtime (`--drop-port`, `--cpu-port`).

## Problem

The drop port and CPU port are handled in different layers with no shared
abstraction. The drop port is derived from port width in the simulator; the CPU
port is derived from port width in the P4Runtime layer. Neither is configurable.

## Design

Both intrinsic ports should have sensible defaults (derived from port width) and
be configurable. Open questions:

1. Where does the configuration live? Options: simulator proto, CLI flags,
   `SetForwardingPipelineConfig`, or a combination.

2. Should the simulator know about the CPU port, or should it remain
   port-agnostic with the CPU port handled externally?

3. Should there be a shared definition of intrinsic ports that both layers
   reference?
