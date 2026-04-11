# Bazel Central Registry (BCR) Test Module

A minimal Bzlmod module that depends on `@fourward` via
`local_path_override`, exercising the same resolution path 
[BCR](https://registry.bazel.build/) consumers use. It serves two purposes:

- **BCR presubmit validation.** Referenced by `bcr_test_module:` in the
  fourward BCR entry's `presubmit.yml`. BCR's CI builds `@fourward//...`
  from here to verify the module is consumable as a dependency.

- **Regression check in our own CI.** The `bcr-consumer-check` job in
  [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) builds from
  this module on every PR, catching "works as root, broken as dep" bugs
  before they reach the BCR submission PR — things like dev-dep leaks,
  `git_override`s that don't apply to non-root modules, or maven
  namespace conflicts.

## Using @fourward as a consumer

Copy [`MODULE.bazel`](MODULE.bazel) as a starting point for your own
project. The `known_contributing_modules` bless is only needed if you
pull in `@fourward//p4runtime:p4runtime_lib` (see the comment in that
file for why); otherwise the `bazel_dep(name = "rules_jvm_external", ...)`
and the `maven.install(...)` block can go away.

## Building locally

```sh
cd bcr_test_module
bazel build -- @fourward//... -@fourward//e2e_tests/... -@fourward//examples/...
```

The `e2e_tests/` and `examples/` exclusions mirror the
`bcr-consumer-check` CI job — they carry `dev_dependency` deps (like
`@behavioral_model`) that aren't visible to non-root consumers, and
they're not part of the public surface anyway.

This directory carries its own [`.bazelversion`](.bazelversion) pinning
Bazel 8.x, because the BCR release of `grpc@1.80.0` pulls
`rules_swift@2.5.0`, which conflicts with the `rules_swift@3.1.2` that
`bazel_tools` carries in Bazel 9. (When `@fourward` is the root module
its `grpc` `git_override` sidesteps this, but overrides don't apply to
non-root modules — which is the whole point of this test.) Make sure
you have [bazelisk](https://github.com/bazelbuild/bazelisk) installed
so the per-directory version pin is honored automatically.
