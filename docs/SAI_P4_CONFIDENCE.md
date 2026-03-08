# SAI P4 — Confidence Assessment

> Living document. Check off items as they're resolved. The goal is to
> systematically close the gap between "it compiles and forwards one packet"
> and "DVaaS-ready with high confidence."

## Current state

**Packet coverage: 501 packets.** `SaiP4E2ETest` sends 1 hand-crafted IPv4
packet through the L3 forwarding path. p4testgen generates 500 test vectors
via symbolic execution (Z3), covering diverse program paths through the
27-table middleblock pipeline. All 501 pass.

**Constraint coverage: 10 tests.** `SaiP4ConstraintTest` validates that
`@entry_restriction` and `@action_restriction` constraints on real SAI P4
tables are enforced — 5 valid entries accepted, 5 violations rejected.

## What works (tested E2E)

- [x] Compile real SAI P4 middleblock (from sonic-pins) via p4c-4ward
- [x] Load pipeline via `SetForwardingPipelineConfig`
- [x] P4Runtime Write with `@p4runtime_translation` (string IDs for vrf_id,
      nexthop_id, router_interface_id, port_id)
- [x] P4Runtime Read with SDN-to-dataplane translation round-trip
- [x] P4Runtime Delete
- [x] L3 IPv4 forwarding: MAC rewrites, TTL decrement (1 packet)
- [x] p4-constraints on SAI P4: entry and action restrictions enforced (10 tests)
- [x] Write validation (action IDs, params, match fields, priority)
- [x] p4testgen symbolic execution on SAI P4 middleblock (500 tests, all pass)

## Gaps — ordered by impact

### 1. ~~Packet processing coverage~~ ✅ Done

p4testgen generates 500 test vectors for the SAI P4 middleblock in ~4 seconds.
All pass against the simulator. Uses `-DPLATFORM_BMV2` to strip
`@p4runtime_translation` annotations; `@entry_restriction`/`@action_restriction`
pass through without issue.

Currently `manual` tagged (not in CI's default test suite). Uncapped
exploration was not completed — p4testgen may generate more than 500 tests
if allowed to run to completion, but exceeds local machine memory.

### 2. `@p4runtime_translation_mappings` (functional gap)

**Problem:** SAI P4 declares explicit mappings like:
```
@p4runtime_translation_mappings({ {"", 0} })
type bit<N> vrf_id_t;
```
This maps the default VRF string `""` to numeric value `0`. We auto-allocate
mappings instead of honoring the explicit ones.

**Impact:** VRF constraint `vrf_id != ""` can't be enforced correctly because
the mapping between `""` and the data-plane value isn't known.

**Assessment:** The Kotlin `TypeTranslator` already supports explicit mappings
via the `TypeTranslation` proto — the infrastructure is in place. The blocker
is the **p4c backend (C++)**: it doesn't extract `@p4runtime_translation_mappings`
from P4 source and emit `TranslationEntry` protos in the IR. Only 1 use case
exists (VRF default `""` → `0`). The annotation is also non-standard (see
`metadata.p4` TODO). Deferred until DVaaS requires it.

### 3. ~~Constraint violation testing~~ ✅ Done

`SaiP4ConstraintTest` validates constraints on real SAI P4 tables:
- `acl_pre_ingress_table`: DSCP-without-IP-type rejected, IPv4+IPv6
  mutual exclusion rejected, DSCP-with-IPv4 accepted
- `ipv4_multicast_table`: unicast address rejected, multicast accepted
- `set_multicast_group_id` action: `group_id == 0` rejected
- `router_interface_table`: `src_mac == 0` rejected, nonzero accepted
- `disable_vlan_checks_table`: exact dummy_match rejected, wildcard accepted

### 4. PacketIO via P4Runtime StreamChannel (integration gap)

**Problem:** TypeTranslator supports `translatePacketOut()` and
`translatePacketIn()`, but the code path is never tested E2E. SAI P4 has
`packet_out_header.egress_port` (port_id_t, string-translated) and
`packet_in_header.ingress_port` / `target_egress_port`.

**Caveat:** LIMITATIONS.md notes that v1model p4c does not emit
`controller_packet_metadata` with `type_name`, so this may be blocked
upstream. Unit tests cover the translator logic.

### 5. Complex switching features (coverage gap)

SAI P4 middleblock includes tables for features we don't exercise in the E2E
test:
- Ingress/egress ACLs (punt, deny, redirect)
- Ingress cloning / mirroring
- WCMP (weighted cost multipath) — action selectors
- L3 admit / pre-ingress ACL
- Tunneling (GRE encap/decap)
- Multicast

These are tested generically via the STF corpus (clone, multicast, action
selectors all work), but never through SAI P4's specific table structure.
p4testgen (gap #1) covers many of these automatically.

## Strategy

1. **p4testgen first.** ✅ Done. 500 test vectors, all passing.
2. **Translation mappings.** Deferred — requires C++ p4c backend work,
   only 1 use case, non-standard annotation.
3. **Constraint violations.** ✅ Done. 10 tests across 4 tables.
4. **PacketIO.** May be blocked by p4c; defer unless DVaaS needs it.
5. **Complex features.** p4testgen covers most of this; add targeted hand-
   crafted tests only for gaps p4testgen can't reach.
