#!/usr/bin/env bash
# Runs all linters:
#   - clang-tidy on C++ sources (via Bazel aspect)
#   - duplicate-srcs check across kt_jvm_library targets
#   - detekt on Kotlin sources
#
# Runs all checks even if an earlier one fails, so you see all issues at once.
#
# Usage:
#   ./lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Both commands go through Bazel, which serializes them via its server lock.
rc=0

echo "Running clang-tidy..."
bazel build //p4c_backend/... --config=clang-tidy || rc=1

echo "Checking for source files compiled into multiple kt_jvm_library targets..."
if targets=$(bazel query 'kind("kt_jvm_library", //...)' 2>/dev/null); then
  duplicates=$(
    echo "$targets" | while read -r t; do
      bazel query "labels(srcs, $t)" 2>/dev/null
    done | sort | uniq -d
  )
  if [[ -n "$duplicates" ]]; then
    echo "ERROR: Source files compiled into multiple kt_jvm_library targets:"
    echo "$duplicates"
    rc=1
  fi
else
  echo "WARNING: could not enumerate kt_jvm_library targets; skipping duplicate-srcs check"
  echo "$targets"
  rc=1
fi

echo "Running detekt..."
bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/p4runtime,${REPO_ROOT}/e2e_tests,${REPO_ROOT}/cli" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config || rc=1

exit $rc
