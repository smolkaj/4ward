# What Happened to My Packet? Introducing 4ward.

If you've ever debugged a P4 program, you know the drill. A packet goes in,
something comes out (or doesn't), and you're left piecing together what happened
from log fragments and educated guesses. The switch is a black box. BMv2 gives
you a trace — a single path through the program — but if your program has
non-deterministic choice points (action selectors, clone, multicast), you only
see one outcome. Which one? You don't get to choose, and you can't see the
others.

[4ward](https://github.com/smolkaj/4ward) is a new P4 simulator that takes a
different approach. It's a **glass box**: every decision your packet makes is
visible — every parser transition, every table lookup, every action, every
branch. And when the program hits a non-deterministic choice point, 4ward
doesn't pick one path. It shows you **all of them**, as a structured **trace
tree**.

<p align="center">
<video src="https://smolka.st/4ward/assets/playground-demo.mp4" controls muted width="100%"></video>
</p>

## Trace trees: the killer feature

Here's a P4 program that clones a packet and forwards the original and clone
out of two different ports. One packet goes in, two come out. The trace tree
captures the full picture:

```protobuf
events { parser_transition { from_state: "start"  to_state: "accept" } }
events { clone { session_id: 100 } }
fork_outcome {
  reason: CLONE
  branches {
    label: "original"
    subtree {
      events { table_lookup { action_name: "tag_original" } }
      packet_outcome { output { egress_port: 2 } }
    }
  }
  branches {
    label: "clone"
    subtree {
      events { table_lookup { action_name: "tag_clone" } }
      packet_outcome { output { egress_port: 3 } }
    }
  }
}
```

This isn't a log you have to grep through. It's a structured proto you can
programmatically inspect, diff, and assert against in tests. And it captures
*every possible outcome* — not just the one that happened to fire.

No other P4 tool gives you this. BMv2 picks one path. Hardware picks one path.
4ward shows you all of them.

## Built for testing, not forwarding

4ward is not a data plane. It's a **spec-compliant reference implementation**
of [P4₁₆](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/p4-16-working-draft.html)
and [P4Runtime](https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html),
optimized for **correctness, observability, and extensibility**. Think of it as
a debugger that speaks P4.

The concrete goal: replace BMv2 as the reference simulator in
[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas) (SONiC's
Dataplane Validation as a Service), and fully support
[SAI P4](https://github.com/sonic-net/sonic-pins/tree/main/sai_p4) — Google's
production P4 pipeline for network switches.

These two forcing functions keep us honest. SAI P4 is a 30-table v1model
program with heavy use of `@p4runtime_translation` (string port names!),
`@entry_restriction`, and `@refers_to` — the kind of production complexity
that other tools paper over with hardcoded workarounds. DVaaS needs structured
traces and all-paths coverage to validate switch behavior reliably.

## What's under the hood

**Three architectures.** v1model, PSA, and PNA all work today. The architecture
boundary is clean — adding a new one means implementing a Kotlin interface, not
forking the simulator.

**P4Runtime done right.** 141 out of 142 spec requirements tested and passing.
Full multi-controller arbitration, role-based access control, and a built-in
`@p4runtime_translation` engine with explicit, auto-allocate, and hybrid
mapping modes.

**Interactive web playground.** A browser-based IDE where you write P4, install
table entries, inject packets, and step through the resulting trace — with
animated playback that highlights the active source line and control-flow node
in sync. `bazel run //web:playground` and you're in.

**A proper CLI.** `4ward run program.p4 test.stf` for quick experiments;
`--format=json` or `--format=textproto` for machine-readable output in CI
pipelines.

## Three oracles, one answer

Here's the question everyone asks: *how do you know it's correct?*

4ward uses three independent testing strategies, each with a different source of
truth:

1. **200+ conformance tests** from p4c's own test suite — hand-written
   expectations by the people who built the language.
2. **Symbolic path exploration** via p4testgen — auto-generated tests that
   systematically cover execution paths humans wouldn't think to exercise.
3. **Differential testing** against BMv2 — run identical inputs through both
   simulators, compare every output.

When three independent oracles agree, the answer is clear.

## 100% AI-written

Every line of code, every test, every doc — including the one you're reading
right now — was written by AI (Claude). The entire codebase was built from
scratch in under three months: 40k lines of code, 400+ PRs, and CI that runs
in about 2 minutes.

The natural follow-up is: should you trust AI-written code?

The answer isn't "trust the AI." It's **trust the tests**. The three-oracle
testing strategy above doesn't care who wrote the code. It cares whether the
code is correct. And when you can verify correctness against the language spec,
a symbolic solver, *and* the existing reference implementation, the question of
authorship stops mattering.

There's a practical upside too: 4ward is designed to be extended by AI agents.
If Claude can navigate the codebase, understand the IR, and land a feature with
passing tests — so can you. The codebase is its own documentation.

## Try it

```sh
git clone https://github.com/smolkaj/4ward.git && cd 4ward
bazel run //web:playground    # opens http://localhost:8080
```

Or from the command line:

```sh
alias 4ward='bazel run //cli:4ward --'

4ward run examples/basic_table.p4 - << 'EOF'
add port_table hdr.ethernet.etherType:0x0800 forward(1)
packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
EOF
```
```
packet received: port 0, 18 bytes
  parse: start -> accept
  table port_table: hit -> forward
  action forward(port=1)
  output port 1, 18 bytes
PASS
```

Every step is visible. That's the point.

## Links

- **GitHub:** [smolkaj/4ward](https://github.com/smolkaj/4ward)
- **Documentation:** [smolka.st/4ward](https://smolka.st/4ward/)
- **Roadmap:** [ROADMAP.md](https://github.com/smolkaj/4ward/blob/main/docs/ROADMAP.md)
- **How we develop with AI:** [AI_WORKFLOW.md](https://github.com/smolkaj/4ward/blob/main/docs/AI_WORKFLOW.md)
