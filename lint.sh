#!/bin/bash
# Runs all linters:
#   - clang-tidy on C++ sources (requires compile_commands.json)
#   - detekt on Kotlin sources
#
# Both linters run in parallel; the script exits non-zero if either fails.
#
# First-time setup for clang-tidy (and after adding/removing Bazel C++ targets):
#   bazel run @hedron_compile_commands//:refresh_all
#
# Then run this script:
#   ./lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ ! -f "${REPO_ROOT}/compile_commands.json" ]]; then
  echo "compile_commands.json not found. Generating via Bazel..." >&2
  if ! bazel run @hedron_compile_commands//:refresh_all; then
    echo "Failed to generate compile_commands.json." >&2
    exit 1
  fi
fi

# Only .cpp/.cc — headers are already checked via HeaderFilterRegex in .clang-tidy.
SOURCES=()
while IFS= read -r f; do
  SOURCES+=("$f")
done < <(
  find "${REPO_ROOT}/p4c_backend" -type f \( -name "*.cpp" -o -name "*.cc" \) | sort
)

rc=0
CLANG_TIDY_LOG=$(mktemp)
trap 'rm -f "$CLANG_TIDY_LOG"' EXIT

# Run clang-tidy in the background so detekt can start immediately.
if [[ ${#SOURCES[@]} -gt 0 ]]; then
  echo "Running clang-tidy on ${#SOURCES[@]} file(s)..."
  run-clang-tidy -p "${REPO_ROOT}" -quiet "${SOURCES[@]}" >"$CLANG_TIDY_LOG" 2>&1 &
  CLANG_TIDY_PID=$!
else
  echo "No C++ sources found, skipping clang-tidy." >&2
  CLANG_TIDY_PID=
fi

# Run detekt in the foreground.
echo "Running detekt..."
if ! bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/e2e_tests" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config; then
  rc=1
fi

# Wait for clang-tidy and print its output.
if [[ -n "${CLANG_TIDY_PID:-}" ]]; then
  if ! wait "$CLANG_TIDY_PID"; then
    rc=1
  fi
  cat "$CLANG_TIDY_LOG"
fi

exit $rc
