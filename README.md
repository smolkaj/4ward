# 4ward

A glass-box P4 simulator. Unlike a real switch (or BMv2), 4ward tells you
*exactly* what happened to your packet: every parser transition, every table
lookup, every action executed, every branch taken — delivered as a structured
trace alongside the output packets.

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
            │  (Kotlin/JVM)  │──▶ execution trace
            └────────────────┘
                     ▲
             P4Runtime writes
             (table entries,
              counters, etc.)
```

## Why 4ward?

| | BMv2 | Real hardware | **4ward** |
|---|---|---|---|
| Runs P4 programs | ✓ | ✓ | **✓** |
| Execution trace | ✗ | ✗ | **✓** |
| Architecture-generic | ✗ | ✗ | **✓** |
| P4Runtime | ✓ | ✓ | **✓** |
| Simple codebase | ✗ | ✗ | **✓** |

4ward optimises for **correctness and observability**, not performance. It is a
development and testing tool, not a production data plane.

## Quick start

You need [Bazel](https://bazel.build) (version 9+, or use
[Bazelisk](https://github.com/bazelbuild/bazelisk)) and a C++20-capable
compiler for the p4c backend. Everything else is hermetic.

```sh
# Build everything.
bazel build //...

# Run all tests.
bazel test //...

# Simulate a P4 program (once the backend and simulator are on your PATH).
p4c-4ward --arch v1model my_program.p4 -o my_program.pb
4ward my_program.pb < input.stf
```

## Trace example

Given a simple IPv4 forwarding program, 4ward produces traces like:

```json
{
  "events": [
    {"parserTransition": {"parserName": "MyParser", "fromState": "start",        "toState": "parse_ethernet"}},
    {"parserTransition": {"parserName": "MyParser", "fromState": "parse_ethernet","toState": "parse_ipv4"}},
    {"parserTransition": {"parserName": "MyParser", "fromState": "parse_ipv4",   "toState": "accept"}},
    {"tableLookup":      {"tableName": "ipv4_lpm",  "hit": true, "actionName": "set_nhop",
                          "matchedEntry": {"match": [{"lpm": {"value": "CgAA", "prefixLen": 24}}]}}},
    {"actionExecution":  {"actionName": "set_nhop", "params": {"port": "AQ=="}}},
    {"branch":           {"controlName": "MyIngress", "taken": false}}
  ]
}
```

No printf debugging. No Wireshark. No guessing. Just the trace.

## Project structure

```
4ward/
├── proto/fourward/ir/v1/   # Behavioral IR (the core contract between backend and sim)
├── proto/fourward/sim/v1/  # Simulator service protocol (stdin/stdout framing)
├── simulator/              # Kotlin simulator (interpreter + P4Runtime adapter)
├── backend/                # p4c backend plugin (C++, emits the proto IR)
└── tests/                  # STF test runner and test programs
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for a deeper dive.

## Status

4ward is early-stage. The current focus is the walking skeleton: a passthrough
P4 program that exercises the full pipeline end-to-end. Feature development is
driven by the p4c STF test corpus — each new feature makes more tests go green.

- [x] Proto IR schema
- [x] Project skeleton
- [ ] p4c backend (v1model, passthrough)
- [ ] Simulator (passthrough)
- [ ] STF test runner
- [ ] Full v1model support
- [ ] P4Runtime server (Go)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and PRs welcome — especially
"make this STF test pass" contributions, which are naturally well-scoped.

## License

Apache 2.0. See [LICENSE](LICENSE).
