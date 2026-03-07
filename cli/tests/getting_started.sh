#!/usr/bin/env bash
# ============================================================================
# Getting started with 4ward
# ============================================================================
#
# This is a tutorial you can read *and* a test verified by CI.
#
# 4ward is a glass-box P4 simulator. You give it a P4 program and a test
# file, and it shows you exactly what the simulator does with each packet:
# which parser states it visits, which tables it looks up, and where the
# packet ends up.
#
# Let's try it with the simplest possible program: passthrough.p4.
# It parses an Ethernet header, hardcodes the output port to 1, and emits
# the packet unchanged. No tables, no conditionals.
#
# The test file (passthrough.stf) sends one packet and checks it comes out:
#
#   packet 0 FFFFFFFFFFFF 000000000001 0800
#   expect 1 FFFFFFFFFFFF 000000000001 0800
#
# That's it — send on port 0, expect on port 1, same bytes.
# ============================================================================
set -euo pipefail

FOURWARD="$1"
P4="$2"
STF="$3"

# The 'run' subcommand compiles the P4 source and simulates in one shot.
# This is the fastest way to try a program.

echo "--- Running: 4ward run passthrough.p4 passthrough.stf ---"
echo ""

output=$("$FOURWARD" run "$P4" "$STF" 2>&1)
echo "$output"

# Here's what that output means:
#
#   parse: start -> accept       The parser moved from 'start' to 'accept',
#                                extracting the Ethernet header along the way.
#   output port 1, 14 bytes      The packet exits on port 1 (14-byte Ethernet frame).
#   PASS                         The actual output matched the STF's 'expect' line.
#
# The trace tree is the key feature of 4ward: it shows every decision the
# simulator made, so you can debug your P4 program by reading the trace
# instead of guessing.
#
# Compare this to basic_table.sh, which introduces match-action tables and
# shows a richer trace with table lookups, hits, misses, and drops.

echo ""
echo "--- Checking output ---"

echo "$output" | grep -q "PASS"        || { echo "FAIL: expected PASS";         exit 1; }
echo "$output" | grep -q "parse:"      || { echo "FAIL: expected parser trace"; exit 1; }
echo "$output" | grep -q "output port" || { echo "FAIL: expected output";       exit 1; }

echo "OK"
