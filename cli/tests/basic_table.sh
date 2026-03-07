#!/usr/bin/env bash
# ============================================================================
# Match-action tables
# ============================================================================
#
# passthrough.p4 hardcodes the output port — not very useful. Real P4
# programs use tables to make forwarding decisions at runtime.
#
# basic_table.p4 defines a table keyed on the Ethernet type field:
#
#   table port_table {
#       key = { hdr.ethernet.etherType : exact; }
#       actions = { forward; drop; }
#       default_action = drop();
#   }
#
# The test file installs one entry and sends two packets:
#
#   add port_table hdr.ethernet.etherType:0x0800 forward(1)
#
#   packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF    <- IPv4, matches
#   expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
#
#   packet 0 FFFFFFFFFFFF 000000000001 0806 DEADBEEF    <- ARP, no match
#   (no expect — the packet is dropped by the default action)
#
# The trace tree shows both outcomes: a table hit for IPv4, and a drop for
# ARP. This is where 4ward's glass-box tracing shines — you can see *why*
# one packet was forwarded and the other was dropped.
# ============================================================================
set -euo pipefail

FOURWARD="$1"
P4="$2"
STF="$3"

echo "--- Running: 4ward run basic_table.p4 basic_table.stf ---"
echo ""

output=$("$FOURWARD" run "$P4" "$STF" 2>&1)
echo "$output"
echo ""

echo "--- Checking output ---"

echo "$output" | grep -q "PASS"        || { echo "FAIL: expected PASS";        exit 1; }
echo "$output" | grep -q "table"       || { echo "FAIL: expected table trace"; exit 1; }
echo "$output" | grep -q "output port" || { echo "FAIL: expected output";      exit 1; }
echo "$output" | grep -q "drop"        || { echo "FAIL: expected drop trace";  exit 1; }

echo "OK"
