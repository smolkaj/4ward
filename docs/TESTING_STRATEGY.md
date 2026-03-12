# Testing Strategy

The README asks "[Should you trust AI-written code?](../README.md#should-you-trust-ai-written-code)"
and gives the short answer: trust the tests, not the author. This document
tells the full story.

## The key insight

If success criteria are machine-checkable, *who* writes the code doesn't
matter — only whether the tests are good. There's no ambiguity, no judgment
call on "is this done?" A test passes or it doesn't.

This makes the project automatable: AI agents run a tight loop — pick a
failing test, implement, green, ship. And it makes the output trustworthy:
three independent oracles agreeing is stronger evidence of correctness than
any code review.

## Three layers, three oracles

### Layer 1: STF corpus — breadth

p4c ships over 200 [STF](https://github.com/p4lang/p4c/tree/main/testdata)
(Simple Test Framework) test programs. Each is a self-contained spec: a P4
program, table entries, input packets, and expected output. The STF runner
compiles through p4c + the 4ward backend, loads the pipeline, sends packets,
and diffs actual output against expectations.

The source of truth is hand-written expectations by the language authors — a
direct statement of what correct behavior looks like. This catches regressions,
feature gaps, and basic correctness across the full breadth of P4₁₆. The blind
spot: only paths someone thought to write get tested.

The failing-test list *is* the feature backlog — pick one, make it pass, ship.

### Layer 2: p4testgen — depth

[p4testgen](https://github.com/p4lang/p4c/tree/main/backends/p4tools/modules/testgen)
symbolically executes P4 programs with an SMT solver, generating concrete test
cases that exercise each reachable path — including ones no human would think
to write.

The source of truth is p4testgen's own model of P4 and BMv2 semantics, an
independent implementation that shares no code with 4ward or BMv2. This catches
bugs on paths humans miss. The blind spot: p4testgen's model could disagree
with the real BMv2 implementation.

### Layer 3: BMv2 differential testing — correctness

Identical inputs go through BMv2 and 4ward. Every output packet, every drop
decision, every egress port is compared. If they disagree, one of them has a
bug.

The source of truth is the reference implementation itself — not a model, not
a spec. This catches the case that the other layers can't: 4ward produces
output, but the output is *wrong*. The blind spot: BMv2 has its own bugs, and
can't serve as oracle for features unique to 4ward (like trace trees).

## Unit tests

Underneath the three layers, unit tests provide fast development feedback —
bit-precise arithmetic, match kinds, select expressions, packet I/O. Not part
of the correctness strategy, but they catch problems early, before the
expensive end-to-end tests run.

## P4Runtime server

The layers above verify the data plane — does the simulator execute P4 programs
correctly? The P4Runtime server is a different surface: a gRPC control plane
that translates between P4Runtime-speaking controllers and the simulator.
Different surface, same philosophy: multiple independent methodologies,
machine-checkable success criteria.

### Layer 1: Conformance tests — spec compliance

Hand-written tests that walk the P4Runtime spec section by section. Each test
cites the spec requirement it validates. Three categories:

- **Per-RPC happy paths.** SetForwardingPipelineConfig, Write, Read,
  StreamChannel — does the basic lifecycle work?
- **Per-RPC error codes.** The P4Runtime spec (§9.1) prescribes specific gRPC
  status codes for each error condition: ALREADY_EXISTS for duplicate INSERT,
  NOT_FOUND for delete of nonexistent entry, FAILED_PRECONDITION for Write
  before pipeline load. Each condition gets a test that asserts the exact code.
  Modeled after
  [sonic-pins/p4rt_app/tests/response_path_test.cc](https://github.com/sonic-net/sonic-pins/tree/main/p4rt_app/tests)
  which systematically covers per-RPC error paths including batch partial
  failure, error message sanitization, and state consistency after failed
  writes.
- **Translation correctness.** `@p4runtime_translation` round-trips: write a
  value in SDN bitwidth, read it back, verify it matches. Covers both the
  narrowing (write) and widening (read) paths.

The source of truth is the P4Runtime spec itself. The blind spot: only covers
scenarios someone thought to write.

### Layer 2: Round-trip testing — simulator agreement

The simulator is already validated by three independent oracles. The P4Runtime
server is just a different front door to the same simulator. So: for programs
in the STF corpus that have p4info, load via P4Runtime
(`SetForwardingPipelineConfig`), install entries via `Write`, send packets via
`StreamChannel` PacketOut, and verify outputs match what the simulator produces
through the direct protocol.

This turns the existing 186-program corpus into P4Runtime integration tests
for free. It answers the question: does the P4Runtime layer faithfully
translate between the controller protocol and the simulator, or does it lose
or corrupt information along the way?

The source of truth is the simulator (already validated by three independent
oracles). The blind spot: can't catch bugs where both the P4Runtime layer and
the simulator agree on the wrong answer — but Layer 1 and Layer 3 can.

*Not implemented yet — requires p4info generation for corpus programs.
Methodology documented here so it can be built incrementally.*

### Layer 3: Fuzz testing — robustness

The data plane has p4testgen exploring paths no human would write. The control
plane equivalent is
[sonic-pins/p4_fuzzer](https://github.com/sonic-net/sonic-pins/tree/main/p4_fuzzer):
given a P4Info, it generates random valid and mutated P4Runtime WriteRequests
(invalid table IDs, missing match fields, duplicate inserts, deletes of
nonexistent entries, out-of-range values — 16 mutation types), sends them to
the server, and checks responses against a spec oracle that knows what the
P4Runtime spec says should happen.

The key insight is the oracle pattern: the fuzzer maintains a `SwitchState`
model that tracks what entries should be installed. After each Write, it checks:
did the server accept/reject correctly per spec? Does a Read back match the
modeled state? This catches crashes, state corruption, and spec violations that
hand-written tests miss.

The source of truth is the P4Runtime spec oracle (independent of our
implementation). The blind spot: doesn't test data plane correctness, only
control plane protocol compliance.

*Not implemented yet — requires integrating sonic-pins p4_fuzzer as a Bazel
dependency. Methodology documented here so it can be built in Track 4B+.*

### Compliance matrix

[P4RUNTIME_COMPLIANCE.md](P4RUNTIME_COMPLIANCE.md) maps every testable
P4Runtime spec requirement to its test status. This answers the first open
question below — and makes the remaining gaps visible at a glance.

### Confidence assessment

The [compliance matrix](P4RUNTIME_COMPLIANCE.md) shows 119/120 applicable
requirements tested — but the matrix is self-authored. We wrote the
requirements, wrote the tests, and checked our own boxes. Four blind spots:

1. **Spec coverage is hand-distilled.** The matrix was not systematically
   extracted from every MUST/SHALL in the P4Runtime spec. Entire requirement
   areas could be missing — e.g. bytestring canonicalization on reads (§9.1.2),
   ordering guarantees across batched updates (§12.4), role config interaction
   with arbitration (§15).

2. **Tests are shallow.** Many tests check the happy path plus one error case.
   The arbitration tests cover 7 scenarios, but the state machine has many more
   edge cases: same election_id on two streams, re-arbitration on the same
   stream, races between arbitration and writes, rapid connect/disconnect. Write
   validation tests cover one bad value per field type, not boundary cases.

3. **N/A judgments deserve scrutiny.** 10 requirements are marked N/A. Most are
   defensible (digests, idle timeouts, two-phase commit), but should be
   re-evaluated as scope grows — particularly DATAPLANE_ATOMIC (§12) and
   role-based access control (§15) if DVaaS compatibility requires them.

4. **No independent oracle.** The biggest gap. The data plane has BMv2
   differential testing (186 programs, bit-for-bit). The P4Runtime server has
   nothing equivalent — our tests encode our *interpretation* of the spec. If we
   misread a requirement, the test happily passes our wrong implementation.
   Layer 3 (p4-fuzzer) directly addresses this, but is not yet integrated.

| Area | Confidence | Why |
|------|-----------|-----|
| Write validation (fields, actions, params) | High | Thorough unit tests, multiple P4 schemas |
| Read/wildcard semantics | High | Well-covered by conformance tests |
| Pipeline config lifecycle | High | Good coverage of load/reload/clear |
| Translation (@p4runtime_translation) | High | Dedicated test suite + SAI P4 E2E |
| Arbitration state machine | Medium | Core cases covered, edge cases not |
| Error code compliance (exact gRPC status) | Medium | Tested but not independently verified |
| Batch/atomicity semantics | Medium | Simple scenarios only |
| Spec completeness (did we miss requirements?) | Low | Hand-distilled, no systematic extraction |

### Open questions

- What's the P4Runtime equivalent of p4testgen? The p4_fuzzer (Layer 3)
  explores the Write surface. Is there an analog for Read, StreamChannel, or
  pipeline config lifecycle?
- What's the equivalent of BMv2 diff testing? Run the same P4Runtime session
  against BMv2's gRPC server and 4ward, compare responses. This would catch
  compatibility bugs that spec-reading alone misses.
