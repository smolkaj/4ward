# DVaaS Status

Current status of the 4ward-to-DVaaS integration work on branch `dvaas-poc-e2e`.

## Goal

Run DVaaS `ValidateDataplane` end-to-end against 4ward using:

- one 4ward instance as the SUT
- one 4ward instance as the control switch
- back-to-back dataplane connectivity
- native P4Runtime PacketIO semantics driven by a configurable CPU port

## What is implemented

### 4ward runtime

- Added a generic configurable CPU port to the P4Runtime server via `--cpu_port`.
- `PacketOut` injection uses the configured CPU port as ingress.
- Egress to the configured CPU port produces `PacketIn`.
- CPU-port egress is copied to `PacketIn` but remains visible in normal dataplane
  egress.
- Added a PoC back-to-back dataplane path so a control-switch 4ward instance can
  forward traffic through a peer SUT 4ward instance.

Relevant files:

- `p4runtime/P4RuntimeService.kt`
- `p4runtime/P4RuntimeServer.kt`
- `p4runtime/PacketHeaderCodec.kt`

### PoC P4 program

- Added a minimal v1model PoC program for DVaaS bring-up.
- Includes `@p4runtime_translation_mappings` for `CPU`, `DROP`, and two front-panel
  ports.
- Includes SAI-like `packet_in` / `packet_out` controller metadata.

Relevant files:

- `e2e_tests/dvaas_poc/dvaas_poc.p4`
- `e2e_tests/dvaas_poc/BUILD.bazel`

### DVaaS harness

- Added a temporary overlay that is copied into a pinned `sonic-pins` checkout.
- Added a minimal backend with hard-coded SAI-like knowledge for punt-all behavior.
- Added a runner script that:
  - builds 4ward and the PoC pipeline
  - starts two live 4ward servers
  - clones a pinned `sonic-pins`
  - builds the overlay helper
  - runs `ValidateDataplane`
- Added a dedicated GitHub Actions workflow to run the PoC on Ubuntu.

Relevant files:

- `tools/dvaas_overlay/validate_dataplane_poc.cc`
- `tools/dvaas_overlay/BUILD.bazel`
- `tools/run_dvaas_poc.sh`
- `.github/workflows/dvaas-poc.yml`

## Current blocker

The integration is not green yet.

The 4ward side builds and starts successfully on Ubuntu. The current failing step is
the temporary `sonic-pins` overlay build used to drive DVaaS.

Latest resolved blockers:

- removed local-only Bazel `--config=throttle` assumptions
- isolated the `sonic-pins` Bazel invocation from this repo's rc files
- forced the temporary overlay build to use C++17
- fixed the gNMI include path to match `sonic-pins` header layout

This means the remaining failures are now in the DVaaS overlay/helper path, not in
native 4ward CPU-port handling or PoC server startup.

## Intentional shortcuts and hacks

These were chosen to get a real end-to-end proof loop running before moving to the
final SAI design.

### Custom PoC P4

The current proof uses `e2e_tests/dvaas_poc/dvaas_poc.p4`, not proper SAI P4 without
macros.

Why:

- it isolates PacketIO and mirror-testbed semantics first
- it is much easier to debug than full SAI P4

### Temporary sonic-pins overlay

The DVaaS proof currently depends on copying a custom helper into a temporary pinned
`sonic-pins` checkout.

Why:

- it drives the real DVaaS `ValidateDataplane` code path
- it avoids needing an upstream DVaaS API change before proving feasibility

### Hard-coded backend knowledge

The temporary backend in `tools/dvaas_overlay/validate_dataplane_poc.cc` is explicitly
aware of SAI-like concepts such as punt-all behavior and fixed port/interface mapping.

Why:

- this is acceptable for the current backend shape
- it avoids premature abstraction before the integration is known-good

### Fake gNMI

The helper uses a tiny fake gNMI server that reports a fixed OpenConfig interface set.

Why:

- DVaaS only needs enough interface metadata to map the two front-panel ports in the
  PoC

### Packet/spec overrides instead of p4-symbolic

The helper currently uses `packet_test_vector_override` and `specification_override`
instead of real symbolic packet generation.

Why:

- the first proof target is PacketIO and dataplane traversal correctness
- `p4-symbolic` is planned after the PoC plumbing is stable

## Known non-final design choices

- The back-to-back control-switch path is currently implemented inside the P4Runtime
  service using the peer dataplane stub. It is good enough for the PoC but is not yet
  a general-purpose testbed abstraction.
- The proof currently relies on CI for the Ubuntu end-to-end run because the local
  machine's macOS environment cannot reliably build the DVaaS side:
  - `clang-tidy` is not installed locally
  - the local macOS Bazel/C++ environment hits Xcode/toolchain problems for the
    `sonic-pins` build

## Why work paused here

Work paused at the current synchronization point, not because the integration is done.

The remaining failures were still being driven down in the Ubuntu CI loop. At the time
this document was written:

- 4ward runtime changes were in place
- the PoC runner and workflow existed
- repeated CI iterations had moved the failure frontier from:
  - Bazel config leakage
  - C++ standard mismatch
  - wrong generated-header include path
  to the remaining overlay/helper build path

## Next steps

1. Finish getting the temporary DVaaS overlay helper to build and run on Ubuntu.
2. Get one green `ValidateDataplane` PoC run against two live 4ward instances.
3. Replace the PoC P4 with proper SAI P4 without macros, using the modified
   `v1model.p4`.
4. Replace manual packet/spec overrides with `p4-symbolic`.
5. Reassess the current mirror-testbed plumbing and refactor if a cleaner native shape
   is possible.
