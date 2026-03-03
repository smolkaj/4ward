#!/bin/bash
# Runs all linters:
#   - clang-tidy on C++ sources (via Bazel aspect)
#   - detekt on Kotlin sources
#
# Both linters run in parallel; the script exits non-zero if either fails.
#
# Usage:
#   ./lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

rc=0
CLANG_TIDY_LOG=$(mktemp)
trap 'rm -f "$CLANG_TIDY_LOG"' EXIT

# Run clang-tidy via Bazel aspect in the background.
echo "Running clang-tidy..."
bazel build //p4c_backend/... --config=clang-tidy >"$CLANG_TIDY_LOG" 2>&1 &
CLANG_TIDY_PID=$!

# Run detekt in the foreground.
echo "Running detekt..."
if ! bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/e2e_tests" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config; then
  rc=1
fi

# Wait for clang-tidy and print its output.
if ! wait "$CLANG_TIDY_PID"; then
  rc=1
fi
cat "$CLANG_TIDY_LOG"

exit $rc
