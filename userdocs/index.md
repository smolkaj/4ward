<p align="center">
  <img src="assets/logo.svg" alt="4ward logo" width="160">
</p>

# 4ward

**A glass-box P4 simulator — trace every decision your packet makes.**

4ward is a spec-compliant [P4₁₆](https://p4.org/wp-content/uploads/sites/53/p4-spec/docs/P4-16-v1.2.4.html) reference simulator that produces **trace
trees**: a complete record of every parser transition, table lookup, action
execution, and branch decision a packet encounters. At non-deterministic
choice points (action selectors, clone, multicast), the trace forks — showing
all possible outcomes in a single pass.

## Pick your entry point

| Entry point | Description |
|---|---|
| **[Web Playground](getting-started/playground.md)** | Edit P4 in the browser, install table entries, send packets, step through traces visually. |
| **[CLI](getting-started/cli.md)** | Compile a P4 program, run an STF test, read the trace — all from the terminal. |
| **[gRPC API](getting-started/grpc.md)** | Load pipelines and inject packets programmatically via the DataplaneService and P4Runtime gRPC services. |

## Who is this for?

- **Network engineers** testing P4 programs — send a packet, see exactly what
  happened and why.
- **Platform teams** integrating a P4 simulator into test infrastructure
  (DVaaS, sonic-pins) via the gRPC API.
- **Students and researchers** learning P4 — the web playground lets you edit,
  compile, and trace P4 programs in the browser.

## Key features

- **Trace trees** — every event recorded, every fork explored.
  [Learn more](concepts/traces.md).
- **v1model and PSA** — two P4 architectures fully implemented, with support
  for architecture modifications like translated port types.
  [Learn more](concepts/architectures.md).
- **[P4Runtime](https://p4lang.github.io/p4runtime/spec/v1.5.0/P4Runtime-Spec.html) server** — spec-compliant gRPC server
  with full arbitration, table management, PacketIO, and clone/multicast
  support.
- **Type translation** — `@p4runtime_translation` types (string port names,
  translated IDs) flow through the entire stack, including traces.
  [Learn more](concepts/type-translation.md).
- **Web playground** — visual pipeline diagrams, animated trace playback,
  packet dissection. [Try it](getting-started/playground.md).
