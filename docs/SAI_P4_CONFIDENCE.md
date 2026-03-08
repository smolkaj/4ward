# SAI P4 — Confidence Assessment

> Living document. The goal is to systematically close the gap between "it
> compiles and forwards one packet" and "DVaaS-ready with high confidence."

## Current state

**All identified gaps are closed.** The simulator handles the full SAI P4
middleblock pipeline with high confidence:

- **p4testgen**: uncapped symbolic execution (all paths explored or Z3 timeout)
  runs in CI on every push. Previously capped at 500 tests; now exhaustive.
- **Hand-crafted E2E tests**: 5 tests exercise specific SAI P4 features through
  the P4Runtime server — L3 forwarding, ACL drop, ACL redirect, IPv4 multicast,
  and WCMP action selectors.
- **Constraint coverage**: 10 tests validate `@entry_restriction` and
  `@action_restriction` on real SAI P4 tables.
- **PacketIO**: `PacketHeaderCodec` serializes/deserializes `packet_in`/
  `packet_out` headers per p4info, tested E2E via StreamChannel.
- **Translation mappings**: `@p4runtime_translation_mappings` extracted by the
  p4c backend and honored by `TypeTranslator` (VRF default `""` → `0`).

## What works (tested E2E)

- [x] Compile real SAI P4 middleblock (from sonic-pins) via p4c-4ward
- [x] Load pipeline via `SetForwardingPipelineConfig`
- [x] P4Runtime Write with `@p4runtime_translation` (string IDs for vrf_id,
      nexthop_id, router_interface_id, port_id)
- [x] P4Runtime Read with SDN-to-dataplane translation round-trip
- [x] P4Runtime Delete
- [x] L3 IPv4 forwarding: MAC rewrites, TTL decrement
- [x] ACL ingress drop (deny action prevents forwarding)
- [x] ACL redirect to nexthop (overrides normal routing)
- [x] IPv4 multicast replication to multiple ports
- [x] WCMP action profile: members and groups round-trip via P4Runtime
- [x] PacketIO via StreamChannel (packet_out → simulate → packet_in)
- [x] `@p4runtime_translation_mappings` (explicit VRF `""` → `0` mapping)
- [x] p4-constraints on SAI P4: entry and action restrictions enforced (10 tests)
- [x] Write validation (action IDs, params, match fields, priority)
- [x] p4testgen symbolic execution on SAI P4 middleblock (uncapped, in CI)

## Resolved gaps

### 1. ~~Packet processing coverage~~ ✅

p4testgen runs uncapped symbolic execution on the SAI P4 middleblock in CI.
Uses `-DPLATFORM_BMV2` to strip `@p4runtime_translation` annotations;
`@entry_restriction`/`@action_restriction` pass through without issue.

### 2. ~~`@p4runtime_translation_mappings`~~ ✅

The p4c backend now extracts `@p4runtime_translation_mappings` annotations
from P4 source and emits `TranslationEntry` protos in the IR. The Kotlin
`TypeTranslator` honors these explicit mappings. Only use case: VRF default
`""` → `0`.

### 3. ~~Constraint violation testing~~ ✅

`SaiP4ConstraintTest` validates constraints on real SAI P4 tables:
- `acl_pre_ingress_table`: DSCP-without-IP-type rejected, IPv4+IPv6
  mutual exclusion rejected, DSCP-with-IPv4 accepted
- `ipv4_multicast_table`: unicast address rejected, multicast accepted
- `set_multicast_group_id` action: `group_id == 0` rejected
- `router_interface_table`: `src_mac == 0` rejected, nonzero accepted
- `disable_vlan_checks_table`: exact dummy_match rejected, wildcard accepted

### 4. ~~PacketIO via P4Runtime StreamChannel~~ ✅

`PacketHeaderCodec` serializes `packet_out_header` fields (egress_port) into
a binary header prepended to the payload, and deserializes `packet_in_header`
fields (ingress_port, target_egress_port) from simulation output. Handles
`@p4runtime_translation` on port_id_t fields. Tested E2E in `SaiP4E2ETest`.

### 5. ~~Complex switching features~~ ✅

Hand-crafted E2E tests exercise SAI P4's specific table structures:
- **ACL ingress**: deny action drops packets (acl_ingress_table)
- **ACL redirect**: redirect_to_nexthop overrides routing (acl_ingress_table)
- **Multicast**: IPv4 multicast replication via multicast group (ipv4_multicast_table)
- **WCMP**: action profile members and groups round-trip (wcmp_group_table)

Features not individually tested (covered by p4testgen's path exploration):
- Ingress cloning / mirroring
- L3 admit / pre-ingress ACL
- Tunneling (GRE encap/decap)

## Remaining notes

- **Tunneling / GRE**: p4testgen explores tunnel paths symbolically, but no
  hand-crafted test exercises the full GRE encap/decap flow through SAI P4
  tables. Add if DVaaS tunneling use cases arise.
- **Ingress cloning**: covered generically by STF corpus clone tests, and by
  p4testgen's path exploration on SAI P4. No SAI-specific hand-crafted test.
