# Simple Test Framework (STF)

Parser and runner for the [STF test format](https://github.com/p4lang/p4c/blob/main/backends/bmv2/testing/stf/README.md) —
the test format from BMv2 (the Behavioral Model reference simulator),
which 4ward supports as a first-class way to drive the simulator. Used
by the `4ward sim` command-line tool and by every end-to-end test under
`e2e_tests/`. See [`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#stf-runner).
