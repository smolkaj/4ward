#!/bin/bash
# Developer task runner. Run `./dev.sh help` to see available commands.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

cmd_help() {
  cat <<'EOF'
Usage: ./dev.sh <command>

Commands:
  build           Build everything
  test            Run all tests
  fmt             Auto-format all files
  lint            Run all linters
  coverage        Run tests with coverage, open report
  help            Show this help
EOF
}

cmd_build() {
  bazel build //... "$@"
}

# Extra args are passed through, e.g. `./dev.sh test //simulator:BitVectorTest`.
cmd_test() {
  bazel test //... "$@"
}

cmd_fmt() {
  exec "${REPO_ROOT}/format.sh"
}

cmd_lint() {
  exec "${REPO_ROOT}/lint.sh"
}

cmd_coverage() {
  exec "${REPO_ROOT}/coverage.sh" "$@"
}

# Dispatch.
case "${1:-help}" in
  build)        shift; cmd_build "$@" ;;
  test)         shift; cmd_test "$@" ;;
  fmt|format)   shift; cmd_fmt "$@" ;;
  lint)         shift; cmd_lint "$@" ;;
  coverage|cov) shift; cmd_coverage "$@" ;;
  help|--help|-h) cmd_help ;;
  *)
    echo "Unknown command: $1" >&2
    echo "Run './dev.sh help' for available commands." >&2
    exit 1
    ;;
esac
