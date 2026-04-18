#!/usr/bin/env bash
# Runs all linters:
#   - formatting check (clang-format, buildifier, ktfmt)
#   - clang-tidy on C++ sources (via Bazel aspect)
#   - duplicate-srcs check across kt_jvm_library targets
#   - detekt on Kotlin sources
#
# Runs all checks even if an earlier one fails, so you see all issues at once.
#
# Set SKIP_CLANG_TIDY=1 to skip the clang-tidy step (CI sets this when no C++
# files were modified).
#
# Usage:
#   ./tools/lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

rc=0

echo "Checking formatting..."
"${REPO_ROOT}/tools/format.sh" --check || rc=1

if [[ "${SKIP_CLANG_TIDY:-}" == "1" ]]; then
  echo "Skipping clang-tidy (SKIP_CLANG_TIDY=1)."
else
  echo "Running clang-tidy..."
  bazel build //p4c_backend/... --config=clang-tidy || rc=1
fi

# TODO(buf-edition-2024): Re-enable buf lint/breaking once buf supports
# edition 2024. Tracked in https://github.com/smolkaj/4ward/pull/4.

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
