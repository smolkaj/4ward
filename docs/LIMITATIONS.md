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
- **Meters always return GREEN.** `meter.execute_meter()` and
  `direct_meter.read()` always return GREEN (0). Rate limiting is not
  simulated — there are no real packet rates in STF tests.
- **`digest`, `log_msg` not implemented.** No corpus tests depend on these.

## P4Runtime server

- **Basic multi-controller arbitration.** The highest `election_id` becomes
  primary and may write; all controllers may read (P4Runtime spec §10).
  Not implemented: demotion notifications to existing non-primary controllers
  when a new primary is elected, and automatic promotion of the next-highest
  controller when the primary disconnects.
- **`@p4runtime_translation`: fully integrated for action params, match fields,
  and PacketIO metadata.** The `TypeTranslator` supports `sdn_bitwidth` and
  `sdn_string` with explicit, auto-allocate, and hybrid mapping modes.
  Note: v1model `p4c` does not emit `controller_packet_metadata` with
  `type_name`, so PacketIO translation is exercised via unit tests only.
- **Direct meters always return GREEN.** Direct meter configs can be
  written and read via P4Runtime, but the simulator does not perform
  real rate limiting — `direct_meter.read()` always returns the default
  color (GREEN).
- **No digests, idle timeouts, or atomic write batches.**

## Simulator

- **Clone/resubmit/recirculate: no metadata preservation.** `clone3` field
  lists, `resubmit_preserving_field_list`, and `recirculate_preserving_field_list`
  do not carry metadata across — the replayed pipeline starts with fresh state.
- **Multicast: basic replication only.** Multicast group replication works
  for the trace tree (forking per replica). PRE entries are installed via
  P4Runtime `PacketReplicationEngineEntry`.
## BMv2 differential testing

- **System libgmp/libpcap dependency.** The BMv2 build links against system
  `libgmp` and `libpcap` rather than building them from source. The build uses
  genrules to copy headers into the build tree, but runtime linking requires the
  libraries to be installed (e.g. via Homebrew on macOS).
- **All 186 corpus tests pass.**

## p4c backend

- **No `lookahead` or `advance`.** The backend does not emit IR for parser
  `lookahead<T>()` or `packet.advance()`. This is a backend limitation, not
  a simulator one. Blocks 6 corpus tests.
