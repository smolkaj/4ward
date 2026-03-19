# Trace Trees

Every packet 4ward processes produces a **trace tree** — a complete record of
every decision the simulator made. Most packets take a single path through the
pipeline and produce a linear trace. At non-deterministic choice points (action
selectors, clone, multicast), the trace **forks** into branches, one per
possible outcome.

No other P4 tool gives you this. BMv2 picks one path. Hardware picks one path.
4ward shows you *all* paths in a single pass.

## Structure

A trace tree is a recursive structure:

```
TraceTree
├── events[]          — chronologically ordered
└── outcome
    ├── PacketOutcome — terminal: output on a port, or drop
    └── Fork          — non-terminal: branches into subtrees
```

Events at the parent level are **shared** across all branches. Per-branch
events live in the fork's subtrees. A program with no non-determinism produces
a zero-fork tree — structurally equivalent to a flat trace.

## A simple trace

A packet matching a forwarding table and exiting on port 1:

```
packet_ingress (port 0)
├─ parser: start → accept
├─ table port_table: hit → forward
├─ action forward(port=1)
├─ deparser: ethernet_t (14 bytes)
└─ output port 1
```

In proto text format, the same trace:

```textproto
# proto-file: @fourward//simulator/simulator.proto
# proto-message: fourward.sim.TraceTree
events { packet_ingress { dataplane_ingress_port: 0 } }
events { parser_transition {
  parser_name: "MyParser"
  from_state: "start"
  to_state: "accept"
} }
events { table_lookup {
  table_name: "port_table"
  hit: true
  matched_entry { ... }
  action_name: "forward"
} }
events { action_execution {
  action_name: "forward"
  params { key: "port" value: "\001" }
} }
events { deparser_emit { header_type: "ethernet_t" byte_length: 14 } }
packet_outcome {
  output { dataplane_egress_port: 1 payload: "..." }
}
```

## Forks

When execution reaches a non-deterministic choice point, the trace forks.
Each branch gets its own subtree with the remaining pipeline events.

### Clone

A clone creates two branches — the **original** packet continues on its
normal path, and the **clone** goes to the clone session's egress port:

```
packet_ingress (port 0)
├─ parser: start → accept
├─ table routing: hit → forward
├─ action forward(port=2)
├─ clone session 1
├─ clone_session_lookup: session 1 → port 3
└─ fork (clone)
   ├─ branch: original
   │  ├─ egress pipeline...
   │  └─ output port 2
   └─ branch: clone
      ├─ egress pipeline...
      └─ output port 3
```

### Multicast

Multicast creates one branch per replica in the multicast group:

```
├─ table routing: hit → multicast_forward
├─ action multicast_forward(group=1)
└─ fork (multicast)
   ├─ branch: replica_0_port_1
   │  └─ output port 1
   ├─ branch: replica_0_port_2
   │  └─ output port 2
   └─ branch: replica_0_port_3
      └─ output port 3
```

### Action selector

An action selector with multiple members forks into one branch per member —
showing every possible forwarding decision:

```
├─ table ecmp: hit → set_port
└─ fork (action selector)
   ├─ branch: member_0
   │  ├─ action set_port(port=1)
   │  └─ output port 1
   ├─ branch: member_1
   │  ├─ action set_port(port=2)
   │  └─ output port 2
   └─ branch: member_2
      ├─ action set_port(port=3)
      └─ output port 3
```

### Resubmit and recirculate

Both create a fork where one branch is the resubmitted/recirculated packet
re-entering the pipeline. The branch label is `resubmit` or `recirculate`.

## Event catalog

Every trace event carries optional **source info** (file, line, column,
source fragment) linking back to the P4 source.

| Event | Fields | When it fires |
|-------|--------|---------------|
| **PacketIngress** | `dataplane_ingress_port`, `p4rt_ingress_port` | First event in every trace |
| **PipelineStage** | `stage_name`, `stage_kind`, `direction` (ENTER/EXIT) | Entering or exiting a pipeline stage |
| **ParserTransition** | `from_state`, `to_state`, `select_value`, `select_expression` | Every parser state transition, including accept/reject |
| **TableLookup** | `table_name`, `hit`, `matched_entry`, `action_name` | Every `table.apply()` call |
| **ActionExecution** | `action_name`, `params` (name → bytes) | When an action begins executing |
| **Branch** | `control_name`, `taken` (true=then, false=else) | Every if/else branch |
| **ExternCall** | `extern_instance_name`, `method` | Every extern method call |
| **MarkToDrop** | `reason` | `mark_to_drop()` called |
| **Clone** | `session_id` | `clone()` / `clone3()` called |
| **CloneSessionLookup** | `session_id`, `session_found`, `dataplane_egress_port` | Traffic manager resolves a clone session |
| **LogMessage** | `message` | `log_msg()` called |
| **Assertion** | `passed` | `assert()` or `assume()` called |
| **DeparserEmit** | `header_type`, `byte_length` | Deparser emits a header |

## Packet outcomes

Every trace path terminates with a **PacketOutcome**:

- **Output** — packet transmitted: `dataplane_egress_port` + `payload`.
- **Drop** — packet dropped, with a reason:
    - `MARK_TO_DROP` — explicit `mark_to_drop()` call.
    - `PARSER_REJECT` — parser transitioned to the reject state.
    - `PIPELINE_EXECUTION_LIMIT_REACHED` — too many fork branches
      (exponential blowup guard).
    - `ASSERTION_FAILURE` — `assert()` or `assume()` failed.

## P4RT enrichment

When the loaded pipeline uses
[`@p4runtime_translation`](type-translation.md), trace events carry
**P4Runtime representations** alongside raw dataplane values. This happens
automatically when packets are injected through the
[DataplaneService gRPC API](../reference/grpc.md).

```textproto
# proto-file: @fourward//simulator/simulator.proto
# proto-message: fourward.sim.TraceTree
events { packet_ingress {
  dataplane_ingress_port: 1
  p4rt_ingress_port: "Ethernet0"
} }
events { table_lookup {
  table_name: "forwarding"
  hit: true
  matched_entry {
    # dataplane: action param value "\000"
  }
  p4rt_matched_entry {
    # P4Runtime: action param value "Ethernet1"
  }
} }
packet_outcome { output {
  dataplane_egress_port: 0
  p4rt_egress_port: "Ethernet1"
} }
```

Enrichment only applies to the DataplaneService gRPC path. The CLI and web
playground inject packets directly through the simulator and do not produce
enriched traces.

## Output formats

The CLI supports three trace output formats via `--format`:

**`--format=human`** (default) — indented text:

```
parse: start -> accept
table port_table: hit -> forward
action forward(port=1)
output port 1, 18 bytes
```

**`--format=textproto`** — proto text format, suitable for programmatic
consumption, golden file diffing, and proto tooling.

**`--format=json`** — JSON serialization of the proto, suitable for
programmatic consumption (jq, scripts, dashboards).

The web playground shows traces as an interactive visual tree, with optional
JSON and proto text views.
