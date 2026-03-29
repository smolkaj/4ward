# 4ward — Roadmap

This document describes the next phase of development. For the original
roadmap that took 4ward from zero to feature-complete, see
[ROADMAP_V1.md](ROADMAP_V1.md).

## Where we are

4ward is a feature-complete P4 simulator with three architecture backends
(v1model, PSA, PNA), a near-complete P4Runtime server, structured trace
trees, a web playground, and competitive performance. All of this was
built in under a month.

But it has no users. Every status update says "all building blocks are in
place" for DVaaS integration — yet there's no PR to sonic-pins, no proof
it works as a drop-in, no external consumer exercising the code in anger.
The V1 roadmap achieved technical parity with BMv2. The V2 roadmap is
about proving it in the real world.

## North star

> **4ward is the P4 platform: the reference simulator, the reference
> P4Runtime server, and the best way to understand what a P4 program
> does.**

Three goals, roughly in priority order:

1. **Ship it.** DVaaS integration, BCR publication, real users.
2. **Own P4Runtime.** External validation, community positioning as
   *the* P4Runtime reference server.
3. **Own P4 education.** A hosted playground where anyone can learn P4
   without installing anything.

## Tracks

### Track 12: DVaaS integration

**Priority: top | The whole point**

Stop building and start shipping. DVaaS (Dataplane Validation as a
Service) in [sonic-pins](https://github.com/sonic-net/sonic-pins/tree/main/dvaas)
is the forcing function that justified this project. It's time to close
the loop.

#### Phase 1: drop-in replacement

Make 4ward work as a BMv2 replacement in DVaaS's existing test
infrastructure. This means:

- Wire 4ward into DVaaS's `ReferenceSimulator` interface (or its
  equivalent). Identify what the interface expects and implement it.
- Run DVaaS's existing SAI P4 test vectors through 4ward. Every test
  that passes on BMv2 must pass on 4ward.
- Document every gap that surfaces — these are the real integration
  requirements, not the ones we imagined.

**Done when:** DVaaS's SAI P4 test suite passes with 4ward as the
reference simulator, and a PR to sonic-pins is open.

#### Phase 2: trace tree integration

Once the drop-in works, demonstrate the upgrade: feed trace trees back
to DVaaS so it can report *why* a test failed, not just *that* it
failed. This is the unique value proposition — the reason to switch
from BMv2.

**Done when:** DVaaS test failures include structured trace diffs
showing where the switch and simulator diverged.

### Track 13: BCR publication

**Priority: high | Unblocks external adoption**

Publish 4ward to the [Bazel Central Registry](https://registry.bazel.build/)
so anyone can depend on it with a single `bazel_dep` line. Today the
only way to use 4ward is to clone the repo.

Blockers:

- **p4c fork.** The `smolkaj/p4c` fork adds `//p4include` exports, a
  testdata package, and a macOS build fix. Upstream PR:
  https://github.com/p4lang/p4c/pull/5533. Until merged, 4ward can't
  use a released p4c version.
- **behavioral_model Bazel patch.** BMv2 uses Autotools; our patch adds
  native Bazel rules. This is a dev dependency only — it doesn't block
  BCR publication of the core library, but it blocks the diff testing
  infrastructure.

**Done when:** `bazel_dep(name = "4ward", version = "0.1.0")` works
in a fresh project.

### Track 14: P4Runtime reference server

**Priority: high | Differentiator**

4ward is arguably the most spec-compliant P4Runtime implementation in
existence. Lean into that claim by proving it with external validation.

#### Phase 1: external test suites

Run existing P4Runtime test suites against 4ward:

- **sonic-pins P4Runtime tests.** These are the tests DVaaS already
  runs against BMv2. If 4ward passes them, that's independent
  confirmation of compliance.
- **Stratum / ONOS test suites.** If available and applicable.
- **p4lang/PI tests.** The reference P4Runtime implementation's own
  tests.

Each external test that passes is worth more than three we wrote
ourselves — it validates our *interpretation* of the spec, not just
our *implementation* of it.

#### Phase 2: fuzz testing

Integrate [p4_fuzzer](https://github.com/sonic-net/sonic-pins/tree/main/p4_fuzzer)
or build a Kotlin property-based fuzzer. Feed random valid and mutated
`WriteRequest` protos to the server. The server must never crash — only
return well-formed errors.

This closes the biggest remaining confidence gap in the P4Runtime
server: robustness against inputs no reasonable controller would send.

#### Phase 3: round-trip testing

Generate p4info for the 186 corpus programs. Load each via P4Runtime's
`SetForwardingPipelineConfig`, install table entries via `Write`,
inject packets via `StreamChannel`, and compare outputs to the direct
simulator path. This turns the existing corpus into 186 P4Runtime
integration tests for free.

**Done when:** at least one external test suite passes, the fuzzer runs
in CI without crashes, and the round-trip test harness covers the full
corpus.

### Track 15: hosted playground

**Priority: medium | Drives adoption**

A publicly accessible instance of the web playground — the "Go
Playground" of P4. Write a P4 program, inject packets, see trace
trees, share a permalink. No installation, no Bazel, no barrier.

This is the fastest path to community adoption. Every P4 tutorial, Stack
Overflow answer, and conference talk could link to a live example.

Scope:

- **Hosting.** Cloud Run, Fly.io, or similar. The playground server is
  already a single binary.
- **Sandboxing.** p4c compilation is the attack surface. Sandbox the
  compiler subprocess (seccomp, nsjail, or container isolation).
  Compilation timeout to prevent resource exhaustion.
- **Permalinks.** Shareable URLs that encode the P4 program, table
  entries, and packet inputs. Like the Go Playground's share feature.
- **Rate limiting.** Prevent abuse without blocking legitimate use.

**Done when:** `4ward.dev` (or equivalent) serves the playground and
anyone can share a P4 program via permalink.

### Track 16: polish and harden

**Priority: ongoing | Protects what we've built**

#### Performance regression CI

Wire the benchmark (JSON output, `./tools/dev.sh benchmark`) into CI
to track packets/sec over time. Alert on regressions. The 127× gains
are hard-won — protect them.

#### Concurrency testing

The global `Mutex` serializes all operations. Stress-test it:
concurrent writes from multiple controllers, reads during pipeline
reload, packet injection during table updates. DVaaS will exercise
this in production — find the bugs first.

#### Architecture helper extraction

~350 lines duplicated between `PSAArchitecture.kt` and
`PNAArchitecture.kt` (per [REFACTORING.md](REFACTORING.md)). Extract
shared helpers before the duplication becomes load-bearing.

#### Remaining error quality work

The golden error test suite covers 100% of P4Runtime error paths.
Remaining work: simulator exceptions that surface as opaque gRPC
`UNKNOWN` — catch at the service layer and translate to proper status
codes with actionable messages.

## Sequencing

```
                     now                      next              later
              ┌─────────────────────┐  ┌──────────────┐  ┌──────────────┐
  Track 12    │ DVaaS drop-in       │  │ trace tree   │  │              │
  (DVaaS)     │                     │  │ integration  │  │              │
              │                     │  │              │  │              │
  Track 13    │ BCR publication     │  │              │  │              │
  (BCR)       │                     │  │              │  │              │
              │                     │  │              │  │              │
  Track 14    │ external test       │  │ fuzz +       │  │ round-trip   │
  (P4Runtime) │ suites              │  │ testing      │  │ corpus       │
              │                     │  │              │  │              │
  Track 15    │                     │  │ hosted       │  │ permalinks   │
  (playground)│                     │  │ playground   │  │              │
              │                     │  │              │  │              │
  Track 16    │ · · · · · · · · · · ongoing · · · · · · · · · · · · · │
  (polish)    │                     │  │              │  │              │
              └─────────────────────┘  └──────────────┘  └──────────────┘
```

**Key dependencies:**

- Track 12 (DVaaS) has no technical blockers — everything is built.
  It's purely integration work.
- Track 13 (BCR) is blocked on the upstream p4c PR.
- Track 14 (P4Runtime) can start immediately — external test suites
  are independent of DVaaS.
- Track 15 (playground) depends on nothing but is lower priority than
  proving the core product works (Tracks 12–14).
- Track 16 (polish) is continuous background work.

## What's out of scope (for now)

- **TNA (Tofino Native Architecture).** Commercially important but
  has proprietary aspects. Revisit if there's demand.
- **Multi-device simulation.** Transformative but a major lift.
  Revisit after DVaaS integration proves the single-device story.
- **Formal verification.** Trace trees are structured enough to feed
  into model checkers, but this is research territory. Revisit when
  there's a concrete use case.
- **Static analysis / P4 linting.** Valuable but orthogonal to the
  simulator mission. Could be a separate project built on the same IR.
