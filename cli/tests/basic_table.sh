#!/usr/bin/env bash
# Test the basic_table example — table lookup with match and drop.
set -euo pipefail

FOURWARD="$1"
P4="$2"
STF="$3"

echo "=== 4ward run: basic_table with match entry and default drop ==="

output=$("$FOURWARD" run "$P4" "$STF" 2>&1)
echo "$output"

# Verify the test passed.
echo "$output" | grep -q "PASS" || { echo "FAIL: expected PASS"; exit 1; }

# Verify the trace shows a table hit for the IPv4 packet.
echo "$output" | grep -q "table" || { echo "FAIL: expected table trace"; exit 1; }

# Verify the matched packet is output.
echo "$output" | grep -q "output port" || { echo "FAIL: expected output"; exit 1; }

# Verify the dropped packet shows a drop.
echo "$output" | grep -q "drop" || { echo "FAIL: expected drop trace"; exit 1; }

echo "=== Success! ==="
