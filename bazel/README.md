# Bazel

Bazel infrastructure shared across the repo. The headline export is the
[`fourward_pipeline`](fourward_pipeline.bzl) rule for compiling P4
programs with `p4c-4ward`, consumable as
`@fourward//bazel:fourward_pipeline.bzl`.
