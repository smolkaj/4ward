# E2E Tests

End-to-end tests exercising the full 4ward stack. Everything here is
`testonly` — production code cannot depend on it. Most tests use
[`//stf`](../stf/) to drive the simulator from `.stf` files. See
[`docs/TESTING_STRATEGY.md`](../docs/TESTING_STRATEGY.md).
