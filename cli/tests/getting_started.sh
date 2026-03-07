#!/usr/bin/env bash
# Getting started with 4ward — a walkthrough verified by CI.
#
# This test demonstrates the "hello world" experience: compile a P4 program
# and run an STF test against it, all in one command.
set -euo pipefail

FOURWARD="$1"
P4="$2"
STF="$3"

echo "=== 4ward run: compile and simulate in one shot ==="

# The 'run' subcommand compiles the P4 program and runs the STF test.
# It prints a human-readable trace tree showing every decision the simulator
# made, then reports PASS or FAIL.
output=$("$FOURWARD" run "$P4" "$STF" 2>&1)
echo "$output"

# Verify the test passed.
echo "$output" | grep -q "PASS" || { echo "FAIL: expected PASS"; exit 1; }

# Verify the trace contains expected events.
echo "$output" | grep -q "parse:" || { echo "FAIL: expected parser trace"; exit 1; }
echo "$output" | grep -q "output port" || { echo "FAIL: expected output"; exit 1; }

echo "=== Success! ==="
