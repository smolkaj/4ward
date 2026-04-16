#!/usr/bin/env bash
# Regenerates documentation markdown that is auto-generated from source
# (Stardoc for Bazel rules, etc.) and writes it into userdocs/reference/.
# Committing the output keeps the docs site free of a Bazel build step
# at publish time, and the staleness check in CI keeps it honest.
#
# Usage:
#   ./tools/gen-docs.sh

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

bazel build //bazel:fourward_pipeline_docs

# Prepend mkdocs-Material frontmatter + an H1 to the raw Stardoc output.
# Existing reference/*.md files use the same shape, so the generated
# page slots into the nav without special casing.
OUT=userdocs/reference/bazel.md
{
  cat <<'EOF'
---
description: "Bazel rule reference for fourward_pipeline — compile P4 programs with p4c-4ward from a Bazel build."
---

# Bazel Rule Reference

EOF
  cat bazel-bin/bazel/fourward_pipeline.md
} > "$OUT"

echo "Wrote $OUT"
