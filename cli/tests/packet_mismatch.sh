#!/usr/bin/env bash
# Test that a packet mismatch is reported as FAIL with exit code 1.
set -euo pipefail

FOURWARD="$1"
P4="$2"

TMPDIR="${TEST_TMPDIR:-/tmp}"
BAD_STF="$TMPDIR/mismatch.stf"

# Create an STF that sends a packet and expects it on the WRONG port.
# passthrough.p4 always outputs on port 1, so expecting port 9 will fail.
cat > "$BAD_STF" <<'EOF'
packet 0 FFFFFFFFFFFF 000000000001 0800
expect 9 FFFFFFFFFFFF 000000000001 0800
EOF

echo "=== 4ward run with mismatched expect → FAIL (exit 1) ==="

# Run and capture exit code.
set +e
output=$("$FOURWARD" run "$P4" "$BAD_STF" 2>&1)
exit_code=$?
set -e

echo "$output"
echo "Exit code: $exit_code"

# Should exit with code 1 (TEST_FAILURE).
test "$exit_code" -eq 1 || { echo "FAIL: expected exit code 1, got $exit_code"; exit 1; }

# Should contain FAIL in output.
echo "$output" | grep -q "FAIL" || { echo "FAIL: expected FAIL in output"; exit 1; }

echo "=== Success! ==="
