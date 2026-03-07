#!/usr/bin/env bash
# Test CLI error handling — bad arguments, missing files, invalid P4.
set -euo pipefail

FOURWARD="$1"

echo "=== No arguments → usage error (exit 2) ==="
if "$FOURWARD" 2>/dev/null; then
  echo "FAIL: expected non-zero exit"; exit 1
fi

echo "=== Unknown command → usage error ==="
if "$FOURWARD" bogus 2>/dev/null; then
  echo "FAIL: expected non-zero exit"; exit 1
fi

echo "=== sim with missing file → error ==="
if "$FOURWARD" sim /nonexistent/pipeline.txtpb /nonexistent/test.stf 2>/dev/null; then
  echo "FAIL: expected non-zero exit"; exit 1
fi

echo "=== --help → success (exit 0) ==="
output=$("$FOURWARD" --help 2>&1)
echo "$output" | grep -q "compile" || { echo "FAIL: help missing 'compile'"; exit 1; }
echo "$output" | grep -q "sim" || { echo "FAIL: help missing 'sim'"; exit 1; }
echo "$output" | grep -q "run" || { echo "FAIL: help missing 'run'"; exit 1; }

echo "=== sim --help → success ==="
output=$("$FOURWARD" sim --help 2>&1)
echo "$output" | grep -q "pipeline" || { echo "FAIL: sim help missing 'pipeline'"; exit 1; }

echo "=== Success! ==="
