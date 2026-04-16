#!/usr/bin/env bash
# Runs all linters:
#   - bzl_library coverage check (every .bzl file has a bzl_library target)
#   - clang-tidy on C++ sources (via Bazel aspect)
#   - detekt on Kotlin sources
#
# Runs all checks even if one fails, so you see all issues at once.
#
# Usage:
#   ./lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# All commands go through Bazel, which serializes them via its server lock.
rc=0

echo "Checking bzl_library coverage..."
"${REPO_ROOT}/tools/check-bzl-library.sh" || rc=1

echo "Running clang-tidy..."
bazel build //p4c_backend/... --config=clang-tidy || rc=1

echo "Running detekt..."
bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/p4runtime,${REPO_ROOT}/e2e_tests,${REPO_ROOT}/cli" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config || rc=1

exit $rc
