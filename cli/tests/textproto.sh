#!/usr/bin/env bash
# Test --format=textproto output mode.
set -euo pipefail

FOURWARD="$1"
P4="$2"
STF="$3"

echo "=== 4ward run --format=textproto ==="

output=$("$FOURWARD" run --format=textproto "$P4" "$STF" 2>&1)
echo "$output"

# Verify the test passed.
echo "$output" | grep -q "PASS" || { echo "FAIL: expected PASS"; exit 1; }

# textproto output should contain proto field names, not human-readable labels.
# The trace tree proto has fields like "parser_transition" and "packet_outcome".
echo "$output" | grep -q "parser_transition" || { echo "FAIL: expected proto field parser_transition"; exit 1; }

echo "=== Success! ==="
