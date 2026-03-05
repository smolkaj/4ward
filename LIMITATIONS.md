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

- **Limited extern functions.** `mark_to_drop`, `verify`, `clone`, and
  `clone3` are implemented. All other v1model externs — `hash`,
  `verify_checksum`, `update_checksum`, `random`, `digest`, `log_msg` —
  are unimplemented and will crash with `"unhandled extern call"`.
  Blocks ~6 corpus tests (verify/checksum).
- **No register, counter, or meter support.** Extern object methods
  (`register.read`/`.write`, `counter.count`, etc.) are not implemented.
  Blocks ~3 corpus tests.

## Simulator

- **`ReadEntries` is a stub.** The `ReadEntriesRequest` handler returns an
  empty response (`Simulator.kt:143`).
- **Clone: I2E only, no metadata preservation.** `clone()` and `clone3()`
  support ingress-to-egress cloning with last-writer-wins for multiple calls
  (matching BMv2). E2E clone, `clone3` metadata field lists, resubmit, and
  recirculate are not implemented.
- **Multicast: basic replication only.** Multicast group replication works
  for the trace tree (forking per replica). PRE entries are installed via
  P4Runtime `PacketReplicationEngineEntry`.

## Header types

- **Header union gaps.** Basic union support landed, but one-valid-at-a-time
  semantics (P4 spec §8.20) are not fully enforced. Header stacks of unions
  are not supported. Blocks ~10 corpus tests.
- **Header stack `push_front`/`pop_front` not implemented.** Array indexing
  and field access work, but the stack manipulation primitives do not.
  Blocks ~1 corpus test.

## p4c backend

- **No `lookahead` or `advance`.** The backend does not emit IR for parser
  `lookahead<T>()` or `packet.advance()`. This is a backend limitation, not
  a simulator one. Blocks 6 corpus tests.
- **Integer literals without explicit bit type** are not always handled
  correctly. Blocks ~2 corpus tests.

## Testing

- **STF parser edge cases.** Some STF syntax variants (large hex literals,
  unusual formatting) cause `NumberFormatException`. Blocks ~2 corpus tests.
- **Payload mismatches.** ~5 corpus tests produce correct control flow but
  emit packets with wrong payload bytes. Root causes vary and are not yet
  triaged individually.
