# P4Runtime Differential Testing Design

**Status: proposal**

Tracks issue #595.

## Goal

Cross-validate 4ward's P4Runtime gRPC server against BMv2's
`simple_switch_grpc` on a corpus of write/read scenarios, so that subtle
spec divergences (like the §8.3 bytestring encoding bug fixed in #594) get
caught by an external oracle rather than relying on our own reading of the
spec.

## What we already have

The `e2e_tests/bmv2_diff/` harness drives BMv2 and 4ward through equivalent
data-plane operations and compares output packets bit-for-bit. It uses
BMv2's C++ `simple_switch` library directly (`bmv2_driver.cpp` issues
`TABLE_ADD`, `PACKET`, etc., commands over stdin/stdout). The corpus is
~50 STF tests pulled from `@p4c//testdata/p4_16_samples`.

This validates that the **data plane** behaves the same way given the same
table state. It does not exercise the **P4Runtime control plane** — the
gRPC API itself is bypassed entirely in favor of BMv2's internal C++
plumbing.

## What's missing

The §8.3 bug lived in the gRPC adapter — in code BMv2 never sees and our
existing harness never touches. The same is likely true of other
P4Runtime-spec corners: status codes, batch atomicity, role config, idle
timeout, default-action semantics, action-profile group membership rules,
counter/meter direct-vs-indirect dispatch. Any of these could diverge
silently between 4ward and BMv2 today.

A control-plane differential harness would speak P4Runtime gRPC to both
servers and compare responses byte-by-byte (or with documented
canonicalizations).

## Design

### Topology

```
                 ┌──────────────────────────────┐
                 │   P4Runtime test scenario     │
                 │   (Write/Read sequences)      │
                 └────────────┬─────────────────┘
                              │ same gRPC requests
              ┌───────────────┴───────────────┐
              ▼                               ▼
   ┌──────────────────────┐       ┌──────────────────────┐
   │   4ward p4runtime    │       │  BMv2                │
   │   server subprocess  │       │  simple_switch_grpc  │
   └──────────┬───────────┘       └──────────┬───────────┘
              │ WriteResponse,               │ WriteResponse,
              │ ReadResponse                 │ ReadResponse
              └───────────────┬──────────────┘
                              ▼
                       diff (with allowed
                       canonicalizations)
                              │
                              ▼
                          pass/fail
```

Both servers are spawned as subprocesses, each listening on a separate
gRPC port. The test harness is a Kotlin gRPC client that issues identical
requests to both and diffs responses.

### Building `simple_switch_grpc`

The current BMv2 Bazel patch builds `simple_switch_lib` and the
`behavioral_model` binary, but not `simple_switch_grpc`. That binary
depends on `p4lang/PI`, which is a separate repo and a non-trivial Bazel
port (PI itself depends on protobuf, gRPC, and several p4lang sub-deps).

Two paths to consider, in order of preference:

1. **Patch the existing `behavioral_model` Bazel module to also build
   `simple_switch_grpc`** by pulling in `p4lang/PI` as a `git_override`'d
   `bazel_dep`. Highest-fidelity, hermetic. The cost is writing Bazel
   rules for PI — likely the largest single chunk of work in this
   project, comparable in scope to the existing behavioral_model patch.

2. **Use a prebuilt `simple_switch_grpc` binary fetched as an
   `http_file`.** Lower hermeticity, faster to land, but introduces a
   binary dependency that's hard to keep in sync with the rest of the
   BMv2 dep. Only useful as a stopgap.

Recommend (1). It's the right architectural choice, and it sets up future
work (P4Runtime extern-related testing, gNMI, etc.) on the same footing.

### Test scenarios

Start small. The corpus is **not** the existing STF tests — those are
data-plane oriented. The corpus is a new set of Kotlin-defined sequences,
each describing:

- A pipeline config (P4 program + P4Info).
- A sequence of P4Runtime requests: `SetForwardingPipelineConfig`,
  `Write` (insert/modify/delete), `Read`, `StreamChannel` events.
- For each request, the expected outcome class: success, specific gRPC
  error code, etc. — used as a sanity check before the diff fires.

Initial scenarios:

- **Round-trip canonical form.** Write a TableEntry with various
  encodings; Read it back; both servers must return the same canonical
  form. (This is the §8.3 regression test, externally validated.)
- **Modify-after-padded-write.** Write with padded encoding; Modify with
  canonical encoding; both servers must treat them as the same key.
- **Out-of-range values.** Write with a value exceeding the field
  bitwidth; both servers must return `OUT_OF_RANGE` (or
  `INVALID_ARGUMENT` — note documented divergence).
- **Batch atomicity.** A `WriteRequest` with one valid + one invalid
  update under each `Atomicity` mode.
- **Default action semantics.** Modify default; read; verify both
  servers' read-back behavior.

Each scenario is one Kotlin test. Failures point at the divergent fields
in the responses.

### Allowed divergences

Some differences are non-bugs and need to be canonicalized away before
diffing:

- **Field ordering** in repeated fields (e.g. `match` lists) — sort by
  `field_id` before compare.
- **Error message text** — compare error code only, not description.
- **Counter/meter timing values** — compare presence/structure, not
  values, or use a tolerance. Out of scope for the first cut; only
  exercise scenarios that don't depend on these.
- **Server-assigned IDs** (action profile member handles, multicast
  group node handles) — record and substitute, don't compare directly.

A sealed `DiffStrategy` per response type makes these choices explicit,
and a divergence in a strategy that wasn't anticipated counts as a test
failure.

## Phasing

1. **Phase 1: BMv2 PI Bazel port.** Extend the BMv2 patch to build
   `simple_switch_grpc`. No 4ward code changes; gate completion on
   spawning the binary from a Bazel test and seeing it accept a
   gRPC connection.
2. **Phase 2: Harness skeleton.** A `Bmv2P4RuntimeRunner` that wraps the
   BMv2 subprocess. A test that spawns both servers, sets a pipeline
   config on both, performs a single Write+Read, and asserts the
   responses match. One scenario, end-to-end.
3. **Phase 3: Initial scenario corpus.** Add the five scenarios above.
   Document divergences found. Some are 4ward bugs (file as issues with
   reproducers); some are BMv2 bugs (file upstream); some are
   spec-ambiguous (raise with the P4 API working group).
4. **Phase 4: Corpus growth.** Iterate. Add scenarios as P4Runtime
   features land in 4ward.

Phase 1 is a real chunk of Bazel work and could be its own PR. Phases
2 and 3 should land together — a harness with no scenarios isn't useful.

## Non-goals

- **Replacing the existing `bmv2_diff` data-plane harness.** Different
  layer, different oracle, different failure modes. Both stay.
- **Behavioral parity with BMv2 across the full P4Runtime API.** BMv2
  has features 4ward doesn't (and vice versa); the corpus targets the
  intersection. Documented divergences are an output, not a defect.
- **Continuous coverage of every P4Runtime field.** This is a
  spec-conformance harness, not a fuzzer. Targeted scenarios beat broad
  coverage when the goal is catching subtle spec divergences.

## Risks

- **PI's Bazel port is the long pole.** If it turns out PI's transitive
  dependencies are incompatible with our Bazel config (e.g. protobuf
  version skew), Phase 1 stalls. Pre-flight by attempting a minimal
  `cc_library(name = "PI", srcs = ...)` build before committing to the
  full design.
- **Test flakiness from subprocess management.** Two servers per test
  scenario with port allocation, startup races, and graceful shutdown
  is more failure surface than the existing single-process harness.
  Reuse `e2e_tests/bmv2_diff/Bmv2Runner.kt`'s patterns where they
  apply.
- **Divergences could be overwhelming.** If the first run finds 30
  divergences, triage becomes a project of its own. Mitigation: start
  with high-confidence scenarios (the §8.3 fix is one), expand from
  there.

## Alternatives considered

- **STF-as-control-plane.** Keep extending the existing harness with
  more STF tests. Doesn't help — STF doesn't exercise the gRPC layer.
- **Drive 4ward's P4Runtime server, drive BMv2 via its existing C++
  driver, diff the resulting data-plane behavior.** Backwards: tests
  the data plane through different control-plane paths. The bug we'd
  catch is "did the control plane translate to the same data-plane
  state?" — useful, but doesn't catch gRPC-API-only bugs (status codes,
  encoding rules, etc.).
- **p4lang's existing P4Runtime conformance test suite.** Worth
  checking — but the suites I know about (PTF, p4runtime-shell tests)
  are written against specific testbeds, not as a library you can
  point at two implementations and diff. May change if a more general
  suite has emerged.
