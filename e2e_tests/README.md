# e2e_tests

End-to-end tests that exercise the full 4ward stack — compiler, simulator,
P4Runtime, and everything in between. Everything under this folder is
`testonly` (see `package(default_testonly = True)` at the top of each
`BUILD.bazel`); production code is not allowed to depend on it.

Most tests consume [`//stf`](../stf/) to drive the simulator from `.stf`
files. See [`docs/TESTING_STRATEGY.md`](../docs/TESTING_STRATEGY.md) for
the overall testing approach.
