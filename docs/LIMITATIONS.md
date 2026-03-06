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
- **`@p4runtime_translation`: fully integrated for action params, match fields,
  and PacketIO metadata.** The `TypeTranslator` supports `sdn_bitwidth` and
  `sdn_string` with explicit, auto-allocate, and hybrid mapping modes.
  Note: v1model `p4c` does not emit `controller_packet_metadata` with
  `type_name`, so PacketIO translation is exercised via unit tests only.
- **No counters or meters via P4Runtime.** These work via the simulator
  protocol but cannot be managed through the gRPC server.
- **No digests, idle timeouts, or atomic write batches.**

## Simulator

- **Clone/resubmit/recirculate: no metadata preservation.** `clone3` field
  lists, `resubmit_preserving_field_list`, and `recirculate_preserving_field_list`
  do not carry metadata across — the replayed pipeline starts with fresh state.
- **Multicast: basic replication only.** Multicast group replication works
  for the trace tree (forking per replica). PRE entries are installed via
  P4Runtime `PacketReplicationEngineEntry`.
- **Per-table action specialization lost.** When a table has a single action
  ID in p4info but different compile-time specializations, only one is kept.
  Blocks 1 corpus test (`ternary2-bmv2`).

## BMv2 differential testing

- **No action profile support in BMv2 driver.** The bmv2_driver binary does not
  handle `member` or `group` STF directives. Tests that use action selectors or
  action profiles cannot be diff-tested yet.
- **System libgmp/libpcap dependency.** The BMv2 build links against system
  `libgmp` and `libpcap` rather than building them from source. The build uses
  genrules to copy headers into the build tree, but runtime linking requires the
  libraries to be installed (e.g. via Homebrew on macOS).
- **Small starter test set.** Only 5 corpus tests are diff-tested. Expanding to
  the full corpus requires verifying each test compiles under p4c-bm2-ss and
  produces matching output.

## p4c backend

- **No `lookahead` or `advance`.** The backend does not emit IR for parser
  `lookahead<T>()` or `packet.advance()`. This is a backend limitation, not
  a simulator one. Blocks 6 corpus tests.
