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

In v1model, both intrinsic ports are derived from the port width (`bit<N>`, typically
`N=9`):

| Port | Default value |
|------|---------------|
| Drop port | `2^N - 1` (511) |
| CPU port | `2^N - 2` (510) |

v1model's `mark_to_drop` does nothing more than set `egress_spec` to the drop
port — dropping *is* egressing on the drop port.

BMv2 makes both configurable at runtime (`--drop-port`, `--cpu-port`).

## Problem

The drop port and CPU port are handled in different layers with no shared
abstraction. The drop port is derived from port width in the simulator; the CPU
port is derived from port width in the P4Runtime layer. Neither is configurable.

## Design

### Defaults

Both intrinsic ports have sensible defaults derived from the pipeline itself:
- **Drop port**: derived from the IR's `standard_metadata` port width in
  `V1ModelArchitecture`.
- **CPU port**: derived from the p4info's `controller_packet_metadata` field
  widths in `PacketHeaderCodec`.

### Simulator stays port-agnostic

The simulator knows about the drop port (needed by `mark_to_drop` and the
traffic manager) but not the CPU port. The CPU port has no data-plane semantics
— it's only meaningful to the layer that bridges data plane and control plane
(P4Runtime). This keeps the simulator focused on packet processing.

### Overrides

Both intrinsic ports are configurable via constructor/config parameters,
defaulting to null (use the derived value). Each entry point sources the
override however makes sense for its context:

| Entry point | Drop port override | CPU port override |
|---|---|---|
| **CLI** (`4ward run`) | `--drop-port` flag → passed to `Simulator` | `--cpu-port` flag → passed to `P4RuntimeServer` |
| **P4Runtime server** | `--drop-port` flag → passed to `Simulator` | `--cpu-port` flag → kept in server |
| **Web playground** | Same as P4Runtime server (it wraps one) | Same as P4Runtime server |
| **STF runner** | Constructor param on `StfRunner` (default: null) | N/A — no P4Runtime, no CPU port semantics |
| **Test harness** | Constructor param on `P4RuntimeTestHarness` | Constructor param on `P4RuntimeTestHarness` |
