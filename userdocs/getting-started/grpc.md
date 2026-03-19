# Getting Started: gRPC API

The gRPC API is how test infrastructure (DVaaS, sonic-pins) integrates with
4ward programmatically. Two services are exposed on the same port:

- **[P4Runtime](https://p4lang.github.io/p4runtime/spec/v1.4.0/P4Runtime-Spec.html)** — the standard P4Runtime gRPC API for
  pipeline management, table entries, and PacketIO.
- **DataplaneService** — 4ward-specific API for packet injection with trace
  trees.

## Start the server

```sh
bazel run //p4runtime:p4runtime_server
```

The server listens on port **9559** by default. Override with `--port`:

```sh
bazel run //p4runtime:p4runtime_server -- --port=50051
```

## Typical workflow

### 1. Load a pipeline

Compile your P4 program, then load it via `SetForwardingPipelineConfig`:

```protobuf
SetForwardingPipelineConfigRequest {
  device_id: 1
  action: VERIFY_AND_COMMIT
  config {
    p4info: <from compiled output>
    p4_device_config: <from compiled output>
  }
}
```

### 2. Install table entries

Use `Write` to install entries. The request follows the standard P4Runtime
spec:

```protobuf
WriteRequest {
  device_id: 1
  updates {
    type: INSERT
    entity {
      table_entry {
        table_id: <from p4info>
        match { field_id: 1  exact { value: "\x08\x00" } }
        action { action { action_id: <from p4info>  params { ... } } }
      }
    }
  }
}
```

### 3. Inject a packet

Use the **DataplaneService** `InjectPacket` RPC:

```protobuf
InjectPacketRequest {
  dataplane_ingress_port: 0
  payload: <raw bytes>
}
```

The response contains output packets and a full trace tree:

```protobuf
InjectPacketResponse {
  output_packets { dataplane_egress_port: 1  payload: <...> }
  trace { events { ... } packet_outcome { ... } }
}
```

### 4. Observe all results (optional)

`SubscribeResults` is a server-streaming RPC that delivers results from
*all* packet sources (InjectPacket, PacketOut, etc.):

```protobuf
// First message confirms the subscription is active.
SubscribeResultsResponse { active {} }
// Subsequent messages carry results.
SubscribeResultsResponse { result { input_packet { ... } output_packets { ... } trace { ... } } }
```

## Dual port encoding

When the pipeline uses
[`@p4runtime_translation`](../concepts/type-translation.md) on port types,
both representations appear in responses:

```protobuf
OutputPacket {
  dataplane_egress_port: 0        // raw port number
  p4rt_egress_port: "Ethernet1"   // P4Runtime name
}
```

You can also inject packets using P4Runtime port names:

```protobuf
InjectPacketRequest {
  p4rt_ingress_port: "Ethernet0"
  payload: <raw bytes>
}
```

## What's next

- **Reference** — complete [gRPC API reference](../reference/grpc.md) with all
  RPCs, message types, and error codes.
- **Type translation** — how [`@p4runtime_translation`
  types](../concepts/type-translation.md) flow through the API.
- **Trace trees** — understanding the [trace tree](../concepts/traces.md) in
  the response.
