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
  `PacketHeaderCodec` handles `packet_in`/`packet_out` header serialization
  per p4info `controller_packet_metadata`, tested E2E on SAI P4.
- **Direct meters always return GREEN.** Direct meter configs can be
  written and read via P4Runtime, but the simulator does not perform
  real rate limiting — `direct_meter.read()` always returns the default
  color (GREEN).
- **No `@refers_to` referential integrity enforcement.** SAI P4 uses
  `@refers_to` annotations to declare foreign-key relationships between
  tables (e.g., `nexthop_id` must exist in `nexthop_table`). These are not
  validated at write time — entries referencing non-existent entries are
  silently accepted. Inconsistencies surface at packet simulation time
  (table miss instead of hit).
- **No digests, idle timeouts, or atomic write batches.**

## DVaaS PoC harness

- **Depends on a pinned patched `sonic-pins` checkout.** The current
  `ValidateDataplane` proof uses a temporary overlay plus a checked-in patch
  stack in `tools/dvaas_overlay/` against a specific upstream commit. This is
  enough for end-to-end validation on local macOS and Ubuntu CI, but it is
  still a downstream compatibility harness rather than a native upstream DVaaS
  integration.
- **SAI mirror validation currently observes the peer return-wire directly.**
  In linked two-`4ward` mode, returned SUT packets are surfaced to DVaaS as
  observable `PacketIn`s without depending on the local control-switch SAI
  pipeline to re-punt them. This is sufficient for `ValidateDataplane`, but it
  is a mirror-testbed shortcut rather than a full in-band control-switch model.
- **SAI front-panel port translations are not pinned yet.** The current SAI
  PoC still relies on the present auto-allocation order for some translated
  `port_id_t` values. The local proof is wired to a consistent route/output
  choice, but the final setup should use explicit translation mappings for
  front-panel ports so `EthernetN` is stable across instances and runs.
- **Darwin uses transient third-party compatibility patches.** The local macOS
  path applies patch files to the pinned `sonic-pins` checkout and its vendored
  gRPC external to work around Linux-only headers and an Apple-clang template
  parsing issue. These patches should disappear once upstream dependencies are
  updated or the DVaaS path runs in a Linux-only environment.

## Simulator

- **Multicast: basic replication only.** Multicast group replication works
  for the trace tree (forking per replica). PRE entries are installed via
  P4Runtime `PacketReplicationEngineEntry`.

## Web playground

- **Single-replica clone sessions.** The UI creates clone sessions with one
  replica. Multi-replica cloning requires the gRPC API.
- **No multicast group UI.** The backend supports multicast groups, but the
  web UI only exposes clone sessions.
- **No MODIFY for table entries.** The UI only supports INSERT and DELETE.
  To change an entry, delete and re-add it.
- **No default action changes.** There is no UI to change a table's default
  action.
- **RANGE match type not supported.** The match field input falls through to
  exact-match encoding — range values will be silently misinterpreted.
- **No counters, meters, registers, or action profile UI.** These P4Runtime
  entities are supported by the backend but not exposed in the browser.
- **Read API only returns table entries.** `GET /api/read` hard-codes a
  `TableEntry` filter. Clone sessions, counters, and other entity types
  cannot be read back through the REST API.
- **Forking programs: output packets panel shows "dropped".** When the trace
  tree forks (clone, multicast), output packets appear only in branch leaves
  of the trace — the top-level `output_packets` list is empty. The user must
  inspect the Trace tab.
- **No `StreamChannel`.** The web UI uses REST APIs, not the P4Runtime
  bidirectional stream. This means no PacketIO (`packet_in`/`packet_out`),
  no digest notifications, and no arbitration updates. Packets are injected
  directly to the simulator; controller-bound packets are silently lost.
- **No packet history.** Only the most recent packet result is shown. Sending
  another packet overwrites the previous output.
- **No persistence.** Pipeline and table state are in-memory. Restarting the
  server clears everything. Editor content is not saved across page refreshes.
- **Single shared state.** All browser sessions share one simulator instance.
  Concurrent users will interfere with each other.
- **No compilation timeout.** The p4c subprocess has no timeout. A
  pathological P4 program could hang the server indefinitely.
- **Monaco editor requires internet.** Loaded from `cdn.jsdelivr.net`.
  The playground does not work offline.
- **Three bundled examples only.** `basic_table`, `passthrough`, `mirror`.

## BMv2 differential testing

- **System libgmp/libpcap dependency.** The BMv2 build links against system
  `libgmp` and `libpcap` rather than building them from source. The build uses
  genrules to copy headers into the build tree, but runtime linking requires the
  libraries to be installed (e.g. via Homebrew on macOS).
- **All 186 corpus tests pass.**

## p4c backend

- **`gauntlet_various_ops-bmv2` compilation timeout.** p4c-4ward takes 10+
  minutes on this program. Performance issue, not a missing feature. Blocks
  1 corpus test.
