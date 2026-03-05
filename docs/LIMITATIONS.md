# 4ward — Known Limitations

> Living document. Add entries when you take a shortcut or discover a gap.
> Remove entries when they get resolved.

Intentional shortcuts, missing features, and known gaps in the current
implementation. This is the place to record trade-offs so they don't get
lost in commit messages or forgotten entirely.

Agents: when you take a shortcut or skip a corner case, add it here. No
guilt — just write it down so someone can find it later.

## Architecture support

- **v1model only.** PSA, PNA, and TNA are not implemented. The architecture
  interface (`Architecture.kt`) is designed for pluggability, but only
  `V1ModelArchitecture.kt` exists today. 25 corpus tests are blocked on PSA.

## Externs

- **No meter support.** `meter.execute_meter()` is not implemented.
- **`digest`, `log_msg` not implemented.** No corpus tests depend on these.

## P4Runtime server

- **Single controller only.** No multi-controller arbitration or election ID
  tracking. The first connection is master unconditionally.
- **Wildcard reads only.** `Read` returns all table entries regardless of the
  request's entity filter. Per-table and per-entry reads are not implemented.
- **No p4-constraints validation.** `Write` does not enforce `@entry_restriction`
  or `@action_restriction` annotations from the P4 source.
- **No `@p4runtime_translation`.** Controller-facing values are passed through
  to the simulator without translation. SAI P4 programs (which translate all
  IDs to strings) will not work correctly until this is implemented.
- **Missing RPCs.** `GetForwardingPipelineConfig` and `Capabilities` return
  UNIMPLEMENTED.
- **No digests, idle timeouts, or atomic write batches.**

## Simulator

- **Clone: I2E only, no metadata preservation.** `clone()` and `clone3()`
  support ingress-to-egress cloning with last-writer-wins for multiple calls
  (matching BMv2). E2E clone, `clone3` metadata field lists, resubmit, and
  recirculate are not implemented. Blocks 1 corpus test
  (`v1model-special-ops-bmv2`).
- **Multicast: basic replication only.** Multicast group replication works
  for the trace tree (forking per replica). PRE entries are installed via
  P4Runtime `PacketReplicationEngineEntry`.
- **Per-table action specialization lost.** When a table has a single action
  ID in p4info but different compile-time specializations, only one is kept.
  Blocks 1 corpus test (`ternary2-bmv2`).

## p4c backend

- **No `lookahead` or `advance`.** The backend does not emit IR for parser
  `lookahead<T>()` or `packet.advance()`. This is a backend limitation, not
  a simulator one. Blocks 6 corpus tests.
