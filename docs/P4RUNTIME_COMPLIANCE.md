# P4Runtime Compliance Matrix

Systematic mapping of [P4Runtime spec](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html)
requirements to test coverage. Living document — update when tests are added
or gaps are closed.

Legend: **Y** = tested, **N** = not tested, **N/A** = out of scope

## SetForwardingPipelineConfig (§7)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 7.1 | Load valid pipeline succeeds | Y | ConformanceTest #1 |
| 7.2 | Pipeline reload replaces previous | Y | ConformanceTest #2 |
| 7.3 | Empty/invalid config → INVALID_ARGUMENT | Y | ConformanceTest #3 |
| 7.4 | Invalid p4_device_config bytes → INVALID_ARGUMENT | Y | ConformanceTest #37 |
| 7.5 | Missing p4_device_config → INVALID_ARGUMENT | Y | ConformanceTest #38 |
| 7.6 | Requires primary controller when arbitration is active | Y | ConformanceTest #66 |
| 7.7 | VERIFY action validates without applying | N | |
| 7.8 | VERIFY_AND_COMMIT applies atomically | Y | ConformanceTest #1 |
| 7.9 | VERIFY_AND_SAVE / COMMIT / RECONCILE_AND_COMMIT | N/A | Two-phase commit not meaningful for a reference simulator |
| 7.10 | Cookie stored and returned | Y | ConformanceTest #59 |
| 7.11 | Pipeline reload clears table entries | Y | ConformanceTest #58 |
| 7.12 | Const table entries populated at load time | Y | ConformanceTest #65 |

## General encoding (§8)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 8.1 | Bytestrings must be exactly ceil(bitwidth/8) bytes | Y | WriteValidatorTest |
| 8.2 | Ternary value and mask must have equal byte width | Y | WriteValidatorTest |
| 8.3 | LPM value byte width matches field bitwidth | Y | WriteValidatorTest |
| 8.4 | Range low/high byte width matches field bitwidth | Y | WriteValidatorTest |
| 8.5 | Ternary: masked bits must be zero in value | Y | WriteValidatorTest |
| 8.6 | LPM: bits beyond prefix_len must be zero | Y | WriteValidatorTest |
| 8.7 | Read responses zero-pad bytestrings to ceil(bitwidth/8) | Y | ConformanceTest #63 |

## Write RPC — table entries (§9.1)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.1 | Write without pipeline → FAILED_PRECONDITION | Y | WriteErrorTest |
| 9.2 | INSERT succeeds for new entry | Y | ConformanceTest #4 |
| 9.3 | INSERT duplicate → ALREADY_EXISTS | Y | WriteErrorTest |
| 9.4 | MODIFY existing entry succeeds | Y | ConformanceTest #5 |
| 9.5 | MODIFY non-existent → NOT_FOUND | Y | WriteErrorTest |
| 9.6 | DELETE existing entry succeeds | Y | ConformanceTest #6 |
| 9.7 | DELETE non-existent → NOT_FOUND | Y | WriteErrorTest |
| 9.8 | Unknown table_id → NOT_FOUND | Y | WriteErrorTest, WriteValidatorTest |
| 9.9 | Entry lifecycle INSERT→MODIFY→DELETE→DELETE(fail) | Y | WriteErrorTest |
| 9.10 | Unknown action_id → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.11 | Action not in table's action_refs → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.12 | Wrong param count → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.13 | Unknown param_id → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.14 | Param byte width mismatch → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.15 | Unknown match field_id → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.16 | Wrong match kind (e.g. ternary on exact field) → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.17 | Match field byte width mismatch → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.18 | Missing required exact match field → INVALID_ARGUMENT | Y | WriteValidatorTest, WriteErrorTest |
| 9.19 | Duplicate match field ID → INVALID_ARGUMENT | Y | WriteValidatorTest |
| 9.20 | Priority > 0 required for ternary/range/optional tables | Y | WriteValidatorTest |
| 9.21 | Priority must be 0 for exact-only tables | Y | WriteValidatorTest, WriteErrorTest |
| 9.22 | DELETE skips content validation | Y | WriteValidatorTest |
| 9.23 | Zero-bitwidth params skipped (sdn_string) | Y | WriteValidatorTest |
| 9.24 | Constant table rejects INSERT/MODIFY/DELETE | Y | WriteValidatorTest |
| 9.25 | Default entry: match fields must be absent | Y | WriteValidatorTest |
| 9.26 | Default entry: MODIFY semantics | Y | WriteValidatorTest |
| 9.27 | RESOURCE_EXHAUSTED when table is full | Y | TableStoreTest, ConformanceTest |
| 9.28 | Write batch: updates applied in order | Y | ConformanceTest #39 |
| 9.29 | Direct counter/meter data in table entry reads | Y | ConformanceTest #68-69 |

## Write RPC — action profiles (§9.2)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.30 | Insert action profile member succeeds | Y | ConformanceTest #26 |
| 9.31 | Duplicate member → ALREADY_EXISTS | Y | ConformanceTest #27 |
| 9.32 | Delete member succeeds | Y | ConformanceTest #28 |
| 9.33 | Insert action profile group succeeds | Y | ConformanceTest #29 |
| 9.34 | Modify group with different members | Y | ConformanceTest #30 |
| 9.35 | Delete non-existent group → NOT_FOUND | Y | ConformanceTest #31 |
| 9.36 | One-shot action selector | Y | TableStoreTest, WriteValidatorTest |
| 9.37 | Group max_size enforcement | Y | TableStoreTest |

## Write RPC — registers (§9.7)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.40 | MODIFY register entry persists value | Y | TableStoreTest, ConformanceTest #32 |
| 9.41 | INSERT register → INVALID_ARGUMENT | Y | TableStoreTest, ConformanceTest #34 |
| 9.42 | DELETE register → INVALID_ARGUMENT | Y | TableStoreTest |
| 9.43 | Out-of-bounds index → error | Y | TableStoreTest |
| 9.44 | Unknown register_id → NOT_FOUND | Y | TableStoreTest |

## Write RPC — counters & meters (§9.3, §9.4)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.50 | Direct counter read/write | Y | TableStoreTest, ConformanceTest #52-54 |
| 9.51 | Indirect counter read/write | Y | TableStoreTest, ConformanceTest #46-48 |
| 9.52 | Direct meter config | Y | TableStoreTest, ConformanceTest #55-57 |
| 9.53 | Indirect meter config | Y | TableStoreTest, ConformanceTest #49-51 |

## Write RPC — PRE (§9.5)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.60 | Multicast group CRUD | Y | ConformanceTest (via PRE entries) |
| 9.61 | Clone session CRUD | Y | ConformanceTest (via PRE entries) |
| 9.62 | PRE INSERT existing → ALREADY_EXISTS | Y | TableStoreTest |
| 9.63 | PRE MODIFY non-existent → NOT_FOUND | Y | TableStoreTest |
| 9.64 | PRE DELETE non-existent → NOT_FOUND | Y | TableStoreTest |
| 9.65 | PRE entries readable via Read RPC | Y | TableStoreTest |

## Write RPC — other entity types (§9.6, §9.8, §9.9)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.70 | ValueSetEntry | N/A | No parser value set support in simulator |
| 9.71 | DigestEntry configuration | N/A | Digests out of scope (no real packet rates) |
| 9.72 | ExternEntry | N/A | No standard v1model extern entities |

## Write RPC — atomicity (§12)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.80 | CONTINUE_ON_ERROR: process all updates, per-update errors | Y | WriteErrorTest |
| 9.81 | ROLLBACK_ON_ERROR: undo on failure | Y | WriteErrorTest |
| 9.82 | DATAPLANE_ATOMIC | N/A | Not meaningful for a reference simulator |
| 9.83 | Per-update error reporting in WriteResponse | Y | WriteErrorTest |

## Write RPC — general (§12)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 9.90 | device_id validated on Write | Y | ConformanceTest #60 |
| 9.91 | Write allowed without prior arbitration | Y | ConformanceTest (implicit) |

## Arbitration (§5)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 10.1 | Arbitration establishes master | Y | ConformanceTest #12 |
| 10.2 | Higher election_id becomes primary | Y | ConformanceTest #40-41 |
| 10.3 | Non-primary writes → PERMISSION_DENIED | Y | ConformanceTest #42-44 |
| 10.4 | All controllers may read regardless of role | Y | ConformanceTest #45 |
| 10.5 | Demotion notification to displaced primary | N | |
| 10.6 | Automatic promotion on primary disconnect | N | |
| 10.7 | Zero election_id: backup semantics (cannot be primary) | N | |
| 10.8 | Role-based access control | N/A | Single default role sufficient for reference simulator |

## Read RPC (§11)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 11.1 | Read without pipeline → FAILED_PRECONDITION | Y | ConformanceTest #11 |
| 11.2 | Wildcard read returns all table entries | Y | ConformanceTest #9 |
| 11.3 | Read empty table returns empty | Y | ConformanceTest #10 |
| 11.4 | Read with table_id filter | Y | ConformanceTest #20 |
| 11.5 | Empty entity list → no results (§11.1) | Y | ConformanceTest #21 |
| 11.6 | Per-entry read with match key | Y | ConformanceTest #23-25 |
| 11.7 | Wildcard read for action profiles | Y | ConformanceTest #35 |
| 11.8 | Wildcard read for registers | Y | TableStoreTest |
| 11.9 | Read unwritten register returns zero | Y | ConformanceTest #33 |
| 11.10 | Default entry included in wildcard table reads | Y | ConformanceTest #64 |
| 11.11 | device_id validated on Read | Y | ConformanceTest #61 |

## GetForwardingPipelineConfig (§7)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 12.1 | Returns loaded p4info | Y | ConformanceTest #16 |
| 12.2 | Without pipeline → FAILED_PRECONDITION | Y | ConformanceTest #17 |
| 12.3 | P4INFO_AND_COOKIE omits device config | Y | ConformanceTest #18 |
| 12.4 | DEVICE_CONFIG_AND_COOKIE omits p4info | Y | ConformanceTest #22 |
| 12.5 | COOKIE_ONLY returns empty config | Y | ConformanceTest #36 |
| 12.6 | device_id validated | Y | ConformanceTest #62 |

## Capabilities (§17)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 13.1 | Returns API version | Y | ConformanceTest #19 |

## StreamChannel (§16)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 14.1 | PacketOut processed, PacketIn returned | Y | ConformanceTest #13 |
| 14.2 | PacketOut with table entries forwards correctly | Y | ConformanceTest #14 |
| 14.3 | Multiple packets preserve ordering | Y | ConformanceTest #15 |
| 14.4 | StreamError on invalid stream message | Y | ConformanceTest #67 |
| 14.5 | Digest delivery | N/A | Out of scope — no real packet rates to trigger digests |
| 14.6 | DigestListAck handling | N/A | Digests out of scope |
| 14.7 | Idle timeout notifications | N/A | Out of scope — no wall-clock time in a reference simulator |
| 14.8 | Architecture-specific `other` messages | N/A | No v1model stream extensions |

## @p4runtime_translation (§8.3)

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 15.1 | sdn_bitwidth narrowing on write | Y | TranslationTest |
| 15.2 | sdn_bitwidth widening on read | Y | TranslationTest |
| 15.3 | sdn_string encoding/decoding | Y | TranslationTest, SaiP4E2ETest |
| 15.4 | Non-translated fields pass through | Y | TranslationTest |
| 15.5 | End-to-end forwarding with translated ports | Y | TranslationTest |
| 15.6 | sdn_string write/read round-trip with SAI P4 | Y | SaiP4E2ETest |

## @refers_to

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 17.1 | Match field referential integrity on INSERT/MODIFY | Y | ReferenceValidatorTest |
| 17.2 | Action param referential integrity | Y | ReferenceValidatorTest |
| 17.3 | Action profile member referential integrity | Y | ReferenceValidatorTest |
| 17.4 | One-shot action set referential integrity | Y | ReferenceValidatorTest |
| 17.5 | builtin::multicast_group_table references | Y | ReferenceValidatorTest |
| 17.6 | DELETE bypasses referential checks | Y | ReferenceValidatorTest |

## p4-constraints

| # | Requirement | Status | Test |
|---|-------------|--------|------|
| 18.1 | @entry_restriction validated on Write | Y | ConstraintTest |
| 18.2 | Violation → INVALID_ARGUMENT with constraint text | Y | ConstraintTest |
| 18.3 | DELETE bypasses constraint validation | Y | ConstraintTest |
| 18.4 | Pipeline without constraints works normally | Y | ConstraintTest |

## Summary

| Category | Tested | Not tested | N/A |
|----------|--------|------------|-----|
| SetForwardingPipelineConfig | 9 | 0 | 1 |
| General encoding | 7 | 0 | 0 |
| Write — tables | 29 | 0 | 0 |
| Write — profiles | 8 | 0 | 0 |
| Write — registers | 5 | 0 | 0 |
| Write — counters/meters | 4 | 0 | 0 |
| Write — PRE | 6 | 0 | 0 |
| Write — other entities | 0 | 0 | 3 |
| Write — atomicity | 3 | 0 | 1 |
| Write — general | 2 | 0 | 0 |
| Arbitration | 4 | 3 | 1 |
| Read | 11 | 0 | 0 |
| GetForwardingPipelineConfig | 6 | 0 | 0 |
| Capabilities | 1 | 0 | 0 |
| StreamChannel | 4 | 0 | 4 |
| Translation | 6 | 0 | 0 |
| @refers_to | 6 | 0 | 0 |
| p4-constraints | 4 | 0 | 0 |
| **Total** | **115** | **3** | **10** |
