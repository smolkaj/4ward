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

## Worktrees

Always work in a dedicated worktree. Never make changes directly in the main
tree. Rebase and squash when merging back.

## P4 spec compliance

4ward is a spec-compliant reference implementation. When implementing a
feature or resolving an ambiguity, consult the
[P4₁₆ Language Specification (v1.2.5)](https://p4.org/wp-content/uploads/sites/53/2024/10/P4-16-spec-v1.2.5.html).
Cite spec section numbers in comments on non-obvious semantics.

## Tool use

- Prefer `bazel build //...` and `bazel test //...` to direct invocations of
  `kotlinc`, `g++`, etc. The build is hermetic.
- Use `ibazel build //...` for any work that spans multiple edit/build cycles.
  Run it in the background at the start of a task so build errors surface
  immediately rather than after a full rebuild.
- Use `./format.sh` to format files, not manual edits.
- Use `./lint.sh` to lint all code (clang-tidy for C++, detekt for Kotlin).
  Fix all warnings before marking a task complete.
- Do not install anything with `apt`, `brew`, or `pip`; declare dependencies
  in `MODULE.bazel` instead.

## Testing discipline

Do not mark a task complete unless `bazel test //...` passes. A test that was
green before your change must still be green after it.
