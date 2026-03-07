#!/usr/bin/env bash
# Test the 'compile' subcommand — compile a P4 program to a pipeline config,
# then simulate against it separately.
set -euo pipefail

FOURWARD="$1"
P4="$2"
STF="$3"

echo "=== Step 1: Compile P4 to pipeline config ==="
TMPDIR="${TEST_TMPDIR:-/tmp}"
OUTPUT="$TMPDIR/compiled.txtpb"

"$FOURWARD" compile -o "$OUTPUT" "$P4" 2>&1

# The output is a text-format protobuf containing the pipeline configuration.
test -s "$OUTPUT" || { echo "FAIL: output file is empty"; exit 1; }
grep -q "p4info" "$OUTPUT" || { echo "FAIL: output missing p4info"; exit 1; }
echo "Compiled successfully: $OUTPUT"

echo "=== Step 2: Simulate against the compiled pipeline ==="
output=$("$FOURWARD" sim "$OUTPUT" "$STF" 2>&1)
echo "$output"
echo "$output" | grep -q "PASS" || { echo "FAIL: expected PASS"; exit 1; }

echo "=== Success! ==="
