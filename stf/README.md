# stf

Parser and runner for the [STF test format](https://github.com/p4lang/p4c/blob/main/backends/bmv2/testing/stf/README.md)
(the `packet`/`expect`/`add` format originating with BMv2's
`simple_switch`). 4ward supports STF as a first-class format for driving
the simulator.

**Target:** `//stf` (library). Used by `//cli` (the `4ward sim` / `run`
subcommands) and by every feature test under `e2e_tests/`. See
[`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#stf-runner) for a
walkthrough.
