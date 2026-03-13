# Playground Vision

The web playground is a browser-based IDE for P4 — edit, compile, and simulate
in a single feedback loop. The goal is to make the playground the tool that
*teaches* you P4 — where you can *see* your packet flow through the pipeline,
watch headers get extracted, and understand exactly why a table matched or a
packet was dropped.

This document describes the vision and tracks what's been shipped.

## Shipped

### 1. Visual pipeline diagram (PR #279)

Control-flow graph showing tables, conditions, and control flow for each
pipeline stage. Rendered client-side with dagre for layout. Includes both
parser state machines and control block DAGs.

### 2. Animated trace playback (PR #297)

Step through the trace event by event (arrow keys, Escape to reset). Each step
simultaneously highlights:

- The current event in the trace panel.
- The corresponding line in the P4 editor (via source info).
- The active node in the control-flow graph.

Three views in sync — a "movie" of packet processing.

### 3. Packet dissection (PR #302)

Output packets are decoded into named header fields using the program's own
deparser emit events and header type definitions from the IR. Smart formatting
for MAC addresses and IPv4. Displayed in both the Packets tab and inline in
trace outcomes. Multi-output packets (clone/multicast) correctly resolve which
fork branch produced each output.

### 7. Table graph visualization (PR #279)

Shipped together with item 1. Shows what happens *inside* each control block:
which tables are applied, in what order, and how conditionals connect them.
Highlights the active node during trace playback.

## Not yet started

### 4. Structured packet builder

Instead of typing raw hex, fill in protocol fields:

```
+-------------------------------------+
| Ethernet                            |
|   dst: [ff:ff:ff:ff:ff:ff]          |
|   src: [00:00:00:00:00:01]          |
|   etherType: [0x0800]               |
+-------------------------------------+
| IPv4                                |
|   src: [10.0.0.1]                   |
|   dst: [10.0.0.2]                   |
|   ttl: [64]                         |
+-------------------------------------+
```

Auto-encode to hex. The builder can offer protocol layers based on the
program's parser. The header type schemas shipped to the frontend for packet
dissection (item 3) can be reused here.

### 5. Table stakes polish

- **localStorage persistence.** Editor content, table entries, and clone
  sessions survive page refreshes. No more "I refreshed and lost everything."

- **Packet history.** Keep a scrollable log of all packets sent and their
  results — not just the last one. Compare runs. Spot regressions.

- **URL sharing.** Encode the program + table entries + packet in a URL hash.
  One click to share a complete scenario — no backend needed.

### 6. More examples and guided tours

The current examples (basic_table, passthrough, mirror, sai_middleblock) cover
the basics. Candidates for more:

- **LPM routing** — longest prefix match, the bread and butter of forwarding.
- **Ternary ACL** — access control lists with priorities.
- **Multicast** — one packet in, many out. Shows the trace tree forking.
- **Action selector** — non-deterministic choice, trace tree exploration.
- **Stateful counting** — read/write counters, see state persist across
  packets.

### Input/output packet diff

Show input and output packets side-by-side with field-level diff highlighting:
what changed? TTL decremented? Source MAC rewritten? The diff makes the
program's effect visible at a glance. Builds on the dissection infrastructure
from item 3.
