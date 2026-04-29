# P4Runtime Differential Testing

Cross-validates 4ward's P4Runtime gRPC server against BMv2's `simple_switch_grpc`
on a corpus of write/read scenarios. Spec rationale and full design in
[`designs/p4runtime_diff.md`](../../designs/p4runtime_diff.md).

## Status

**Partial.** The canonicalize-and-diff logic ([`ResponseDiff.kt`](ResponseDiff.kt))
is implemented and unit-tested. The end-to-end harness is **blocked** on
producing a Bazel-built `simple_switch_grpc` binary — see the design doc's
"Building `simple_switch_grpc` is the long pole" section. `rules_foreign_cc`
was the obvious first attempt and turned out to be a dead end (it doesn't
bundle autoconf/automake/libtool, and adding them as system deps breaks
4ward's hermetic-build property).

## What's here

- [`ResponseDiff.kt`](ResponseDiff.kt) — canonicalization helpers per design
  doc §"Canonicalizations before diff": match-list ordering, identity
  short-circuits when no canonicalization is needed, and `assertProtosEqual`
  with text-format diff output.
- [`ResponseDiffTest.kt`](ResponseDiffTest.kt) — unit tests on synthetic
  protos. Verifies the diff machinery is correct on day one of the harness
  landing, independent of any bmv2 build.

## What's deferred

When `simple_switch_grpc` is buildable, the harness adds:

- **`Bmv2P4RuntimeRunner`** — spawns `simple_switch_grpc` as a subprocess,
  exposes a `P4RuntimeBlockingStub`. Mirrors the lifecycle pattern in
  `e2e_tests/bmv2_diff/Bmv2Runner.kt` (port allocation, gRPC-ready polling,
  graceful shutdown). CLI invocation:
  `simple_switch_grpc --device-id N --thrift-port P1 -- --grpc-server-addr 0.0.0.0:P2`.
- **`FourwardP4RuntimeRunner`** — wraps an in-process 4ward `P4RuntimeServer`
  with the same gRPC stub surface, so the test code is symmetric across the
  two runners.
- **`P4RuntimeDiffTest`** — the five scenarios from the design doc, each as
  one `@Test` method:
  1. Round-trip canonical form (the §8.3 regression test, externally
     validated).
  2. Modify-after-padded-write (same logical key, different encodings).
  3. Out-of-range values (both must reject; status code may diverge).
  4. Batch atomicity under each `WriteRequest.Atomicity` mode.
  5. Default-action modify + read-back.

The deferred tests will be tagged `heavy` (subprocess-based) and skipped via
JUnit `Assume` when the binary isn't in runfiles, so the suite degrades
gracefully on hosts that don't have a Bazel-built `simple_switch_grpc`.

## Phase-1 build options for the future

Both have known costs documented in the design doc:

1. **Native Bazel rules for PI's `targets/bmv2/` + behavioral-model's
   `targets/simple_switch_grpc/`.** Multi-week port; version-skew triage
   on PI's stale absl pin; protobuf 26+ target rename patches.
2. **Vendor autoconf/automake/libtool as Bazel deps.** Experimental
   `rules_autotools` exists but isn't production-grade. Probably more work
   than option 1.

Tracking issue: [#595](https://github.com/smolkaj/4ward/issues/595).
