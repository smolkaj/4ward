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
identifier. A non-empty URI is unique per type — there is at most one type
with any given URI. It can be used in `DeviceConfig.TypeTranslation` entries
as a convenience, but is rejected if it matches zero or multiple types.

### SAI P4 convention: empty URI

SAI P4 uses an empty URI (`""`) for all its translated types, effectively
leaving the URI unset:

```p4
@p4runtime_translation("", string)
type bit<9> port_id_t;

@p4runtime_translation("", string)
type bit<10> vrf_id_t;

@p4runtime_translation("", string)
type bit<16> nexthop_id_t;
```

An empty URI is a special case: it does not uniquely identify a type, so it
cannot be used for lookup. 4ward handles this by always keying translation
tables by type name, not URI. When configuring translations via
`DeviceConfig`, use `type_name` (not `type_uri`) for programs with empty
URIs.

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

- `InputPacket.dataplane_ingress_port` / `OutputPacket.dataplane_egress_port` (DataplaneService)
- `PacketIn` / `PacketOut` metadata (P4RuntimeService)
- `CloneSessionEntry.replicas[].egress_port` (P4Runtime spec)
- `MulticastGroupEntry.replicas[].egress_port` (P4Runtime spec)

These fields are not dynamically typed — their port semantics are
built into the proto schema. The server needs to know the port type as a
**pipeline-wide property** to translate these fields.

The `PortTranslator` (a property of `TypeTranslator`) provides this. The
port type is a property of the architecture — it is determined at compile
time from the architecture's port metadata field and stored in the IR's
`Architecture.port_type_name`:

| Architecture | Port metadata field |
|---|---|
| v1model | `standard_metadata_t.ingress_port` |
| PSA | `psa_ingress_input_metadata_t.ingress_port` |

This field records the P4
[newtype](https://p4.org/wp-content/uploads/sites/53/2024/10/P4-16-spec-v1.2.5.html#sec-newtype)
used for ports (e.g. `port_id_t`), or is empty if ports use a bare
`bit<N>` or a `typedef` (which is just an alias, not a new type).

Stock architectures use `typedef` for ports (`typedef bit<9> PortId_t` in
v1model, `typedef bit<32> PortId_t` in PSA) — no port translation.
Programs that need port translation should use a forked architecture where
the port typedef is replaced with a `type` declaration and
`@p4runtime_translation`. A `PortTranslator` is created only when the port
type has `@p4runtime_translation`.

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

## P4RT-enriched traces

The simulator produces trace trees with raw dataplane values — port numbers
are `uint32`, table entry fields are in dataplane representation. When the
loaded pipeline uses `@p4runtime_translation`, the DataplaneService enriches
these traces with P4RT representations before returning them to gRPC callers.

### What gets enriched

Each trace message has an optional `p4rt_*` field alongside the dataplane
value. These are populated by `TraceEnricher` at the DataplaneService
boundary — the simulator never sets them.

| Trace message | Dataplane field | P4RT field | Source |
|---|---|---|---|
| `PacketIngressEvent` | `dataplane_ingress_port` (uint32) | `p4rt_ingress_port` (bytes) | `PortTranslator` |
| `OutputPacket` (in `PacketOutcome`) | `dataplane_egress_port` (uint32) | `p4rt_egress_port` (bytes) | `PortTranslator` |
| `CloneSessionLookupEvent` | `dataplane_egress_port` (uint32) | `p4rt_egress_port` (bytes) | `PortTranslator` |
| `TableLookupEvent` | `matched_entry` (TableEntry) | `p4rt_matched_entry` (TableEntry) | `TypeTranslator` |

Port fields are enriched when the pipeline has a `PortTranslator` (i.e. the
architecture uses a `type` with `@p4runtime_translation` for its port
metadata fields). Table entries are enriched when any match field or action
param uses a translated type.

### Example

With a SAI P4 pipeline where ports are `@p4runtime_translation("", string)`,
a trace might look like:

```textproto
events {
  packet_ingress {
    dataplane_ingress_port: 1
    p4rt_ingress_port: "Ethernet0"
  }
}
events {
  table_lookup {
    table_name: "forwarding"
    hit: true
    matched_entry {
      # ... action param value: "\000" (dataplane)
    }
    p4rt_matched_entry {
      # ... action param value: "Ethernet1" (P4Runtime)
    }
    action_name: "forward"
  }
}
packet_outcome {
  output {
    dataplane_egress_port: 0
    p4rt_egress_port: "Ethernet1"
    payload: "..."
  }
}
```

A full example is pinned in the
[enriched trace golden file](../p4runtime/enriched_trace.golden.txtpb).

### When enrichment does not apply

- **Stock v1model / PSA programs** — ports use `typedef bit<N>` (not a
  newtype), so there is no `PortTranslator`. Port fields remain unenriched.
  Table entries are unenriched unless the program declares translated types
  on match fields or action params.
- **CLI (`4ward sim`) and web playground** — these inject packets directly
  through the simulator, not the DataplaneService. Traces from these paths
  have no P4RT enrichment.
- **Table misses** — `p4rt_matched_entry` is only populated on table hits.
- **Unmapped ports** — if a dataplane port has no reverse mapping (e.g. the
  controller never allocated it), the `p4rt_*` port field is left empty.
