# Type Translation

> Audience: developers integrating 4ward — DVaaS, sonic-pins, or anyone
> consuming the P4Runtime or Dataplane gRPC APIs.

## What is type translation?

P4 programs can annotate types with `@p4runtime_translation` to give them
two representations:

- **P4Runtime (controller-facing)** — the value the controller sends and
  receives. Can be a string (`sdn_string`, e.g. `"Ethernet0"`) or a
  fixed-width bitstring (`sdn_bitwidth`, e.g. a 32-bit integer).
- **Dataplane** — the value the P4 program processes internally (e.g. a
  9-bit `bit<9>` port number).

Example from SAI P4:

```p4
@p4runtime_translation("", string)
type bit<9> port_id_t;
```

Here `port_id_t` has a P4Runtime representation of an opaque string (like
`"Ethernet0"`) and a dataplane representation of 9-bit unsigned integers.

The `TypeTranslator` in 4ward maps between these representations
bidirectionally. It is configured at pipeline load time from the p4info
and optional `DeviceConfig.TypeTranslation` entries.

## How types are identified

Each translated type is identified by its **fully qualified type name**
from `p4info.type_info.new_types` (e.g. `port_id_t`, `vrf_id_t`). This is
the primary key for translation tables.

The `@p4runtime_translation` URI (e.g. `"test.port_id"`) is a secondary
identifier. It can be used in `DeviceConfig.TypeTranslation` entries as a
convenience, but must resolve to exactly one type in the p4info. If
ambiguous, the server rejects it with an error.

### SAI P4 convention

SAI P4 uses an empty URI (`""`) for all its translated types:

```p4
@p4runtime_translation("", string)
type bit<9> port_id_t;

@p4runtime_translation("", string)
type bit<10> vrf_id_t;

@p4runtime_translation("", string)
type bit<16> nexthop_id_t;
```

Because the URI is shared, it cannot disambiguate types. 4ward handles
this by keying translation tables by type name, not URI. When configuring
translations via `DeviceConfig`, use `type_name` (not `type_uri`) for SAI
P4 programs.

## Translation modes

Each type supports three modes, configured via `DeviceConfig.TypeTranslation`:

| Mode | `auto_allocate` | `entries` | Behavior |
|------|----------------|-----------|----------|
| **Auto-allocate** | `true` | empty | P4Runtime values assigned sequential dataplane values on first use (0, 1, 2, ...) |
| **Explicit** | `false` | non-empty | Only pre-configured mappings accepted; unknown values rejected |
| **Hybrid** | `true` | non-empty | Explicit pins for known values, auto-allocate for the rest; auto-allocator skips reserved dataplane values |

When no `TypeTranslation` is provided for a type, auto-allocation is used
by default.

## Configuring translations

Translations are configured via `DeviceConfig.translations` in
`SetForwardingPipelineConfig`:

```proto
message TypeTranslation {
  oneof type {
    string type_name = 4;  // Preferred: "port_id_t"
    string type_uri = 1;   // Convenience: must be unambiguous
  }
  repeated TranslationEntry entries = 2;
  bool auto_allocate = 3;
}
```

Example (explicit port mappings):

```textproto
translations {
  type_name: "port_id_t"
  auto_allocate: true
  entries { sdn_str: "Ethernet0"  dataplane_value: "\x00" }
  entries { sdn_str: "Ethernet1"  dataplane_value: "\x01" }
}
```

## Where translation happens

The `TypeTranslator` translates values at the P4Runtime API boundary:

| P4Runtime operation | Direction | What is translated |
|---|---|---|
| **Write** (table entry, action profile member) | P4RT → dataplane | Match field values, action param values |
| **Read** (table entry, action profile member) | Dataplane → P4RT | Match field values, action param values |
| **PacketOut** | P4RT → dataplane | Metadata field values |
| **PacketIn** | Dataplane → P4RT | Metadata field values (lenient: unmapped values pass through) |

Translation only applies to fields whose `type_name` in the p4info resolves
to a `@p4runtime_translation`-annotated type. Fields without a translated
type are passed through unchanged.

## Why ports are special

Most translated types appear only in dynamically-typed table entry fields
— match fields and action params — where the `TypeTranslator` discovers
the type from p4info field-level metadata. No special handling is needed.
In [SAI P4](https://github.com/sonic-net/sonic-pins/tree/main/sai_p4),
for example, VRF IDs, nexthop IDs, and router interface IDs are all
translated this way.

**Ports are different.** They appear in hardcoded proto fields across
multiple messages:

- `InputPacket.ingress_port` / `OutputPacket.egress_port` (DataplaneService)
- `PacketIn` / `PacketOut` metadata (P4RuntimeService)
- `CloneSessionEntry.replicas[].egress_port` (P4Runtime spec)
- `MulticastGroupEntry.replicas[].egress_port` (P4Runtime spec)

These fields are not dynamically typed — their port semantics are
built into the proto schema. The server needs to know the port type as a
**pipeline-wide property** to translate these fields.

The `PortTranslator` (a property of `TypeTranslator`) provides this. It is
derived at pipeline load time from `controller_packet_metadata` in the
p4info, when available: any metadata field whose
[`type_name`](https://github.com/p4lang/p4runtime/blob/main/proto/p4/config/v1/p4info.proto#L453)
resolves to a `@p4runtime_translation`-annotated type identifies the port
type. If the P4 program has no `controller_packet_metadata` (no
`@controller_header`), port translation is unavailable and the
DataplaneService operates with dataplane ports only.

## Dual port encoding in the DataplaneService

The DataplaneService supports **dual port encoding**: both the dataplane
port number (`uint32`) and the P4Runtime port ID (`bytes`) in requests and
responses. See [designs/dataplane_port_encoding.md](../designs/dataplane_port_encoding.md)
for the full design.

- **Requests** (`InjectPacketRequest`): callers choose either
  `dataplane_ingress_port` or `p4rt_ingress_port` via a `oneof`.
- **Responses** (`OutputPacket`, `InputPacket`): both representations are
  populated when port translation is available.
- **No pipeline / no translation**: P4Runtime port fields are empty;
  dataplane ports work as before.
