# P4Runtime Differential Testing Design

**Status: proposal.** Tracks issue #595; realizes the open question in
[`docs/TESTING_STRATEGY.md`](../docs/TESTING_STRATEGY.md) §"Open
questions" ("What's the equivalent of BMv2 diff testing?") and the
external-validation goal in [`docs/ROADMAP.md`](../docs/ROADMAP.md)
Track 9.

## Proposal

Drive 4ward's P4Runtime gRPC server and BMv2's `simple_switch_grpc` with
the same gRPC requests, diff the responses with a small set of documented
canonicalizations.

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
```

## Why

The §8.3 bytestring fix in #594 lived in the gRPC adapter — code BMv2's
C++ API never reaches and our existing differential harness
(`e2e_tests/bmv2_diff/`, 187 STF tests) never touches. Candidate
corners that could be similarly affected include status codes, batch
atomicity, role config, idle timeout, default-action semantics, and
action-profile group membership rules — none verified, hence the
proposal. Today, 4ward's tests encode our *reading* of the spec; a
control-plane differential harness uses an external implementation as
oracle.

## Design

### Building `simple_switch_grpc` is the long pole

`bazel/behavioral_model.patch` builds `simple_switch_lib` and its
transitive deps but explicitly omits PI ("Minimal build: no Thrift, no
nanomsg, no debugger, no PI"). The `simple_switch_grpc` binary requires
both [`p4lang/PI`](https://github.com/p4lang/PI) and the
`targets/simple_switch_grpc/` directory of `behavioral-model` itself —
the binary lives in behavioral-model, links against PI's `libpi_bmv2`
adapter.

The realistic split:

- **Upstream PI**: has WORKSPACE-style Bazel rules for its C frontend
  (`pihdrs`, `piutils`, `pip4info`, `pi`, `pifegeneric`) but **none**
  for the C++ bmv2 adapter (`targets/bmv2/`, ~15 `.cpp` files
  producing `libpi_bmv2`) — that's autotools-only.
- **PI's deps.bzl** pins absl 2022-06-23 LTS, which is incompatible
  with our absl 20260107.1 / protobuf 33.5 / grpc 1.80.0 (target
  renames around protobuf 26 break PI's existing `cc_library`
  references).
- **Behavioral-model's `targets/simple_switch_grpc/`** is autotools
  only; our existing patch covers `simple_switch_lib` only (the
  data-plane variant) and explicitly disables `--with-pi`.

[`rules_foreign_cc`](https://github.com/bazel-contrib/rules_foreign_cc)
was the obvious first attempt: wrap PI and `simple_switch_grpc` as
`configure_make` targets, defer dep resolution to autotools. **It
doesn't work.** `rules_foreign_cc` does not bundle autoconf, automake,
or libtool — it expects them on the host PATH. Adding them as a
system dependency contradicts 4ward's "fully hermetic build, no
system packages" property — exactly the property that makes 4ward
attractive as a BMv2 replacement in DVaaS (designs/dvaas_integration.md).

Two paths remain, both real work:

1. **Native Bazel rules for PI and behavioral-model's
   `simple_switch_grpc`.** Multi-week port — version-skew triage on
   PI's stale absl pin, ~15 `cc_library`s for the bmv2 adapter,
   another ~10 for `simple_switch_grpc/` itself, plus protobuf 26+
   target rename patches.
2. **Vendor autotools as Bazel deps.** `autoconf` and `automake` are
   themselves shell + m4 — notoriously hard to bazelify. Some
   third-party `rules_autotools` exists but is experimental.
   Probably not less work than option 1.

**This is the dominant risk and currently a blocker** (see Risks).
The harness, runner, and scenarios in this PR are written against an
abstract `Bmv2P4RuntimeRunner` interface so they're ready to wire up
once a working `simple_switch_grpc` build lands; the tests are
tagged `bmv2-grpc-blocked` and skipped until then.

### Scenarios

The corpus is a new set of Kotlin-defined sequences, not the existing
STF corpus (which is data-plane oriented). Each scenario describes:

- A pipeline config (P4 program + P4Info).
- A sequence of P4Runtime requests.
- For each request, the expected outcome class — used as a sanity
  check before the diff fires.

Initial scenarios:

- **Round-trip canonical form.** Write a TableEntry with various
  encodings; Read it back; both servers must return the same canonical
  form. (The §8.3 regression test, externally validated.)
- **Modify-after-padded-write.** Write with padded encoding; Modify
  with canonical encoding; both servers must treat them as the same
  key.
- **Out-of-range values.** Write with a value exceeding the field
  bitwidth; both must reject. 4ward returns `OUT_OF_RANGE` per spec;
  BMv2's actual code is to be measured during Phase 2 and recorded as
  either an expected match or a documented divergence.
- **Batch atomicity.** A `WriteRequest` with one valid + one invalid
  update under each `Atomicity` mode.
- **Default action semantics.** Modify default; read; verify both
  servers' read-back behavior.

Five scenarios is the Phase 3 target. Phase 4 grows to ~20, prioritised
by recent P4Runtime feature work.

### Canonicalizations before diff

Some differences are non-bugs and must be canonicalized away before
diffing:

- **Field ordering** in repeated fields (`match` lists) — sort by
  `field_id`.
- **Error message text** — compare error code only, not description.
- **Server-assigned IDs** (action profile member handles, multicast
  group node handles) — record and substitute.
- **Counter/meter timing values** — out of scope for the first cut;
  initial scenarios avoid these.

A sealed `DiffStrategy` per response type makes these choices explicit;
divergence in a strategy that wasn't anticipated counts as a test
failure.

### Reuse of existing infrastructure

- `e2e_tests/bmv2_diff/Bmv2Runner.kt` already manages a BMv2 subprocess
  with port allocation, startup readiness checks, and graceful
  shutdown. Phase 2's `Bmv2P4RuntimeRunner` should mirror that
  lifecycle directly — same patterns, different transport.
- 4ward already exposes a `p4runtime_server` Kotlin binary
  (`p4runtime/BUILD.bazel`); the harness spawns it as a subprocess
  the same way DVaaS integration (designs/dvaas_integration.md) does.
- The gRPC client is the standard P4Runtime Kotlin stub already used
  by `P4RuntimeConformanceTest`.

## Phasing

The harness, runner, and initial scenarios ship as a single PR. The
PI Bazel port is the dominant cost but provides no observable value
on its own, and a harness with no scenarios isn't useful — bundling
keeps each component honest about its end-to-end role and lets
reviewers see the full picture before any of it merges.

1. **PI Bazel port.** Pull `p4lang/PI` in as a `git_override`'d
   `bazel_dep`, reusing its upstream WORKSPACE Bazel rules; add the
   missing BUILD files for `targets/bmv2/` and `bin/`; resolve
   version skew with our protobuf/gRPC. No 4ward code changes.
2. **Harness skeleton.** A `Bmv2P4RuntimeRunner`. A test that spawns
   both servers, sets a pipeline config on each, performs one
   Write+Read, asserts responses match.
3. **Initial scenario corpus.** The five scenarios above. Triage
   divergences: file 4ward bugs as issues, file BMv2 bugs upstream,
   raise spec ambiguities with the P4 API working group.
4. **Corpus growth.** Add scenarios as P4Runtime features land
   (post-merge follow-ups, not part of this PR).

## Non-goals

- **Replacing the existing `bmv2_diff` data-plane harness.** Different
  layer, different oracle, different failure modes. Both stay.
- **Behavioral parity with BMv2 across the full P4Runtime API.** BMv2
  has features 4ward doesn't (and vice versa). The corpus targets the
  intersection. Documented divergences are an output, not a defect.
- **Continuous coverage of every P4Runtime field.** This is a
  spec-conformance harness, not a fuzzer.

## Risks

- **PI / behavioral-model autotools build is the long pole.** Using
  `rules_foreign_cc` reduces our scope to "wire up the autotools
  build" rather than "port to native Bazel," but it depends on
  Thrift, gRPC, protobuf, and several p4lang sub-deps being
  resolvable from the host. macOS in particular is fragile here.
  Pre-flight by attempting a minimal `configure_make` build of PI's
  C frontend before committing to wiring `simple_switch_grpc`.
- **Subprocess management flakiness.** Two servers per scenario with
  port allocation, startup races, and graceful shutdown is more
  failure surface than the existing single-process harness. Reusing
  `Bmv2Runner.kt`'s lifecycle is mitigation.
- **Divergence triage.** Baseline rate is unknown until Phase 3 runs.
  Mitigation: scenarios are ordered by confidence, starting with the
  §8.3 regression test where 4ward and the spec demonstrably agree —
  any divergence there is a BMv2 bug, not ours. If volume turns out
  to be high, scenarios get a `divergence-classified` tag and fail
  only on unclassified differences.
- **CI cost.** Two gRPC servers per scenario doesn't fit the default
  test budget; tag the suite `heavy` and skip locally, run on CI.
  Only two `heavy` targets exist today (`bmv2_diff_test` and the
  `P4RuntimeWriteErrorTest` heavy variant), so adding a peer suite
  is well within the existing pattern.

## Alternatives considered

- **STF-as-control-plane.** Extending the existing harness with more
  STF tests doesn't help — STF doesn't exercise the gRPC layer.
- **Diff data-plane behavior across two control-plane paths.** Drive
  4ward via gRPC and BMv2 via its existing C++ driver, then compare
  output packets. Catches "did the control plane translate to the
  same data-plane state?" but not gRPC-API-only bugs (status codes,
  encoding rules).
- **Existing P4Runtime conformance suites.**
  [`p4lang/PI`'s PTF tests](https://github.com/p4lang/PI),
  [`p4runtime-shell`](https://github.com/p4lang/p4runtime-shell), and
  [`fabric-p4test`](https://github.com/opennetworkinglab/fabric-p4test)
  are written against specific testbeds, not as libraries you point
  at two implementations and diff. None replace the proposed harness;
  individual test cases from them may be portable into the corpus.
- **Stratum's PI consumption.** Stratum already pins PI as a Bazel
  dep but uses it as a *backend*, not for differential testing. Their
  Bazel wiring is reusable as reference; their use case is not.
