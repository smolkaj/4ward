# DataplaneService Port Encoding

**Status: proposal**

## Goal

Make the DataplaneService ergonomic for both simulator-level tooling (which
thinks in dataplane port numbers) and P4Runtime-level tooling (which thinks in
P4RT port IDs). Today, only dataplane port numbers (`uint32`) are supported,
forcing P4Runtime clients to convert back and forth.

## Background

Ports have two representations in the P4Runtime world:

- **Dataplane ports** — `uint32` values matching `standard_metadata.ingress_port`
  / `egress_port`. This is what the simulator operates on internally.
- **P4RT port IDs** — opaque `bytes` whose encoding depends on the P4 program's
  `@p4runtime_translation` annotation on the port type. Could be a numeric
  encoding (`bit<32>`), a string encoding (`sdn_string`), or any other `bytes`
  value. The `TypeTranslator` maps between the two representations based on the
  loaded pipeline. If a P4 program does not use `@p4runtime_translation` on its
  port type, the two encodings are identical — the P4RT port ID is just the
  dataplane port number in its native binary encoding.

The DataplaneService currently uses `uint32` ports everywhere (via
`simulator.proto`'s `InputPacket`/`OutputPacket`). This is natural for
STF tests and simulator-level tooling, but awkward for DVaaS and other
P4Runtime-oriented consumers that pass port IDs as opaque bytes.

## Requirements

1. **Accept either port encoding in requests.** Callers should be able to
   specify ingress ports as dataplane `uint32` or P4RT `bytes`, whichever is
   natural for their context.

2. **Provide both port encodings in responses.** Output packets should include
   both the dataplane port number and the P4RT port ID, so consumers in either
   world can read ports without conversion.

3. **P4RT port encoding is `bytes`.** The P4Runtime spec defines port IDs as
   opaque bytes — the actual encoding depends on `@p4runtime_translation`. The
   DataplaneService must not assume any particular encoding (not string, not
   fixed-width integer).

4. **Dataplane ports remain available.** Simulator-level tooling (STF runner,
   trace tree tests, BMv2 differential tests) should continue to work with
   `uint32` ports without needing a loaded pipeline or translation table.

5. **P4RT ports require a loaded pipeline.** The mapping between dataplane and
   P4RT ports depends on `@p4runtime_translation` in the loaded pipeline. The
   server should reject P4RT port input if no pipeline is loaded (or if the
   port doesn't map). P4RT port output is only populated when translation is
   available.

6. **`simulator.proto` is unchanged.** `InputPacket`/`OutputPacket` in
   `simulator.proto` remain `uint32` — they are internal types shared across
   the simulator, trace trees, and STF infrastructure. The DataplaneService
   defines its own request/response types at the API boundary.

## Scope

This change affects `p4runtime/dataplane.proto` and `DataplaneService.kt`. The
simulator, trace trees, STF runner, and P4RuntimeService are not affected.

On the sonic-pins side, the local copy of `dataplane.proto` in `fourward/`
adopts the same schema, and `FourwardBackend` drops its manual port conversion
code.
