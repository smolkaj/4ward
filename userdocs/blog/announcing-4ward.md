# 4ward: a P4 simulator that shows its work

Most P4 tools give you a black box. Packet goes in, packet comes out, and
if something went wrong you're on your own. BMv2 gives you a trace, which
helps — but it's one path through the program. If your program clones,
multicasts, or uses an action selector, you see one arbitrary outcome and
have to guess about the rest.

4ward shows you all of them.

<p align="center">
<video src="https://smolka.st/4ward/assets/playground-demo.mp4" controls muted width="100%"></video>
</p>

## Trace trees

When a packet hits a non-deterministic choice point, 4ward forks the
trace into a tree. One packet in, multiple possible outcomes out — all
captured in a single proto:

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

You can diff these, assert against them in tests, or just read them. Every
parser transition, table hit, action, and output is there. No grepping
through logs.

## Why this exists

We're building 4ward to replace BMv2 in
[DVaaS](https://github.com/sonic-net/sonic-pins/tree/main/dvaas), SONiC's
dataplane validation service. DVaaS sends the same packets through a switch
and a reference simulator, then compares outputs. The reference simulator
needs to be correct, observable, and able to handle production P4 programs
like [SAI P4](https://github.com/sonic-net/sonic-pins/tree/main/sai_p4) —
a 30-table v1model program with string-translated ports,
`@entry_restriction`, and all the things BMv2 doesn't do well.

4ward handles all of that. It's a
[spec-compliant](https://github.com/smolkaj/4ward/blob/main/docs/P4RUNTIME_COMPLIANCE.md)
P4₁₆ and P4Runtime implementation — not a data plane, but a reference you
can trust. 144 P4Runtime requirements tested. Three architectures (v1model,
PSA, PNA). A built-in `@p4runtime_translation` engine.

## How we know it works

Three independent test strategies, each with a different source of truth:

- **p4c's own test suite** — 200+ conformance tests with hand-written
  expectations from the language authors.
- **p4testgen** — symbolic execution that auto-generates tests covering
  paths you'd never think to write by hand.
- **Differential testing against BMv2** — same inputs, compare outputs.

When all three agree, the code is correct.

## The AI thing

4ward is written entirely by AI — Claude, specifically. Every line of
code, every test, every doc. 40k lines, 400+ PRs, under three months.

We're not saying this to be flashy. We're saying it because the codebase
proves something useful: if you have good tests, good CI, and a codebase
that an AI agent can navigate, authorship doesn't matter. The tests are
the authority, not the author. And as a side effect, any contributor —
human or AI — can pick up the codebase and extend it. The same properties
that make it AI-friendly make it human-friendly.

## Try it

The web playground is the fastest way in:

```sh
git clone https://github.com/smolkaj/4ward.git && cd 4ward
bazel run //web:playground
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

- [GitHub](https://github.com/smolkaj/4ward)
- [Docs](https://smolka.st/4ward/)
- [Roadmap](https://github.com/smolkaj/4ward/blob/main/docs/ROADMAP.md)
- [How we develop with AI](https://github.com/smolkaj/4ward/blob/main/docs/AI_WORKFLOW.md)
