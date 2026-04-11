# bazel

Bazel infrastructure shared across the repo. The most important export
is the [`fourward_pipeline`](fourward_pipeline.bzl) rule for compiling
P4 programs with `p4c-4ward`, available to downstream consumers as
`@fourward//bazel:fourward_pipeline.bzl`.

Bzlmod dependencies are declared in [`../MODULE.bazel`](../MODULE.bazel);
the consumer-side presubmit check lives in
[`../bcr_test_module/`](../bcr_test_module/).
