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
  `V1ModelArchitecture.kt` exists today. 26 corpus tests are blocked on PSA.

## Externs

- **No user-defined extern functions.** Extern functions declared in P4
  without a body (e.g. `extern void f(out bit<32> d, bit<32> s)`) cannot be
  executed — their semantics exist only in architecture-specific libraries.
  Blocks 1 corpus test (`extern-funcs-bmv2`).
- **No meter support.** `meter.execute_meter()` is not implemented.
- **`digest`, `log_msg` not implemented.** No corpus tests depend on these.

## P4Runtime server

- **Single controller only.** No multi-controller arbitration or election ID
  tracking. The first connection is master unconditionally.
- **No per-entry reads.** `Read` supports wildcard (all tables) and per-table
  filtering, but not per-entry reads with specific match keys.
- **No p4-constraints validation.** `Write` does not enforce `@entry_restriction`
  or `@action_restriction` annotations from the P4 source.
- **`@p4runtime_translation`: `sdn_bitwidth` only.** Bitwidth-based translation
  (e.g. 9-bit port → 32-bit SDN) is implemented. String-based translation
  (`sdn_string`) is not — SAI P4 programs that translate IDs to strings will
  not work correctly.
- **No counters, meters, or registers via P4Runtime.** These work via the
  simulator protocol but cannot be managed through the gRPC server.
- **No action profiles or groups via P4Runtime.** Action selector tables and
  group management are not implemented.
- **No digests, idle timeouts, or atomic write batches.**

## Simulator

- **Clone/resubmit/recirculate: no metadata preservation.** `clone3` field
  lists, `resubmit_preserving_field_list`, and `recirculate_preserving_field_list`
  do not carry metadata across — the replayed pipeline starts with fresh state.
- **Multicast: basic replication only.** Multicast group replication works
  for the trace tree (forking per replica). PRE entries are installed via
  P4Runtime `PacketReplicationEngineEntry`.
