# Architectures

4ward currently supports two P4 architectures: **v1model** and **PSA**. Each
defines a fixed pipeline of stages, a set of externs, and metadata structures.
Adding more architectures is straightforward — see
[Architecture Modifications](architecture-modifications.md).

## v1model

The BMv2 simple_switch architecture, originally designed to support P4_14
programs compiled to P4_16. Defined in
[v1model.p4](https://github.com/p4lang/p4c/blob/main/p4include/v1model.p4);
see the [simple_switch documentation](https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md)
for the canonical behavioral spec.

Six stages in fixed order:

```
MyParser → MyVerifyChecksum → MyIngress → MyEgress → MyComputeChecksum → MyDeparser
```

**Ports:** 9-bit by default (`bit<9>`). Drop port: 511 (`2^9 - 1`). CPU port:
510 (`2^9 - 2`, auto-enabled when `@controller_header` is present). Both can
be overridden via `--drop-port` and `--cpu-port` flags.

### Key metadata (`standard_metadata_t`)

| Field | Type | Description |
|-------|------|-------------|
| `ingress_port` | `bit<9>` | Input port (read-only) |
| `egress_spec` | `bit<9>` | Desired output port (set by ingress) |
| `egress_port` | `bit<9>` | Actual output port (read-only in egress) |
| `instance_type` | `bit<32>` | 0 = normal, 1 = ingress clone, 2 = egress clone |
| `mcast_grp` | `bit<16>` | Multicast group ID |
| `packet_length` | `bit<32>` | Packet byte count |

### Externs

**Forwarding and drops:**

- `mark_to_drop(standard_metadata)` — drop the packet

**Cloning, resubmit, recirculate:**

- `clone(CloneType, session_id)` — I2E or E2E clone
- `clone3(CloneType, session_id, data)` — clone with field list
- `resubmit(data)` — re-inject into ingress
- `recirculate(data)` — feed deparsed packet back to parser

Multiple calls use last-writer-wins. All produce
[trace tree forks](traces.md#forks).

**Checksums:**

- `verify_checksum(condition, data, checksum, algo)` — validate
- `update_checksum(condition, data, checksum, algo)` — compute
- `_with_payload` variants include the packet payload

**Hashing:**

- `hash(out result, algo, base, data, max)` — `result = base + hash(data) mod max`

**Logging:**

- `log_msg(msg)` / `log_msg(msg, data)` — debug output with `{}` placeholders

**Stateful:**

- `register<T>.read(out result, index)` / `.write(index, value)` — generic
  array storage
- `counter.count(index)` / `direct_counter.count()` — increment (fire-and-forget)
- `meter.execute_meter(index, out color)` — always returns GREEN (no real
  packet rates in the simulator)

## PSA

The Portable Switch Architecture — a more modern design with a cleaner
separation between ingress and egress. Defined in
[psa.p4](https://github.com/p4lang/p4-spec/blob/main/p4-16/psa/psa.p4);
see the [PSA specification](https://p4lang.github.io/p4-spec/docs/PSA.pdf)
for the canonical behavioral spec.

Two-pipeline design with separate ingress and egress:

```
IngressParser → Ingress → IngressDeparser → EgressParser → Egress → EgressDeparser
```

**Key difference from v1model:** egress re-parses the deparsed packet, so
header and metadata state does not persist across the ingress→egress boundary.

**Ports:** 32-bit by default (`bit<32>`). Drop port: `2^32 - 1`. CPU port:
`2^32 - 2` (when `@controller_header` is present).

### Key metadata

**Ingress output** (`psa_ingress_output_metadata_t`) — set by ingress to
control packet fate:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `drop` | `bool` | **true** | Must clear via `send_to_port()` or `multicast()` to forward |
| `egress_port` | `bit<32>` | - | Output port |
| `multicast_group` | `bit<16>` | - | Multicast group ID |
| `clone` | `bool` | false | I2E clone requested |
| `clone_session_id` | `bit<16>` | - | Clone session |
| `resubmit` | `bool` | false | Resubmit requested |

Note: **ingress drops by default** — you must explicitly call `send_to_port()`
to forward. This is opposite to v1model where packets forward by default.

### Externs

**Forwarding:**

- `send_to_port(ostd, port)` — clear drop, set egress port
- `multicast(ostd, group)` — clear drop, set multicast group
- `ingress_drop(ostd)` / `egress_drop(ostd)` — set drop flag

**Stateful:**

- `Register<T>.read(index) → T` / `.write(index, value)` — note: returns
  value directly (unlike v1model's `out` parameter)
- `Counter.count()` / `DirectCounter.count()` — fire-and-forget
- `Meter.execute(index) → color` — always GREEN
- `Random.read() → T` — random value in constructor-specified range

**Hashing:**

- `Hash.get_hash(data) → hash` — 1-arg form
- `Hash.get_hash(base, data, max) → (base + hash) mod max` — 3-arg form

Supported algorithms: `IDENTITY`, `CRC16`, `CRC32`, `ONES_COMPLEMENT16`.

**Checksum:**

- `InternetChecksum` — `clear()`, `add(data)`, `subtract(data)`, `get()`,
  `get_state()`, `set_state(value)`

**Other:**

- `Digest.pack(data)` — queues message to control plane (no-op in simulator)

## Limitations

Both architectures:

- **Meters always return GREEN.** No real packet rates in a simulator.
- **Counters are fire-and-forget.** Not readable from P4 logic (control-plane
  only via P4Runtime Read).
- **Digest is a no-op.** No control-plane receiver in the simulator.

See [LIMITATIONS.md](https://github.com/smolkaj/4ward/blob/main/docs/LIMITATIONS.md)
for the full list.
