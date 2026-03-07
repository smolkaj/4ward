#!/usr/bin/env bash
# ============================================================================
# Compiling and simulating separately
# ============================================================================
#
# The 'run' subcommand (see getting_started.sh) compiles and simulates in
# one shot. But sometimes you want to separate the two steps — e.g., to
# inspect the compiled output, or to run multiple tests against the same
# compiled pipeline.
#
# Step 1: Compile a P4 program to a pipeline config.
# Step 2: Simulate an STF test against the compiled pipeline.
# ============================================================================
set -euo pipefail

FOURWARD="$1"
P4="$2"
STF="$3"

# --- Step 1: Compile -------------------------------------------------------
#
# The 'compile' subcommand runs p4c-4ward (the P4 compiler backend) and
# produces a text-format protobuf file. This is the pipeline configuration
# that the simulator loads.

TMPDIR="${TEST_TMPDIR:-/tmp}"
OUTPUT="$TMPDIR/compiled.txtpb"

echo "--- Running: 4ward compile -o compiled.txtpb passthrough.p4 ---"
echo ""
"$FOURWARD" compile -o "$OUTPUT" "$P4" 2>&1

# The output is a text-format protobuf. It contains:
# - p4info: metadata about tables, actions, and match fields.
# - pipelines: the compiled program (parser, controls, expressions).
#
# You can inspect it to see what p4c made of your program:

echo "Compiled pipeline: $(wc -c < "$OUTPUT" | tr -d ' ') bytes"
test -s "$OUTPUT"                   || { echo "FAIL: output file is empty";  exit 1; }
grep -q "p4info" "$OUTPUT"          || { echo "FAIL: output missing p4info"; exit 1; }
echo ""

# --- Step 2: Simulate ------------------------------------------------------
#
# Now feed the compiled pipeline and a test file to 'sim'. Same trace tree
# output, same PASS/FAIL verdict — but this time the compilation was
# already done.

echo "--- Running: 4ward sim compiled.txtpb passthrough.stf ---"
echo ""
output=$("$FOURWARD" sim "$OUTPUT" "$STF" 2>&1)
echo "$output"
echo ""

echo "$output" | grep -q "PASS" || { echo "FAIL: expected PASS"; exit 1; }

echo "OK"
