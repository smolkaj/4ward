# cli

The standalone `4ward` command-line tool. Wraps the compiler + simulator
in a no-setup binary for quick iteration on P4 programs: compile, sim,
run, and multi-switch network simulation.

**Target:** `//cli:4ward`
**Start:** `bazel run //cli:4ward -- --help`
(or `alias 4ward='bazel run //cli:4ward --'`)

See [`docs/ENTRY_POINTS.md`](../docs/ENTRY_POINTS.md#cli) for a full
walkthrough and [`examples/tutorial.t`](../examples/tutorial.t) for a
hands-on tour.
