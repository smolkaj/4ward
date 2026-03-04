# 4ward — Roadmap

This document describes the main development tracks, their priorities, and how
they can be parallelized. See [STATUS.md](STATUS.md) for daily progress and
[ARCHITECTURE.md](ARCHITECTURE.md) for design details.

## North star: replace BMv2 in DVaaS

[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas) (Dataplane
Validation as a Service) validates switch behavior by sending synthesized
packets to both a switch under test and a reference P4 simulator, then
comparing outputs. Today that reference simulator is BMv2.

4ward should replace BMv2 in DVaaS. This is compelling because:

- **Spec compliance without workarounds.** DVaaS currently needs a
  "BMv2-compatible config" that papers over BMv2's missing features (e.g.
  `@p4runtime_translation`). 4ward aims to be spec-compliant out of the box.
- **Trace trees eliminate false positives.** DVaaS flags mismatches when the
  switch's action selector hash picks a different group member than BMv2's.
  With trace trees, 4ward can report all valid outputs — a switch result that
  matches *any* valid path is correct, not a false failure.
- **Richer traces.** DVaaS already augments test vectors with packet traces.
  4ward's structured proto traces are more powerful than BMv2's textual
  traces.
- **Easy to extend and evolve.** BMv2 is a large C++ codebase that has grown
  organically over many years — adding features or fixing spec compliance gaps
  requires deep familiarity with its internals. 4ward is designed from the
  ground up to be approachable for any contributor, human or AI.

### Why 4ward is easier to extend

- **AI-first development.** 4ward is written from the ground up by AI coding
  agents, using prompting only. The codebase, docs, and test infrastructure
  are designed so that an agent can pick up a failing test, implement the
  missing feature, and land a correct change — autonomously. If an AI agent
  can extend it, anyone can.
- **Kotlin's type system as a guardrail.** Sealed classes and exhaustive
  `when` expressions mean the compiler enforces completeness — add a new IR
  node and the compiler tells you every place that needs handling. No silent
  fallthrough, no missing cases. The type system catches what the agent
  misses, making it safe to move fast.
- **Testing from day one.** Fast CI on GitHub Actions, extensive e2e test
  coverage (200+ STF corpus tests, p4testgen path coverage), and unit tests
  for every tricky invariant. Testing is not an afterthought — it's what
  drives development. The failing-test list *is* the feature backlog, and
  `bazel test //...` is the definition of done.
- **Architecture pluggability.** Adding a new P4 architecture means
  implementing a Kotlin interface, not forking the codebase.

### Keeping it easy: the strategy

- **Readability over performance.** Always.
- **One contract, two codebases.** The proto IR is the only interface between
  backend and simulator — extend either side independently.
- **Outstanding CI, automation, and docs.** With AI, these are cheap to build
  and maintain — so there's no excuse not to have excellent ones.

This north star connects all the tracks: v1model completeness (track 1) makes
4ward a credible reference, trace trees (track 3) make it *better* than BMv2,
and the P4Runtime server (track 4) is the integration point.

## Tracks

### Track 1: v1model spec compliance

**Priority: top | Parallelizable: yes (within and across subtracks)**

Complete the v1model reference implementation. This is the foundation —
without it, trace trees aren't useful, P4Runtime is limited, and the
"spec-compliant reference implementation" claim doesn't hold.

Three subtracks, each a different testing methodology:

- **1A: STF corpus** — make every v1model-capable p4c STF test pass.
  132/213 passing today. Remaining gaps: header unions (10), verify/checksum
  externs (6), payload mismatches (5), register/counter externs (3), and
  assorted edge cases. Two categories are build plumbing, not simulator
  work: skeleton includes (15 tests) and lookahead/advance (6 tests).
- **1B: p4testgen** — unpin `max_tests = 1` limits, expand from 10 programs
  to the full passing corpus, fix new failures. Deeper path coverage than
  hand-written STFs.
- **1C: BMv2 diff testing** — run the same inputs through BMv2 and 4ward,
  compare outputs. Catches gaps that STF tests miss.

**Done when:** all v1model-capable corpus tests pass (1A), p4testgen runs
unpinned across the full corpus (1B), and BMv2 diff testing surfaces no
mismatches (1C).

### Track 2: infrastructure

**Priority: nice to have | Parallelizable: yes**

Build plumbing, CI improvements, cleanup. Picked up opportunistically — none
of this blocks the other tracks. See [REFACTORING.md](REFACTORING.md) for
the full list.

### Track 3: trace trees

**Priority: medium — start now | Parallelizable: yes**

The killer feature. Fork at non-deterministic choice points (action selectors,
profiles, replication) and return a tree of all possible executions. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the full design vision.

This is what differentiates 4ward from every other P4 tool. BMv2 picks one
path. Hardware picks one path. 4ward shows you all of them.

The interpreter is already solid enough to build on (132 passing tests), and
action selectors are orthogonal to the remaining v1model gaps — no need to
wait.

Work breakdown:
1. **Testing harness**: golden tests that snapshot trace tree output (proto
   text or JSON) for small P4 programs with action selectors. The golden file
   is the spec.
2. **Design**: proto schema for tree-structured traces, forking semantics,
   output format.
3. **Implement**: interpreter changes to fork execution, trace tree
   construction, flag and annotation support.
4. **Polish**: trace tree visualization, diffing tools, documentation.

**Done when:** `--nondeterministic-selectors` produces correct trace trees for
programs with action selectors and action profiles.

### Track 4: P4Runtime server

**Priority: medium — start now | Parallelizable: yes (fully independent)**

A Go gRPC server that speaks P4Runtime to controllers and translates requests
into `SimRequest` proto messages for the simulator. This is what turns 4ward
from a testing tool into something you can plug into a real SDN stack.

Fully independent of the other tracks — different language (Go), different
codebase, communicates with the simulator only via the proto service protocol.
Can work with whatever subset of v1model is already passing.

**Done when:** a controller can install table entries and send/receive packets
through 4ward via standard P4Runtime.

### Track 5: architecture expansion (PSA, then PNA/TNA)

**Priority: not now**

Broaden P4 program support beyond v1model. PSA is the obvious next target —
25 corpus tests already provide acceptance criteria. But each architecture is a
significant lift (new pipeline structure, new metadata, new externs) and
v1model should be solid first. The architecture interface (`Architecture.kt`)
is already designed for pluggability, so this will be straightforward when the
time comes.

**Done when:** PSA corpus tests pass.

## Sequencing

```
                    now                          next             later
              ┌─────────────────────┐    ┌──────────────┐    ┌──────────┐
  Track 1     │ v1model complete    │    │              │    │          │
              │                     │    │              │    │          │
  Track 2     │        · · · · · · nice to have · · · · · · · · · · · │
              │                     │    │              │    │          │
  Track 3     │ trace trees         │    │              │    │          │
              │                     │    │              │    │          │
  Track 4     │ P4Runtime server    │    │              │    │          │
              │                     │    │              │    │          │
  Track 5     │                     │    │              │    │   PSA    │
              └─────────────────────┘    └──────────────┘    └──────────┘
```

**Key dependencies:**
- Tracks 1, 3, and 4 proceed in parallel now.
- Track 2 is picked up opportunistically.
- Track 5 (PSA) depends on v1model being done (shared interpreter, proven
  patterns).
