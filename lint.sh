#!/bin/bash
# Runs all linters:
#   - clang-tidy on C++ sources (requires compile_commands.json)
#   - detekt on Kotlin sources
#
# First-time setup for clang-tidy (and after adding/removing Bazel C++ targets):
#   bazel run @hedron_compile_commands//:refresh_all
#
# Then run this script:
#   ./lint.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ ! -f "${REPO_ROOT}/compile_commands.json" ]]; then
  echo "compile_commands.json not found. Generating via Bazel..." >&2
  bazel run @hedron_compile_commands//:refresh_all
fi

# Collect C++ sources owned by this repo (exclude third-party and generated).
SOURCES=()
while IFS= read -r f; do
  SOURCES+=("$f")
done < <(
  find "${REPO_ROOT}/p4c_backend" \
    -name "*.cpp" -o -name "*.cc" -o -name "*.h" \
  | sort
)

if [[ ${#SOURCES[@]} -gt 0 ]]; then
  echo "Running clang-tidy on ${#SOURCES[@]} file(s)..."
  run-clang-tidy -p "${REPO_ROOT}" "${SOURCES[@]}"
else
  echo "No C++ sources found, skipping clang-tidy." >&2
fi

# Run detekt on Kotlin sources.
echo "Running detekt..."
bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/e2e_tests" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config
