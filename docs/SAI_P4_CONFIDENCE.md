# SAI P4 — Confidence Assessment

> Living document. The goal is to systematically close the gap between "it
> compiles and forwards one packet" and "DVaaS-ready with high confidence."

## Current state

SAI P4 middleblock works end-to-end through P4Runtime with high confidence
on the features that are tested. Two gaps remain:

1. **`@refers_to` referential integrity** is not enforced at write time.
2. **20 of 30 SAI P4 tables** lack hand-crafted E2E tests exercising the
   full P4Runtime translation stack. They are covered by p4testgen, but
   p4testgen compiles with `-DPLATFORM_BMV2` which strips
   `@p4runtime_translation` — so translation is not exercised on those
   tables.

### Test coverage summary

- **Hand-crafted E2E tests** (`SaiP4E2ETest`): 15 tests exercise 10/30
  SAI P4 tables through the full P4Runtime stack (translation, validation,
  constraints, PacketIO).
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
- [x] p4testgen symbolic execution on SAI P4 middleblock (500 tests, in CI)

## Table coverage detail

### Tested through full P4Runtime translation (10/30)

| Table | Test type |
|-------|-----------|
| `vrf_table` | E2E round-trip + constraint |
| `ipv4_table` | E2E round-trip + forwarding |
| `nexthop_table` | E2E round-trip + forwarding |
| `router_interface_table` | E2E round-trip + constraint + forwarding |
| `neighbor_table` | E2E forwarding chain (implicit) |
| `ipv4_multicast_table` | E2E forwarding + constraint |
| `wcmp_group_table` | E2E action profile round-trip |
| `acl_ingress_table` | E2E drop + redirect |
| `acl_pre_ingress_table` | Constraint tests |
| `disable_vlan_checks_table` | Constraint test |

### Covered by p4testgen only (no translation exercised) (20/30)

- `ipv6_table`, `ipv6_multicast_table` — IPv6 routing
- `tunnel_table`, `ipv6_tunnel_termination_table` — tunneling
- `vlan_table`, `vlan_membership_table`, `disable_ingress_vlan_checks_table`,
  `disable_egress_vlan_checks_table` — VLAN
- `l3_admit_table` — L3 admission
- `mirror_session_table` — mirroring
- `multicast_router_interface_table` — multicast source MAC rewrites
- `acl_egress_table`, `acl_egress_dhcp_to_host_table` — egress ACL
- `acl_ingress_qos_table`, `acl_ingress_counting_table`,
  `acl_ingress_mirror_and_redirect_table`, `acl_ingress_security_table` — ingress ACL variants
- `acl_pre_ingress_vlan_table`, `acl_pre_ingress_metadata_table` — pre-ingress ACL variants
- `ingress_clone_table` — ingress cloning

## Open gaps

### 1. `@refers_to` referential integrity

SAI P4 uses `@refers_to` annotations to declare foreign-key relationships
between tables (e.g., `nexthop_id` in `ipv4_table` must exist in
`nexthop_table`). These are not validated at write time — entries
referencing non-existent entries are silently accepted.

**Impact**: moderate. Inconsistencies surface at simulation time (table
miss instead of hit). DVaaS sends packets and compares outputs, so it
would catch the mismatch — but the error message would be confusing
(wrong output) rather than clear (invalid write).

### 2. Translation coverage gap

p4testgen's 500 tests are compiled with `-DPLATFORM_BMV2`, which strips
`@p4runtime_translation` annotations. This means the translation
stack (`TypeTranslator`, `PacketHeaderCodec`) is only exercised on the
10 tables with hand-crafted E2E tests. The remaining 20 tables have
never been tested through the full P4Runtime translation path.

**Impact**: low for tables with simple exact-match string IDs (all use
the same `sdn_string` mechanism). Higher for tables with non-trivial
match types or composite fields.

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
