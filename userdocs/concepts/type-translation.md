# Type Translation

P4 programs can annotate types with
[`@p4runtime_translation`](https://p4.org/p4-spec/p4runtime/main/P4Runtime-Spec.html#sec-translation-annotation)
to give them two representations:

- **P4Runtime (controller-facing)** — what the controller sends and receives.
  Can be a string (e.g. `"Ethernet0"`) or a fixed-width integer.
- **Dataplane** — the compact value the P4 program processes internally
  (e.g. a 9-bit port number).

4ward translates between these automatically at the P4Runtime API boundary.

## Example

SAI P4 declares port IDs as translated strings:

```p4
@p4runtime_translation("", string)
type bit<9> port_id_t;
```

A controller installs a table entry with `port = "Ethernet1"`. The
translator maps it to dataplane value `0x01`. When the entry is read back,
the translator maps `0x01` back to `"Ethernet1"`.

## Translation modes

| Mode | Behavior |
|------|----------|
| **Auto-allocate** (default) | P4Runtime values are assigned sequential dataplane values on first use (0, 1, 2, ...) |
| **Explicit** | Only pre-configured mappings accepted; unknown values rejected |
| **Hybrid** | Explicit pins for known values, auto-allocate for the rest |

Configure via `DeviceConfig.translations` in `SetForwardingPipelineConfig`:

```textproto
translations {
  type_name: "port_id_t"
  auto_allocate: true
  entries { sdn_str: "Ethernet0"  dataplane_value: "\x00" }
  entries { sdn_str: "Ethernet1"  dataplane_value: "\x01" }
}
```

Without explicit configuration, auto-allocation is used by default.

## Where translation happens

| Operation | Direction | What is translated |
|-----------|-----------|-------------------|
| **Write** (table entry, action profile) | P4RT → dataplane | Match fields, action params |
| **Read** (table entry, action profile) | Dataplane → P4RT | Match fields, action params |
| **PacketOut** | P4RT → dataplane | Metadata fields |
| **PacketIn** | Dataplane → P4RT | Metadata fields |
| **InjectPacket** | Either direction | Ingress port (`p4rt_ingress_port` oneof) |

Translation only applies to fields whose p4info `type_name` resolves to a
`@p4runtime_translation`-annotated type.

## Ports are special

Most translated types appear in table entries — match fields and action
parameters. Ports are different: they appear in hardcoded proto fields across
multiple messages (`InjectPacketRequest.ingress_port`,
`OutputPacket.egress_port`, `PacketIn`/`PacketOut` metadata, clone session
replicas).

4ward creates a `PortTranslator` when the architecture's port metadata field
uses a `type` (not `typedef`) with `@p4runtime_translation`. Stock
architectures use `typedef` — no port translation. Programs that need port
translation use a [forked architecture](architecture-modifications.md).

## Traces

When translation is available, [trace trees](traces.md) carry P4RT values
alongside dataplane values — port names on ingress/egress events, and
translated match fields and action params on table lookups. See
[P4RT enrichment](traces.md#p4rt-enrichment).
