#!/usr/bin/env bash
# Generate project documentation: proto reference, Kotlin reference, C++ reference.
#
# Everything is built by Bazel; this script just copies the output to a local
# directory for browsing.
#
# Usage:
#   ./tools/docs.sh          # generate all docs
#   ./tools/docs.sh --open   # generate and open in browser

set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

bazel build //tools:docs

rm -rf docs-output
cp -r bazel-bin/tools/docs docs-output

echo "Documentation generated in docs-output/"

if [[ "${1:-}" == "--open" ]]; then
  open docs-output/index.html 2>/dev/null || xdg-open docs-output/index.html 2>/dev/null || true
fi
