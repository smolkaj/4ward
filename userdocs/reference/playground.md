# Web Playground Reference

## Starting the server

```sh
bazel run //web:playground
```

| Flag | Default | Description |
|------|---------|-------------|
| `--http-port` | 8080 | HTTP server port |
| `--grpc-port` | 9559 | gRPC server port (P4Runtime + Dataplane) |
| `--drop-port` | `2^N - 1` | Override drop port (e.g., 511 for 9-bit ports) |
| `--cpu-port` | `2^N - 2` | Override CPU port (auto-enabled when `@controller_header` is present) |
| `--static-dir` | - | Serve frontend from a local directory (for development) |

The browser opens automatically. A gRPC server runs alongside the HTTP
server, so you can use gRPC clients (grpcurl, P4Runtime libraries) against
the same pipeline.

## Layout

**Left panel:** P4 source editor (Monaco) with syntax highlighting.

**Right panel:** Three tabs — Tables, Packets, Trace.

**Bottom panel:** Control-flow graph (appears after compilation).

## Editor

- **Example selector** — dropdown with built-in examples: `passthrough`,
  `basic_table`, `mirror`, `sai_middleblock`.
- **Compile & Load** — compiles the P4 source and loads it into the
  simulator. The status bar shows the pipeline summary (table count, action
  count) or compilation errors.

## Tables tab

Manage control-plane state for the loaded pipeline.

**Add entry:**

1. Select a table from the dropdown.
2. Fill in match fields (supports exact, LPM, ternary, optional).
3. Select an action and fill in parameters.
4. Set priority (for ternary/range tables).
5. Click **Add Entry**.

**Clone sessions:**

Enter a session ID and egress port, then click **Add Clone Session**.

**Installed entries:** lists all current table entries (including `const
entries` from the P4 source).

## Packets tab

**Presets:** IPv4, IPv6, ARP, Raw — each fills the payload textarea with a
valid frame for that protocol.

**Send Packet:** specify ingress port and hex payload, then click **Send
Packet**.

**Output packets:** shows each output packet with its egress port, byte
count, and decoded header fields (Ethernet MAC addresses, IPv4 addresses,
protocol numbers).

## Trace tab

**Visual view** (default): interactive trace tree. Events are grouped by
pipeline stage. Table hits show matched entry details. Forks display branches.

**Playback:** use **← →** arrow keys to step through events. Each step
highlights:

- The current event in the trace panel.
- The corresponding P4 source line in the editor.
- The active node in the control-flow graph.

Press **Escape** to reset playback.

**JSON view:** raw JSON serialization of the trace tree proto. Includes
`proto-file` / `proto-message` header.

**Proto view:** text-format protobuf. Includes `proto-file` /
`proto-message` header.

## Control-flow graph

Visual DAG of the pipeline's parser and control blocks. One tab per
pipeline stage (parser, verify_checksum, ingress, egress, etc.).

- **Table nodes** show table names.
- **Condition nodes** show if/else branching.
- **Edge labels** show branch conditions.
- The active node highlights during trace playback.
- **Fullscreen** toggle in the corner.

## REST API

The playground exposes a REST API used by the frontend. These are also
available to external tools:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/compile-and-load` | POST | Compile P4 source and load pipeline |
| `/api/write` | POST | Install table entries (P4Runtime WriteRequest JSON) |
| `/api/read?table_id=N` | GET | Read table entries (0 = all tables) |
| `/api/packet` | POST | Inject packet (`ingress_port` + `payload_hex`) |
| `/api/pipeline` | GET | Check if a pipeline is loaded |

## Limitations

- **No MODIFY** for table entries — delete and re-add.
- **No default action changes** via UI.
- **Single shared state** — all browser tabs share one simulator.
- **No persistence** — restarting the server clears everything.
- **No packet history** — sending another packet overwrites the previous
  result.
- **Monaco editor requires internet** (loaded from CDN).

See [LIMITATIONS.md](https://github.com/smolkaj/4ward/blob/main/docs/LIMITATIONS.md)
for the full list.
