#!/usr/bin/env bash
# Verifies that every checked-in .bzl file is declared as a src of some
# bzl_library target. Catches the common mistake of adding a new .bzl
# without a matching BUILD entry.
#
# Running `bzl_library` targets for every .bzl file is the google3
# convention; it makes the Starlark dep graph explicit and is a
# prerequisite for Stardoc. Without this check the convention silently
# erodes over time.
#
# Exits 1 if any .bzl file is uncovered.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

tracked=$(git ls-files '*.bzl' | sort)
# bzl_library srcs come back as labels (//pkg:file.bzl); strip the "//"
# and colon to match `git ls-files` paths.
covered=$(
  bazel query 'labels("srcs", kind(bzl_library, //...))' --output=label 2>/dev/null \
    | sed 's|^//||; s|:|/|' \
    | sort
)

missing=$(comm -23 <(echo "$tracked") <(echo "$covered"))
if [[ -n "$missing" ]]; then
  echo "ERROR: .bzl files not covered by any bzl_library target:" >&2
  printf '  %s\n' $missing >&2
  echo >&2
  echo "Add a bzl_library target in the file's package. See" >&2
  echo "bazel/BUILD.bazel for an example." >&2
  exit 1
fi
