# 4ward — Claude Instructions

See [AGENTS.md](AGENTS.md) for the general agent guide. This file adds
Claude-specific notes.

## Commit messages

Focus on *why* the change is being made and what problem it solves. Avoid
restating what the diff already shows. Reference the STF test being fixed where
applicable.

## Code comments

Write self-explanatory code. Add a comment when the code deviates from the
obvious approach, works around a non-obvious constraint, or implements a
subtle P4 spec requirement. Include spec references (section numbers, GitHub
issues) where helpful. Do not add comments that merely restate the code.

## Pull requests

Open PRs in draft mode (`gh pr create --draft`). Rebase onto `origin/main`
before submitting. The description should explain motivation and approach,
not list every changed line.

## Tool use

- Prefer `bazel build //...` and `bazel test //...` to direct invocations of
  `kotlinc`, `g++`, etc. The build is hermetic.
- Use `./format.sh` to format files, not manual edits.
- Use `./lint.sh` to lint all code (clang-tidy for C++, detekt for Kotlin).
  Fix all warnings before marking a task complete.
- Do not install anything with `apt`, `brew`, or `pip`; declare dependencies
  in `MODULE.bazel` instead.

## Testing discipline

Do not mark a task complete unless `bazel test //...` passes. A test that was
green before your change must still be green after it.
