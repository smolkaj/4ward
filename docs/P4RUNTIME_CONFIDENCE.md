# P4Runtime Server — Confidence Assessment

> Living document. Honest evaluation of where our P4Runtime testing is strong
> and where it has blind spots.

## The problem

The [compliance matrix](P4RUNTIME_COMPLIANCE.md) shows 119/120 applicable
requirements tested — but the matrix is **self-authored**. We wrote the
requirements list, wrote the tests, and checked our own boxes. That's several
layers of potential blind spots.

## Blind spots

### 1. Spec coverage is hand-distilled

The compliance matrix was distilled by hand, not systematically extracted from
every MUST/SHALL/SHOULD in the
[P4Runtime spec](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html).
Entire requirement areas could be missing. For example, the spec has detailed
rules about:

- Bytestring canonicalization on reads (§9.1.2)
- Ordering guarantees across batched updates (§12.4)
- Error reporting semantics for partial failures (§12.1)
- Role config interaction with arbitration (§15)

Some of these are tested, but we have no systematic way to verify we haven't
missed others.

### 2. Tests are shallow

Many tests check the happy path plus one error case. The arbitration tests
are a good example: we cover 7 scenarios, but the state machine has many
more edge cases:

- Same election_id on two different streams
- Re-arbitration with a different ID on the same stream
- Races between arbitration updates and concurrent writes
- Rapid connect/disconnect cycles
- Stream errors during arbitration

Similar shallow spots exist in Write validation (we test one bad value per
field type, not boundary cases) and Read (we test wildcard and per-entry
reads, but not malformed read requests).

### 3. N/A judgments deserve scrutiny

We marked 10 requirements N/A. Most are defensible (digests, idle timeouts,
two-phase commit — genuinely not meaningful for a reference simulator), but
they should be periodically re-evaluated as the project's scope grows. In
particular:

- **DATAPLANE_ATOMIC** (§12) — could become relevant if we add transaction
  semantics
- **Role-based access control** (§15) — may be needed for DVaaS
  compatibility

### 4. No independent oracle

This is the biggest gap. For the **data plane**, we have BMv2 differential
testing: 186 programs run through both 4ward and BMv2, outputs compared
bit-for-bit. Any disagreement is a bug. This gives high confidence because
the oracle is completely independent.

For the **P4Runtime server**, we have no equivalent. Our tests encode our
*interpretation* of the spec. If we misread a requirement, the test happily
passes our wrong implementation.

## Potential mitigations

### p4-fuzzer (from sonic-net/sonic-pins)

The [SwitchV paper](https://dl.acm.org/doi/10.1145/3544216.3544tried) (SIGCOMM
2022) describes `p4-fuzzer`, a tool that:

1. Generates random but structurally valid P4Runtime `WriteRequest` updates
   driven by a P4Info schema
2. Applies deliberate **mutations** (invalid table IDs, missing match fields,
   duplicate inserts, etc.) to test error handling
3. Uses an **oracle** (`UpdateOracle` in `oracle_util.cc`) that independently
   tracks expected switch state and validates whether responses comply with
   the P4Runtime spec

The oracle was written by a different team (Google/SONiC) for testing
production switches. Running it against 4ward would give us the independent
verification we lack. It lives in
[`sonic-net/sonic-pins/p4_fuzzer/`](https://github.com/sonic-net/sonic-pins/tree/main/p4_fuzzer).

**Integration options:**

| Option | Effort | Independence |
|--------|--------|-------------|
| Run sonic-pins' p4-fuzzer binary against our gRPC server | Medium | High — their oracle, our server |
| Port oracle logic to Kotlin | High | Low — we'd write the oracle ourselves |
| Build our own fuzzer inspired by their approach | Medium | Low — same concern as above |

Running the existing C++ fuzzer against our server (option 1) is the clear
winner — it directly addresses blind spot #4 without introducing new
self-testing bias.

### Systematic spec audit

A line-by-line read of the P4Runtime spec, extracting every MUST/SHALL into
the compliance matrix, would address blind spot #1. This is tedious but
straightforward.

### Edge-case test expansion

Targeted test additions for the shallow areas identified in blind spot #2.
Priority areas:

- Arbitration edge cases (concurrent streams, re-arbitration, rapid
  reconnect)
- Write validation boundary cases (max/min values, zero-length bytestrings)
- Malformed Read requests
- Batch write ordering under partial failure

## Current confidence level

| Area | Confidence | Why |
|------|-----------|-----|
| Write validation (field types, actions, params) | High | Thorough unit tests, multiple P4 schemas |
| Read/wildcard semantics | High | Well-covered by conformance tests |
| Pipeline config lifecycle | High | Good coverage of load/reload/clear |
| Translation (@p4runtime_translation) | High | Dedicated test suite + SAI P4 E2E |
| Arbitration state machine | Medium | Core cases covered, edge cases untested |
| Error code compliance (exact gRPC status) | Medium | Tested but not independently verified |
| Batch/atomicity semantics | Medium | CONTINUE_ON_ERROR and ROLLBACK tested, but only simple scenarios |
| Spec completeness (did we miss requirements?) | Low | Hand-distilled, no systematic extraction |
| Overall spec compliance | Medium | No independent oracle to validate our interpretation |
