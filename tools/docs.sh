#!/usr/bin/env bash
# Generate project documentation: proto reference, Kotlin reference, C++ reference.
#
# Everything is built by Bazel; this script assembles the outputs into a local
# directory for browsing.
#
# Usage:
#   ./tools/docs.sh          # generate all docs
#   ./tools/docs.sh --open   # generate and open in browser

set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

bazel build //tools:docs

chmod -R u+w docs-output 2>/dev/null || true
rm -rf docs-output
mkdir -p docs-output/proto docs-output/cpp
cp tools/docs-index.html docs-output/index.html
cp bazel-bin/tools/proto_docs.html docs-output/proto/index.html
cp -r bazel-bin/tools/kotlin_docs/. docs-output/kotlin
cp -r bazel-bin/tools/html/. docs-output/cpp

echo "Documentation generated in docs-output/"

if [[ "${1:-}" == "--open" ]]; then
  open docs-output/index.html 2>/dev/null || xdg-open docs-output/index.html 2>/dev/null || true
fi
