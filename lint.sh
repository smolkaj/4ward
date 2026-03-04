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

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

# On macOS, clang-tidy comes from Homebrew LLVM whose version differs from
# Apple's system clang.  We must also compile with Homebrew's clang so the
# toolchain's built-in include directories match what clang-tidy expects.
# However, Homebrew LLVM's libc++ headers reference symbols absent from
# Apple's system libc++ (e.g. __hash_memory), causing link failures for
# tools that Bazel builds as part of the analysis.  The workaround is to
# compile with Homebrew's clang but use Apple's libc++ headers via
# -nostdinc++ and an explicit -isystem pointing at the SDK's C++ headers.
TIDY_FLAGS=()
if [[ "$OSTYPE" == darwin* ]]; then
  LLVM_PREFIX="$(brew --prefix llvm 2>/dev/null || true)"
  if [[ -x "${LLVM_PREFIX:-}/bin/clang-tidy" ]]; then
    MACOS_SDK="$(xcrun --show-sdk-path)"
    TIDY_FLAGS+=(
      --repo_env=CC="${LLVM_PREFIX}/bin/clang"
      --action_env=PATH="${LLVM_PREFIX}/bin:${PATH}"
      --cxxopt=-nostdinc++
      --host_cxxopt=-nostdinc++
      "--cxxopt=-isystem${MACOS_SDK}/usr/include/c++/v1"
      "--host_cxxopt=-isystem${MACOS_SDK}/usr/include/c++/v1"
    )
  fi
fi

# Both commands go through Bazel, which serializes them via its server lock.
rc=0

echo "Running clang-tidy..."
bazel build //p4c_backend/... --config=clang-tidy "${TIDY_FLAGS[@]}" || rc=1

echo "Running detekt..."
bazel run //:detekt -- \
  --input "${REPO_ROOT}/simulator,${REPO_ROOT}/e2e_tests" \
  --config "${REPO_ROOT}/detekt.yml" \
  --build-upon-default-config || rc=1

exit $rc
