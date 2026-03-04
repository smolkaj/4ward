<p align="center">
  <img src="logo.svg" alt="4ward logo" width="200">
  <br><br>
  <strong>Your P4 programs, finally explained.</strong>
</p>

# 4ward

Ever stare at a packet leaving a switch and wonder *what just happened in
there?* 4ward is a glass-box P4 simulator that tells you exactly what happened
to your packet — every parser transition, every table lookup, every action, every
branch — delivered as a structured trace you can actually read.

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
            │  (Kotlin/JVM)  │──▶ execution trace  (the good stuff)
            └────────────────┘
                     ▲
             P4Runtime writes
             (table entries,
              counters, etc.)
```

## Why 4ward?

| | BMv2 | Real hardware | **4ward** |
|---|---|---|---|
| Runs P4 programs | sure | sure | **yep** |
| Execution trace | nope | nope | **yes!** |
| Architecture-generic | nope | nope | **yes!** |
| P4Runtime | sure | sure | **yep** |
| Simple, readable codebase | ehh | ehh | **yes!** |

4ward optimises for **correctness and observability**, not performance. It's a
development and testing tool — think of it as a debugger that speaks P4, not a
production data plane.

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

# Simulate a P4 program (once the backend and simulator are on your PATH).
p4c-4ward --arch v1model my_program.p4 -o my_program.txtpb
4ward my_program.txtpb < input.stf
```

## See what your packets are up to

Given a simple IPv4 forwarding program, 4ward produces traces like this:

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

No printf debugging. No Wireshark. No guessing. Just the trace.

## Project structure

```
4ward/
├── simulator/              Kotlin simulator — the brain
│   ├── ir.proto            Behavioral IR (the contract between backend & sim)
│   └── simulator.proto     Simulator service protocol (stdin/stdout framing)
├── p4c_backend/            p4c backend plugin (C++, emits the proto IR)
└── e2e_tests/              STF test runner and test programs
    └── stf/                STF runner
```

Curious about the design? [ARCHITECTURE.md](ARCHITECTURE.md) has the full story.

## Where things stand

4ward is young and growing fast. Feature development is driven by the p4c STF
test corpus — each new feature makes another batch of tests go green.

- [x] Proto IR schema
- [x] Project skeleton
- [x] p4c backend (v1model, passthrough)
- [x] Simulator (passthrough)
- [x] STF test runner
- [ ] Full v1model support
- [ ] P4Runtime server (Go)

## Want to help?

We'd love that! The easiest way to contribute is to pick a failing STF test and
make it pass — they're naturally well-scoped and self-contained. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the full details.

## License

Apache 2.0. See [LICENSE](LICENSE).
