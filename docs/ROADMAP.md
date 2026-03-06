# 4ward — Roadmap

This document describes the main development tracks, their priorities, and how
they can be parallelized. See [STATUS.md](STATUS.md) for daily progress and
[ARCHITECTURE.md](ARCHITECTURE.md) for design details.

## North star

Two goals, mostly orthogonal:

1. **Replace BMv2 in [DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas).**
   DVaaS (Dataplane Validation as a Service) validates switch behavior by
   sending synthesized packets to both a switch under test and a reference P4
   simulator, then comparing outputs. Today that reference simulator is BMv2.

2. **Full [SAI P4](https://github.com/sonic-net/sonic-pins/tree/main/sai_p4) support.**
   SAI P4 is a 27-table v1model program that models the SONiC Switch
   Abstraction Interface. It makes heavy use of features that BMv2 handles
   poorly or not at all — `@p4runtime_translation`, `@entry_restriction` /
   `@action_restriction`
   ([p4-constraints](https://github.com/p4lang/p4-constraints)),
   `@refers_to` — requiring DVaaS to maintain a "BMv2-compatible config" that
   papers over the gaps.

4ward aims to handle all of this correctly, out of the box:

- **Spec compliance without workarounds.** Full support for
  `@p4runtime_translation` (including SAI P4's convention of string-translated
  port IDs), p4-constraints validation, and all v1model features SAI P4 uses.
- **Trace trees replace brittle hacks.** DVaaS currently uses hacks to
  extract all possible traces from BMv2 at non-deterministic choice points
  (e.g., action selectors), but this is brittle and inefficient. 4ward
  produces trace trees natively — all valid outputs in a single pass.
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
  coverage (STF corpus, p4testgen path coverage), and unit tests for every
  tricky invariant. Testing is not an afterthought — it's what
  drives development. The failing-test list *is* the feature backlog, and
  `bazel test //...` is the definition of done. See
  [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for the full philosophy.
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
and the P4Runtime server (track 4) is the integration point — with
`@p4runtime_translation` and p4-constraints support making it a P4Runtime
reference implementation in its own right.

## Tracks

### Track 1: v1model spec compliance

**Priority: top | Parallelizable: yes (within and across subtracks)**

Complete the v1model reference implementation. This is the foundation —
without it, trace trees aren't useful, P4Runtime is limited, and the
"spec-compliant reference implementation" claim doesn't hold.

Three subtracks, each a different testing methodology:

- **1A: STF corpus** — make every v1model-capable p4c STF test pass.
  See [STATUS.md](STATUS.md) for current counts and remaining gaps.
- **1B: p4testgen** — unpin `max_tests = 1` limits, expand from 10 programs
  to the full passing corpus, fix new failures. Deeper path coverage than
  hand-written STFs.
- **1C: BMv2 diff testing** — run the same inputs through BMv2 and 4ward,
  compare outputs. Catches gaps that STF tests miss.

**Done when:** all v1model-capable corpus tests pass (1A), p4testgen runs
unpinned across the full corpus (1B), and BMv2 diff testing surfaces no
mismatches (1C).

**Current status:** 1A complete (186/186). 1B: 155 programs, all unpinned.
1C: 183 programs diff-tested. See [STATUS.md](STATUS.md).

### Track 2: infrastructure

**Priority: high (p4testgen batching) / nice to have (rest) | Parallelizable: yes**

Build plumbing, CI improvements, cleanup.

**Highest priority item: p4testgen JVM batching.** p4testgen currently spawns
186 separate JVM processes (one per P4 program), which makes it impossible
to run locally without exhausting memory. Batch all p4testgen tests into a
single JVM (like the corpus test suite does for 186 STF tests). This would
make `bazel test //...` viable on developer machines again. Until then,
p4testgen tests are tagged `heavy` and skipped locally.

The rest is picked up opportunistically — see [REFACTORING.md](REFACTORING.md)
for the full list.

**Current status:** p4testgen batching done (155 tests in one JVM).

### Track 3: trace trees

**Priority: medium — start now | Parallelizable: yes**

The killer feature. Fork at non-deterministic choice points (action selectors,
profiles, replication) and return a tree of all possible executions. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the full design vision.

Work breakdown:

1. **Proto + golden tests.** `TraceTree` schema, golden test harness,
   failing tests covering all fork types.
2. **Zero-fork tree.** Simulator produces `TraceTree` instead of flat `Trace`.
   Existing tests pass unchanged; `no_fork` golden test passes.
3. **Action selector forking.** IR support, deep copy, `TraceTreeBuilder`,
   interpreter forks at selector tables.
4. **Clone + multicast forking.** Fork at clone/multicast points.

**Done when:** all golden trace tree tests pass and all existing corpus tests
still pass.

**Current status:** complete. All fork types (action selector, clone,
multicast) implemented and tested.

### Track 4: P4Runtime reference implementation

**Priority: medium — start now | Parallelizable: yes**

A gRPC server that speaks P4Runtime to controllers and forwards requests to
the simulator. Goals:

- **Correctness, simplicity, readability.** Not performance.
- **100% spec-compliant.** Follow the P4Runtime spec to the letter.
- **`@p4runtime_translation`.** Bidirectional translation between
  controller-facing and data-plane values. SAI P4 translates all ID types
  (including ports) to strings via `@p4runtime_translation("", string)`.
  The spec is underspecified here; fill gaps guided by SAI P4 conventions.
- **[p4-constraints](https://github.com/p4lang/p4-constraints).** Validate
  table entries and action parameters against `@entry_restriction` and
  `@action_restriction` annotations at write time.
- **Strict validation.** Enforce message validity rigorously. `--strict`
  mode additionally enforces recommended (not just required) properties.
- **Actionable error messages.** Not `INVALID_ARGUMENT`, but
  `table 'ipv4_table' entry violates constraint: vrf_id must be non-zero`.

Five subtracks:

- **4A: test strategy.** Three layers mirroring the simulator's approach:
  conformance tests (spec compliance), round-trip testing (simulator
  agreement), and fuzz testing (robustness). See
  [TESTING_STRATEGY.md](TESTING_STRATEGY.md) for the philosophy and
  [P4RUNTIME_COMPLIANCE.md](P4RUNTIME_COMPLIANCE.md) for the spec
  requirement matrix.
- **4B: p4-constraints.** JNI binding to
  [p4-constraints](https://github.com/p4lang/p4-constraints), validate
  `@entry_restriction` / `@action_restriction` at write time, actionable
  error messages. Core north-star requirement — SAI P4 depends on it heavily.
- **4C: string translation.** `@p4runtime_translation("", string)` support.
  SAI P4 translates port IDs to strings; without this, SAI P4 can't load.
- **4D: write validation & RPCs.** Richer write validation (match field
  completeness, param bitwidths, per-entity reads), implement
  `GetForwardingPipelineConfig` + `Capabilities`. Spec compliance polish.
- **4E: SAI P4 E2E.** Load SAI P4 pipeline, install entries, send packets
  through P4Runtime, verify outputs. The capstone that proves the whole
  stack works together.

**Done when:** SAI P4 works end-to-end through standard P4Runtime:
`SetForwardingPipelineConfig`, `Write` (with p4-constraints and
`@p4runtime_translation` validation), `Read`, and `StreamChannel` packet I/O.

**Current status:** 4A: three-layer strategy defined, compliance matrix
tracks 61 tested / 12 not tested / 7 not implemented. 4B: done. 4C: done.
4D: in progress. 4E: blocked on 4D. See
[P4RUNTIME_COMPLIANCE.md](P4RUNTIME_COMPLIANCE.md) and
[LIMITATIONS.md](LIMITATIONS.md).

### Track 5: architecture expansion (PSA, then PNA/TNA)

**Priority: not now**

4ward treats architectures as plugins — the `Architecture` interface is
designed for modularity from day one. PSA is the next target, with existing
corpus tests as acceptance criteria. Each architecture is a significant lift
(pipeline structure, metadata, externs), so v1model should be solid first.

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
