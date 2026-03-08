# SAI P4 — Confidence Assessment

> Living document. Check off items as they're resolved. The goal is to
> systematically close the gap between "it compiles and forwards one packet"
> and "DVaaS-ready with high confidence."

## Current state

**Packet coverage: 1 packet.** The entire `SaiP4E2ETest` sends a single IPv4
packet through the L3 forwarding path (VRF → IPv4 → nexthop → RIF → neighbor).
Everything else is Write/Read round-trip testing with no packets.

## What works (tested E2E)

- [x] Compile real SAI P4 middleblock (from sonic-pins) via p4c-4ward
- [x] Load pipeline via `SetForwardingPipelineConfig`
- [x] P4Runtime Write with `@p4runtime_translation` (string IDs for vrf_id,
      nexthop_id, router_interface_id, port_id)
- [x] P4Runtime Read with SDN-to-dataplane translation round-trip
- [x] P4Runtime Delete
- [x] L3 IPv4 forwarding: MAC rewrites, TTL decrement (1 packet)
- [x] p4-constraints JNI integration (tested on constrained_table.p4, not SAI)
- [x] Write validation (action IDs, params, match fields, priority)

## Gaps — ordered by impact

### 1. Packet processing coverage (critical)

**Problem:** 1 packet is nowhere near enough confidence for DVaaS. SAI P4
middleblock has 27 tables spanning L2, L3, ACLs, mirroring, multicast,
tunneling, and packet I/O. We exercise exactly one forwarding path.

**Fix — p4testgen:** Run p4testgen on SAI P4 middleblock to systematically
generate test vectors via symbolic execution (Z3). This would cover all
reachable paths and catch simulator bugs we can't find with a single
hand-crafted packet.

**Caveat:** p4testgen generates STF files, so it tests the simulator directly
(not through P4Runtime translation). Still the highest-leverage way to improve
packet-processing confidence.

**Unknowns:**
- Can p4testgen handle a 27-table program? It may hit Z3 timeouts.
- How many tests will it generate? Could be hundreds or thousands.
- Does it need `--max-tests` to stay tractable?

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

### 3. Constraint violation testing (confidence gap)

**Problem:** `ConstraintValidator` works (tested on `constrained_table.p4`), but
we've never tested it on actual SAI P4 entries. SAI P4 middleblock has rich
constraints:
- `vrf_id != 0` (default VRF)
- IPv4 dst must be unicast
- `src_mac != 0`
- `multicast_group_id != 0`
- Conditional: `marked_to_mirror == 1 → mirror_egress_port::mask == -1`

**Fix:** Add tests that write violating entries to SAI P4 tables and verify
`INVALID_ARGUMENT` rejection with actionable error messages.

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
p4testgen (gap #1) would cover many of these.

## Strategy

1. **p4testgen first.** Highest leverage — one infrastructure investment
   gives us systematic packet-level coverage across all SAI P4 tables.
2. **Translation mappings.** Small, focused feature — needed for correct
   default VRF handling.
3. **Constraint violations.** Straightforward test additions once we pick
   representative SAI P4 constraints.
4. **PacketIO.** May be blocked by p4c; defer unless DVaaS needs it.
5. **Complex features.** p4testgen covers most of this; add targeted hand-
   crafted tests only for gaps p4testgen can't reach.
