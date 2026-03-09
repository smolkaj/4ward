#!/usr/bin/env bash
# Runs all linters:
#   - clang-tidy on C++ sources (via Bazel aspect)
#   - detekt on Kotlin sources
#
# Runs both even if the first fails, so you see all issues at once.
#
# Usage:
#   ./lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Both commands go through Bazel, which serializes them via its server lock.
rc=0

echo "Running clang-tidy..."
bazel build //p4c_backend/... --config=clang-tidy || rc=1

echo "Running detekt..."
bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/p4runtime,${REPO_ROOT}/e2e_tests,${REPO_ROOT}/cli" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config || rc=1

exit $rc
