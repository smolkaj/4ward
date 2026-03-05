<p align="center">
  <img src="logo.svg" alt="4ward logo" width="200">
  <br><br>
  <strong>Your P4 programs, finally explained.</strong>
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

| | Real hardware | BMv2 | **4ward** |
|---|---|---|---|
| Runs P4 programs | sure | sure | **yep** |
| Spec-compliant | varies | needs workarounds | **out of the box** |
| Trace format | nope | text | **proto/JSON** |
| All possible traces | nope | not natively | **trace trees!** |
| Architecture-generic | nope | nope | **yes!** |
| P4Runtime | sure | has gaps | **100% spec-compliant (planned)** |
| AI friendly | nope | nope | **built by AI, for everyone** |
| Simple, readable codebase | ehh | ehh | **yes!** |

4ward is a **spec-compliant reference implementation** of the
[P4₁₆ language](https://p4.org/wp-content/uploads/sites/53/2024/10/P4-16-spec-v1.2.5.html),
optimised for **correctness and observability** rather than performance. Think of
it as a debugger that speaks P4, not a production data plane.

## Quick start

You need [Bazel](https://bazel.build) 9+ (or just grab
[Bazelisk](https://github.com/bazelbuild/bazelisk) and forget about it) and a
C++20 compiler for the p4c backend. Everything else is hermetic — Bazel handles
it.

```sh
# Build everything.
bazel build //...

# Run all tests.
bazel test //...

# Compile a P4 program to the proto IR.
bazel run //p4c_backend:p4c-4ward -- --arch v1model \
  $(pwd)/my_program.p4 -o $(pwd)/my_program.txtpb

# Simulate it (reads an STF scenario on stdin).
bazel run //simulator:simulator -- $(pwd)/my_program.txtpb < input.stf
```

## See what your packets are up to

Given a simple IPv4 forwarding program, 4ward produces trace trees like this:

```json
{
  "events": [
    {"parserTransition": {"parserName": "MyParser", "fromState": "start",         "toState": "parse_ethernet"}},
    {"parserTransition": {"parserName": "MyParser", "fromState": "parse_ethernet","toState": "parse_ipv4"}},
    {"parserTransition": {"parserName": "MyParser", "fromState": "parse_ipv4",    "toState": "accept"}},
    {"tableLookup": {
       "tableName": "ipv4_lpm", "hit": true, "actionName": "set_nhop",
       "matchedEntry": {
         "tableId": 37298292,
         "match": [{"fieldId": 1, "lpm": {"value": "CgAAAA==", "prefixLen": 24}}],
         "action": {"action": {"actionId": 28792498, "params": [{"paramId": 1, "value": "AQ=="}]}}
       }
    }},
    {"actionExecution": {"actionName": "set_nhop", "params": {"port": "AQ=="}}},
    {"branch":          {"controlName": "MyIngress", "taken": false}}
  ]
}
```

No printf debugging. No Wireshark. No guessing. Just the trace tree.

## Not just one trace — all of them

P4 programs have non-deterministic choice points. Action selectors pick a group
member based on an opaque hash. Action profiles let the controller choose
between multiple actions. Multicast replicates packets to different ports.

Other tools pick one path and show you what happened. 4ward will show you what
*could* happen — all possible executions, returned as a **trace tree**:

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

Since execution paths share a common prefix, the tree is compact — shared work
is represented once, and each fork node is labeled with the choice being made.
A flag like `--nondeterministic-selectors` tells the simulator to fork at every
action selector; P4 annotations give fine-grained per-selector control.

This is 4ward's killer feature: the tool you reach for when you need to
understand not just what your program *did*, but everything it *can* do.

## Project structure

```
4ward/
├── simulator/              Kotlin simulator — the brain
│   ├── ir.proto            Behavioral IR (the contract between backend & sim)
│   └── simulator.proto     Simulator service protocol (stdin/stdout framing)
├── p4c_backend/            p4c backend plugin (C++, emits the proto IR)
└── e2e_tests/
    ├── stf/                STF runner (shared subprocess + packet I/O)
    ├── corpus/             p4c STF corpus (216 tests, bulk regression)
    ├── trace_tree/         Golden trace-tree tests
    ├── p4testgen/          p4testgen integration (auto-generated paths)
    └── <feature>/          Hand-written feature tests (passthrough, lpm, …)
```

Curious about the design? [ARCHITECTURE.md](ARCHITECTURE.md) has the full story.

## Where things stand

4ward is pre-1.0 and growing fast. The core pipeline works end-to-end. We will aggressively
refactor to build the best system we can; nothing is sacred except
correctness and the test suite. See [ROADMAP.md](ROADMAP.md) for what's next
and [STATUS.md](STATUS.md) for daily progress.

## Documentation

| Document | Purpose |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Design rationale and component overview |
| [ROADMAP.md](ROADMAP.md) | Development tracks, priorities, and sequencing |
| [STATUS.md](STATUS.md) | Append-only log of daily progress |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to get involved |
| [AGENTS.md](AGENTS.md) | Guide for AI coding agents |
| [CLAUDE.md](CLAUDE.md) | Claude Code-specific instructions |
| [LIMITATIONS.md](LIMITATIONS.md) | Known shortcuts and gaps |
| [REFACTORING.md](REFACTORING.md) | Tech debt and cleanup backlog |
| [STEFFENS_AI_WORKFLOW.md](STEFFENS_AI_WORKFLOW.md) | AI prompting principles and workflow |

## Want to help?

We'd love that! The easiest way to contribute is to pick a failing STF test and
make it pass — they're naturally well-scoped and self-contained. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the full details.

## License

Apache 2.0. See [LICENSE](LICENSE).
