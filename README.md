<p align="center">
  <img src="docs/logo.svg" alt="4ward logo" width="200">
  <br><br>
  <strong>Your P4 programs, finally explained.</strong>
  <br><br>
  <a href="https://github.com/smolkaj/4ward/actions/workflows/ci.yml"><img src="https://github.com/smolkaj/4ward/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://smolkaj.github.io/4ward/main/"><img src="https://img.shields.io/badge/coverage-report-blue" alt="Coverage"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License"></a>
</p>

# 4ward

Ever stare at a packet leaving a switch and wonder *what just happened in
there?* 4ward is a glass-box P4 simulator that tells you exactly what happened
to your packet — every parser transition, every table lookup, every action, every
branch — delivered as a structured trace tree you can actually read.

```
             p4c + 4ward backend
                     │
                     ▼
              PipelineConfig
             (proto IR + p4info)
                     │
                     ▼
            ┌────────────────┐
 packet ──▶ │  4ward sim     │──▶ output packets
            │  (Kotlin/JVM)  │──▶ trace tree  (the good stuff)
            └────────────────┘
                     ▲
             P4Runtime writes
             (table entries,
              counters, etc.)
```

## Why 4ward?

4ward is a **spec-compliant reference implementation** of the
[P4₁₆ language](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html)
and [P4Runtime](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html),
optimised for **correctness, observability, and extensibility** rather than
performance. Think of it as a debugger that speaks P4, not a production data
plane.

| | Real hardware | BMv2 | **4ward** |
|---|---|---|---|
| Runs P4 programs | sure | sure | **yep** |
| Spec-compliant | varies | needs workarounds | **by design** |
| Trace format | nope | text | **proto/JSON** |
| All possible traces | nope | not natively | **trace trees!** |
| Architecture-generic | nope | nope | **by design** |
| P4Runtime | sure | has gaps | **100% spec-compliant (planned)** |
| Easy to extend | ehh | ehh | **if AI can extend it, anyone can** |
| Simple, readable codebase | ehh | ehh | **yes!** |
| Fast, rigorous CI | nope | slow | **[~2 min](https://4ward.buildbuddy.io/trends/)** |

## Why Kotlin?

The P4 ecosystem is C++. So why isn't 4ward?

4ward is **100% AI-written**. Humans define requirements, design the testing
strategy, and ask the right questions. AI agents write every line of code,
including the tests. Formatters, linters, and the compiler enforce mechanical
correctness. No one needs to hold language minutiae in their head — so we're
free to pick the best language for the problem, not the most familiar one.

**Why not C++?** Its top strengths — speed, ecosystem familiarity —
don't matter here. Its top weaknesses — compile times, complexity — matter a lot.

**Why Kotlin?** Fast builds, simple language, strong type system. Sealed classes
with pattern matching are tailor-made for tree-walking interpreters.

**Why not…**
- **Rust?** Borrow checker is overkill — we don't need manual memory control.
- **Go?** Weaker type system — no algebraic data types, no pattern matching.
- **Python?** Weak type system, slow test execution.
- **Java?** Kotlin, but worse. Verbose, no sealed `when`, no data classes.

The p4c backend is still C++ (because p4c is C++). The two halves communicate
through a language-neutral protobuf IR — the simulator's language choice doesn't
ripple into the ecosystem.

## Quick start

[Tested on](https://4ward.buildbuddy.io/tests/) macOS and Ubuntu. You need [Bazel](https://bazel.build) 9+ (or just
grab [Bazelisk](https://github.com/bazelbuild/bazelisk) and forget about it)
and a C++20 compiler for the p4c backend. Everything else is hermetic — Bazel
handles it.

```sh
# Build everything.
bazel build //...

# Run all tests.
bazel test //...
```

## See what your packets are up to

P4 programs have non-deterministic choice points — action selectors,
multicast, clone. Other tools pick one path. 4ward shows you *all* of them
as a **trace tree**:

```
                    ┌─ parse ─ table lookup ─┐
                    │                        │
         packet ────┤       (shared prefix)  ├─── action_selector ─┐
                    │                        │                     │
                    └────────────────────────┘        ┌────────────┼────────────┐
                                                      │            │            │
                                                  member_0     member_1     member_2
                                                      │            │            │
                                                   trace …     trace …     trace …
```

Here's what that looks like in practice — an ECMP program where the selector
can pick one of three members:

```protobuf
# shared prefix: parser + table lookup (same for all paths)
events { parser_transition { from_state: "start"  to_state: "accept" } }
events { table_lookup       { table_name: "ecmp"   hit: true          } }

# fork: one branch per action selector member
fork {
  reason: ACTION_SELECTOR
  branches { ... member_0 → port 1 ... }
  branches { ... member_1 → port 2 ... }
  branches { ... member_2 → port 3 ... }
}
```

No printf debugging. No Wireshark. No guessing.

## Project structure

```
4ward/
├── simulator/              Kotlin simulator — the brain
│   ├── ir.proto            Behavioral IR (the contract between backend & sim)
│   └── simulator.proto     Simulator service protocol (stdin/stdout framing)
├── p4c_backend/            p4c backend plugin (C++, emits the proto IR)
├── p4runtime/              P4Runtime gRPC server (Kotlin)
├── e2e_tests/
│   ├── stf/                STF runner (shared subprocess + packet I/O)
│   ├── corpus/             p4c STF corpus (bulk regression)
│   ├── trace_tree/         Golden trace-tree tests
│   ├── p4testgen/          p4testgen integration (auto-generated paths)
│   └── <feature>/          Hand-written feature tests (passthrough, lpm, …)
├── docs/                   Project documentation
└── tools/                  Developer scripts (format, lint, coverage, …)
```

Curious about the design? [ARCHITECTURE.md](docs/ARCHITECTURE.md) has the full story.

## Where things stand

4ward is pre-1.0 and growing fast. The core pipeline works end-to-end. See
[ROADMAP.md](docs/ROADMAP.md) for where we are going and [STATUS.md](docs/STATUS.md)
for daily progress.

**CAUTION:** We will aggressively refactor to build the best system we can;
nothing is sacred except correctness and the test suite — until we reach 1.0.

## CI that has your back

We think fast, reliable CI is key to keeping developers happy and productive.

Every PR gets built, linted, and tested in about 2 minutes — with a differential coverage
report in about 5. No flakes, no "works on my machine." See for yourself on the
[BuildBuddy dashboard](https://4ward.buildbuddy.io/trends/).

## Documentation

| Document | Purpose |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design rationale and component overview |
| [ROADMAP.md](docs/ROADMAP.md) | Development tracks, priorities, and sequencing |
| [STATUS.md](docs/STATUS.md) | Append-only log of daily progress |
| [CONTRIBUTING.md](docs/CONTRIBUTING.md) | How to get involved |
| [AGENTS.md](AGENTS.md) | Guide for AI coding agents |
| [CLAUDE.md](CLAUDE.md) | Claude Code-specific instructions |
| [LIMITATIONS.md](docs/LIMITATIONS.md) | Known shortcuts and gaps |
| [REFACTORING.md](docs/REFACTORING.md) | Tech debt and cleanup backlog |
| [AI_WORKFLOW.md](docs/AI_WORKFLOW.md) | How to develop with AI agents |

## Want to help?

We'd love that! The easiest way to contribute is to pick a failing STF test and
make it pass — they're naturally well-scoped and self-contained. See
[CONTRIBUTING.md](docs/CONTRIBUTING.md) for the full details.

## License

Apache 2.0. See [LICENSE](LICENSE).
