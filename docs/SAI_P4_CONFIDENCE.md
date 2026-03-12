# SAI P4 — Confidence Assessment

> Living document. The goal is to systematically close the gap between "it
> compiles and forwards one packet" and "DVaaS-ready with high confidence."

## Current state

SAI P4 middleblock works end-to-end through P4Runtime with high confidence.
All 25 tables in the middleblock p4info have hand-crafted E2E tests exercising
the full P4Runtime translation stack (sdn_string round-trip).

### Test coverage summary

- **Hand-crafted E2E tests** (`SaiP4E2ETest`): 39 tests exercise all 25
  middleblock tables through the full P4Runtime stack (translation, validation,
  constraints, PacketIO, forwarding).
- **Constraint tests** (`SaiP4ConstraintTest`): 10 tests validate
  `@entry_restriction` and `@action_restriction` on real SAI P4 tables.
- **p4testgen**: 500 symbolic execution tests on SAI P4 middleblock (in
  CI). Exercises all reachable paths but without `@p4runtime_translation`.
- **STF corpus + BMv2 diff**: 186 v1model corpus tests pass, all
  bit-for-bit identical to BMv2. Validates the simulator engine that SAI
  P4 runs on.

## What works (tested E2E)

- [x] Compile real SAI P4 middleblock (from sonic-pins) via p4c-4ward
- [x] Load pipeline via `SetForwardingPipelineConfig`
- [x] P4Runtime Write with `@p4runtime_translation` (string IDs for vrf_id,
      nexthop_id, router_interface_id, port_id, tunnel_id, mirror_session_id,
      cpu_queue)
- [x] P4Runtime Read with SDN-to-dataplane translation round-trip
- [x] P4Runtime Delete
- [x] L3 IPv4 forwarding: MAC rewrites, TTL decrement
- [x] L3 IPv6 routing with translated vrf_id + nexthop_id (128-bit LPM)
- [x] IPv6 multicast with translated vrf_id
- [x] ACL ingress drop (deny action prevents forwarding)
- [x] ACL redirect to nexthop (overrides normal routing)
- [x] ACL egress with translated out_port (port_id_t)
- [x] ACL ingress mirror+redirect with translated nexthop_id
- [x] IPv4 multicast replication to multiple ports
- [x] IPv6 tunnel termination (ternary on 128-bit IPv6)
- [x] Tunnel encap with translated tunnel_id + router_interface_id
- [x] VLAN + VLAN membership with translated port_id
- [x] L3 admission with translated in_port (port_id_t)
- [x] Mirror session with translated mirror_session_id + port_id params
- [x] Multicast rewrites with translated multicast_replica_port
- [x] Ingress cloning with translated mirror_egress_port
- [x] WCMP action profile: members and groups round-trip via P4Runtime
- [x] PacketIO via StreamChannel (packet_out → simulate → packet_in)
- [x] `@p4runtime_translation_mappings` (explicit VRF `""` → `0` mapping)
- [x] `@refers_to` referential integrity enforcement (7 E2E tests + 14 unit tests)
- [x] p4-constraints on SAI P4: entry and action restrictions enforced (10 tests)
- [x] Write validation (action IDs, params, match fields, priority)
- [x] p4testgen symbolic execution on SAI P4 middleblock (500 tests, in CI)

## Table coverage detail

### Middleblock tables tested through full P4Runtime translation (25/25)

| Table | Test type |
|-------|-----------|
| `vrf_table` | E2E round-trip + constraint |
| `ipv4_table` | E2E round-trip + forwarding |
| `ipv6_table` | E2E round-trip (vrf_id + nexthop_id + 128-bit LPM) |
| `nexthop_table` | E2E round-trip + forwarding |
| `router_interface_table` | E2E round-trip + constraint + forwarding |
| `neighbor_table` | E2E forwarding chain (implicit) |
| `tunnel_table` | E2E round-trip (tunnel_id + router_interface_id) |
| `ipv6_tunnel_termination_table` | E2E round-trip (ternary IPv6) |
| `ipv4_multicast_table` | E2E forwarding + constraint |
| `ipv6_multicast_table` | E2E round-trip (vrf_id) |
| `wcmp_group_table` | E2E action profile round-trip |
| `acl_ingress_table` | E2E drop + redirect |
| `acl_ingress_mirror_and_redirect_table` | E2E round-trip (nexthop_id) |
| `acl_ingress_security_table` | E2E round-trip (ternary IP) |
| `acl_egress_table` | E2E round-trip (out_port) |
| `acl_pre_ingress_table` | Constraint tests |
| `vlan_table` | E2E round-trip |
| `vlan_membership_table` | E2E round-trip (port_id) |
| `disable_ingress_vlan_checks_table` | E2E round-trip (wildcard LPM) |
| `disable_egress_vlan_checks_table` | E2E round-trip (wildcard LPM) |
| `disable_vlan_checks_table` | Constraint test |
| `l3_admit_table` | E2E round-trip (dst_mac + in_port) |
| `mirror_session_table` | E2E round-trip (mirror_session_id + port_id params) |
| `multicast_router_interface_table` | E2E round-trip (replica_port) |
| `ingress_clone_table` | E2E round-trip (mirror_egress_port) |

### Not in middleblock p4info (5 tables, TOR/FBR only)

These tables exist in the SAI P4 source but are optimized away by p4c for the
middleblock instantiation because their `apply()` calls are behind
instantiation-specific `#ifdef` guards:

- `acl_ingress_qos_table` — TOR, FBR
- `acl_ingress_counting_table` — FBR
- `acl_egress_dhcp_to_host_table` — TOR
- `acl_pre_ingress_vlan_table` — TOR
- `acl_pre_ingress_metadata_table` — TOR

## Open gaps

No critical gaps remain. All middleblock tables are tested through the full
P4Runtime translation stack.

**Remaining low-priority items:**

- The 5 TOR/FBR-only tables above could be tested by compiling a TOR
  instantiation. These tables use the same `sdn_string` translation mechanism
  already thoroughly exercised on the middleblock tables.

## Resolved gaps

### 1. ~~Packet processing coverage~~ ✅

p4testgen runs symbolic execution (500 tests) on the SAI P4 middleblock in CI.
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

### 6. ~~`@refers_to` referential integrity~~ ✅

`ReferenceValidator` parses `@refers_to` annotations from p4info at pipeline
load time and validates foreign-key relationships on every INSERT/MODIFY.
Handles match fields, action parameters, and `builtin::multicast_group_table`
references. 14 unit tests + 7 E2E tests on SAI P4.

### 7. ~~Translation coverage gap~~ ✅

All 25 middleblock tables now have hand-crafted E2E round-trip tests exercising
the full P4Runtime translation stack. The 15 newly tested tables exercise all
remaining translated types: `tunnel_id_t`, `mirror_session_id_t`, `cpu_queue_t`,
and `port_id_t` in match fields (not just action params). The 5 tables not in
the middleblock p4info use the same `sdn_string` mechanism and would only need
a TOR/FBR compilation to test.
