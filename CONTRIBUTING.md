# Contributing to 4ward

Hey, thanks for being here! Whether it's your first open-source PR or your
thousandth, you're welcome.

## The easiest way to contribute

Our backlog is literally a list of failing tests. The p4c project ships hundreds
of STF (Simple Test Framework) tests, and each one we can't pass yet is a
feature waiting to be built. Pick one, make it green, open a PR. That's it.

These contributions are great because they're **naturally well-scoped** — you
know exactly when you're done (the test passes), and you don't need to
understand the whole codebase to get started.

Run `bazel test //...` to see what's passing and what's not.

## Getting started

```sh
# Clone and build — that's really all there is to it.
git clone https://github.com/yourorg/4ward
cd 4ward
bazel build //...
bazel test //...
```

Builds are hermetic — Bazel grabs everything it needs. You just need a C++20
compiler and Bazel 9+ (or grab
[Bazelisk](https://github.com/bazelbuild/bazelisk) so you never think about
Bazel versions again). Works on macOS and Ubuntu.

## How we work

Most development happens by prompting AI coding agents — the human's job is
to steer and review, not to type every line. That's why the repo includes
[AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md): they give agents the
context they need to make good changes autonomously. You're welcome to work
this way too, or write code by hand — whatever you prefer.

Run `./dev.sh help` for a summary of available developer commands.

## Making changes

1. Fork the repo and create a branch.
2. Make your change. New behaviour needs a test (usually an STF test does the
   trick).
3. Run `bazel test //...` — everything should be green.
4. Run `./format.sh` to auto-format everything.
5. Open a **draft** PR explaining what you changed and why.

Don't worry about getting it perfect on the first try — that's what review is
for. CI will post a coverage report on your PR automatically.

## Style

We follow the Google style guides and let the formatters handle the details:

- **Kotlin**: [Google Kotlin style](https://google.github.io/styleguide/kotlinguide.html), enforced by ktfmt
- **C++**: [Google C++ style](https://google.github.io/styleguide/cppguide.html), enforced by clang-format
- **Proto**: `snake_case` fields, `UPPER_SNAKE_CASE` enum values with a type prefix
- **Commit messages**: tell us *why*, not *what* — the diff already shows the what

## Proto stability

`ir.proto` and `simulator.proto` are the contract between the backend and the
simulator, so treat field numbers like promises: never remove or renumber them.
Add new fields instead.

## On correctness

4ward is a spec-compliant reference implementation: it should behave exactly as
the [P4₁₆ spec](https://p4.org/wp-content/uploads/sites/53/2024/10/P4-16-spec-v1.2.5.html)
describes. When you're unsure about a language detail, check the spec. If the
spec is ambiguous, follow p4c's behaviour and document the ambiguity in a
comment citing the relevant section.

4ward is proudly not fast. It's correct, it's observable, and it's readable.
Please don't send PRs that trade any of those for speed — if you want a fast P4
data plane, BMv2 or DPDK are excellent choices.
