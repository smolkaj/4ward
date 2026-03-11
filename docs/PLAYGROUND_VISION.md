# Playground Vision

The web playground today is a functional P4 REPL: edit, compile, install table
entries, send packets, read traces. It works — but it doesn't yet play to
4ward's unique strength: **glass-box observability**.

The trace is rendered as indented text, which is fine for developers who already
understand P4. The goal is to make the playground the tool that *teaches* you
P4 — where you can *see* your packet flow through the pipeline, watch headers
get extracted, and understand exactly why a table matched or a packet was
dropped.

This document describes the vision. Items are ordered by impact, not
difficulty.

## 1. Visual pipeline diagram

**The single highest-impact improvement.**

Show the v1model pipeline as a visual flow:

```
parser → verify_checksum → ingress → egress → compute_checksum → deparser
```

When a packet is traced, stages light up. The active stage highlights. You can
see at a glance where the packet went and where it stopped. Drop? The stage
turns red. Clone? The flow forks visually.

This turns an abstract concept into something spatial and immediate. A newcomer
can look at the diagram and understand the v1model pipeline without reading the
spec.

## 2. Animated trace playback

A **step** button that walks through the trace event by event. Each step:

- Highlights the current event in the trace panel.
- Highlights the corresponding line in the P4 editor (via source info).
- Lights up the active stage in the pipeline diagram.

Basically a P4 debugger — but for packets, not code. Play/pause, step
forward/back, adjustable speed. This is what makes the playground a *learning
tool*, not just a REPL.

Combined with the pipeline diagram, this creates a "movie" of packet
processing that no other P4 tool offers.

## 3. Packet dissection

Decode raw hex into structured protocol fields — like a mini-Wireshark:

```
Ethernet
  dst: ff:ff:ff:ff:ff:ff
  src: 00:00:00:00:00:01
  etherType: 0x0800 (IPv4)
IPv4
  src: 10.0.0.1
  dst: 10.0.0.2
  ttl: 64
  protocol: 0 (HOPOPT)
Payload
  de ad be ef
```

Show input and output packets side-by-side with field-level diff highlighting:
what changed? TTL decremented? Source MAC rewritten? The diff makes the
program's effect visible at a glance.

The parser knows which headers exist (from P4Info / the trace) — use that to
guide dissection rather than guessing protocols.

## 4. Structured packet builder

Instead of typing raw hex, fill in protocol fields:

```
┌─────────────────────────────────────┐
│ Ethernet                            │
│   dst: [ff:ff:ff:ff:ff:ff]          │
│   src: [00:00:00:00:00:01]          │
│   etherType: [0x0800 ▾]            │
├─────────────────────────────────────┤
│ IPv4                                │
│   src: [10.0.0.1]                   │
│   dst: [10.0.0.2]                   │
│   ttl: [64]                         │
└─────────────────────────────────────┘
```

Auto-encode to hex. Users think in protocols, not bytes — this removes the
biggest barrier to entry. The builder can offer protocol layers based on the
program's parser (if it parses IPv4, offer IPv4 fields).

## 5. Table stakes polish

Things the playground should have done from day one:

- **localStorage persistence.** Editor content, table entries, and clone
  sessions survive page refreshes. No more "I refreshed and lost everything."

- **Packet history.** Keep a scrollable log of all packets sent and their
  results — not just the last one. Compare runs. Spot regressions.

- **URL sharing.** Encode the program + table entries + packet in a URL hash.
  One click to share a complete scenario — no backend needed. "Here's my
  program, try sending this packet" becomes a link.

## 6. More examples and guided tours

The three current examples (basic_table, passthrough, mirror) cover the basics.
Add:

- **LPM routing** — longest prefix match, the bread and butter of forwarding.
- **Ternary ACL** — access control lists with priorities.
- **Multicast** — one packet in, many out. Shows the trace tree forking.
- **Action selector** — non-deterministic choice, trace tree exploration.
- **Stateful counting** — read/write counters, see state persist across
  packets.

A **guided tour** mode could walk new users through their first table entry and
packet, step by step — with tooltips pointing at each UI element and explaining
what it does. Think of it as an interactive version of the tutorial.

## 7. Table graph visualization

**Zoom into control blocks.** The pipeline diagram (item 1) shows the
six v1model stages. The next level of detail shows what happens *inside*
each control block: which tables are applied, in what order, and how
conditionals connect them.

```
ingress:
  ┌──────────────┐
  │ acl_ingress  │
  └──────┬───────┘
         │
    ┌────▼────┐
    │ if hit  │──── miss ──▶ ...
    └────┬────┘
         │ hit
    ┌────▼──────────┐
    │ ipv4_lpm      │
    └──────┬────────┘
           │
    ┌──────▼──────────┐
    │ nexthop_table   │
    └─────────────────┘
```

Control blocks in P4 are **DAGs** (no loops — the language forbids them),
which makes layout tractable. Each table becomes a node; conditionals become
branch edges with hit/miss or true/false labels.

### Why this matters for SAI P4

SAI P4 middleblock has ~50 tables across ingress and egress. The flat trace
view works for toy programs, but at SAI scale it's hard to tell which tables
fired and how they relate. A graph makes the forwarding pipeline structure
visible — zoom into ingress, see all 30+ tables and how they connect.

### Approach

1. **New API endpoint** (`/api/control-graph`): walk the IR's statement tree
   for each control block, extract table-apply nodes and branch edges, emit a
   simplified DAG (JSON adjacency list).

2. **Client-side graph layout**: use a small layout library
   ([dagre](https://github.com/dagrejs/dagre) or
   [elkjs](https://github.com/kieler/elkjs), both ~15KB from CDN) to compute
   node positions. Render with SVG or positioned divs.

3. **Trace integration**: during playback, highlight the active table node.
   `table_lookup` trace events already carry hit/miss — color the edges to
   show which path the packet took.

4. **Scope**: start with tables only — omit parser states and deparser emit
   order for now. Those could be added later as separate graph views.

## Implementation notes

- Items 1 and 2 (pipeline diagram + animated playback) are tightly coupled —
  they should land together. This is the PR that transforms the playground from
  "functional tool" to "the best way to understand P4."

- Item 3 (packet dissection) is independent and can land separately. The
  protocol knowledge can be shared with item 4 (packet builder).

- Item 5 (polish) can be sprinkled across any PR.

- Item 6 (examples + tours) is content work — easy to parallelize with
  engineering.

- Item 7 (table graph) requires a new backend endpoint but is otherwise
  frontend work. The graph layout library adds a small dependency. This
  naturally follows items 1–2 — it deepens the visualization without changing
  the architecture.

- All frontend-only items work with the existing backend API. Item 7 needs
  one new endpoint.
