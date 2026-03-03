# Contributing to 4ward

Thanks for your interest in 4ward! Contributions are welcome.

## The best kind of contribution

The p4c STF corpus is our backlog. The most valuable contributions are:
**"make this STF test pass"**. These are naturally well-scoped, test-driven,
and self-contained. Pick a failing test, implement what it needs, open a PR.

Run `bazel test //tests/stf/...` to see which tests currently pass and fail.

## Getting started

```sh
# Clone and build.
git clone https://github.com/yourorg/4ward
cd 4ward
bazel build //...

# Run all tests.
bazel test //...
```

Hermetic Bazel builds work on macOS and Ubuntu. You need a C++20 compiler and
Bazel 9+ (or [Bazelisk](https://github.com/bazelbuild/bazelisk), recommended).

## Making changes

1. Fork the repository and create a branch.
2. Make your change. All new behaviour must be covered by a test.
3. Run `bazel test //...`. Everything must be green.
4. Run `bazel run //:buildifier` to format BUILD files.
5. Open a **draft** PR with a clear description of what and why.

## Style

- **Kotlin**: [Google Kotlin style guide](https://google.github.io/styleguide/kotlinguide.html)
- **C++**: [Google C++ style guide](https://google.github.io/styleguide/cppguide.html)
- **Proto**: `snake_case` field names, enum values prefixed with the type name.
- **Commit messages**: focus on *why*, not *what*. The diff shows the what.

## Proto stability

Do not remove or renumber fields in `ir.proto` or `sim.proto`. Add new fields
instead. Removal requires a deprecation period and a version bump.

## A note on performance

4ward is a correctness and observability tool, not a performance tool. Please do
not submit optimisations that trade readability or correctness for speed. If you
want a fast P4 data plane, BMv2 or DPDK are the right tools.
