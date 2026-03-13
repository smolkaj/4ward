#!/usr/bin/env bash
# Generate C++ API reference docs. Usage: ./tools/docs.sh [--open]
set -euo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

bazel build //tools:docs

chmod -R u+w docs-output 2>/dev/null || true
rm -rf docs-output
cp -r bazel-bin/tools/html docs-output

echo "Documentation generated in docs-output/"
[[ "${1:-}" == "--open" ]] && { open docs-output/index.html 2>/dev/null || xdg-open docs-output/index.html 2>/dev/null || true; }
