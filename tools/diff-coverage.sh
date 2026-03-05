#!/bin/bash
# Computes incremental (diff) coverage: the fraction of added/modified lines
# that are covered by tests.
#
# Usage: ./diff-coverage.sh <diff-file> <lcov-file>
#
# Output (stdout, one per line):
#   TOTAL_LH=<total covered lines>
#   TOTAL_LF=<total coverable lines>
#   DIFF_LH=<covered changed lines>
#   DIFF_LF=<coverable changed lines>
#   DIFF_PCT=<percentage, or -1 if no coverable lines changed>
#
# The diff must be in unified format (e.g. `git diff` or `gh pr diff`).
# The LCOV file is the one produced by coverage.sh.

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <diff-file> <lcov-file>" >&2
  exit 1
fi

DIFF_FILE="$1"
LCOV_FILE="$2"

if [[ ! -f "${DIFF_FILE}" ]]; then
  echo "Error: diff file not found: ${DIFF_FILE}" >&2
  exit 1
fi

if [[ ! -f "${LCOV_FILE}" ]]; then
  echo "Error: LCOV file not found: ${LCOV_FILE}" >&2
  exit 1
fi

# Single awk invocation, two-file pass:
#   File 1 (diff): build set of (file, line) pairs for added lines.
#   File 2 (LCOV): check which of those lines have coverage data.
awk '
  # ── Pass 1: unified diff ──────────────────────────────────────────────
  FILENAME == ARGV[1] {
    # New file header.
    if (/^\+\+\+ /) {
      # +++ /dev/null means a deleted file — ignore.
      if ($2 == "/dev/null") { diff_file = ""; next }
      # Strip the "b/" prefix.
      diff_file = $2
      sub(/^b\//, "", diff_file)
      next
    }
    # Hunk header: @@ -old,oldN +new,newN @@  —  $3 is "+L" or "+L,N".
    if (/^@@ /) {
      split($3, a, ",")
      sub(/\+/, "", a[1])
      diff_line = a[1] + 0
      next
    }
    # Inside a hunk: added lines get recorded.
    if (diff_file != "" && /^\+/) {
      added[diff_file, diff_line] = 1
      diff_line++
      next
    }
    # Context lines increment the counter; deleted lines do not.
    if (diff_file != "" && /^-/) next
    if (diff_file != "") diff_line++
    next
  }

  # ── Pass 2: LCOV ─────────────────────────────────────────────────────
  FILENAME == ARGV[2] {
    if (/^SF:/) {
      lcov_file = substr($0, 4)
      next
    }
    if (/^DA:/) {
      # DA:<line>,<count>[,...]
      split(substr($0, 4), da, ",")
      line = da[1] + 0
      count = da[2] + 0
      total_lf++
      if (count > 0) total_lh++
      if ((lcov_file, line) in added) {
        diff_lf++
        if (count > 0) diff_lh++
      }
      next
    }
    next
  }

  END {
    if (diff_lf > 0) {
      pct = int(100 * diff_lh / diff_lf)
    } else {
      pct = -1
    }
    printf "TOTAL_LH=%d\n", total_lh + 0
    printf "TOTAL_LF=%d\n", total_lf + 0
    printf "DIFF_LH=%d\n", diff_lh + 0
    printf "DIFF_LF=%d\n", diff_lf + 0
    printf "DIFF_PCT=%d\n", pct
  }
' "${DIFF_FILE}" "${LCOV_FILE}"
