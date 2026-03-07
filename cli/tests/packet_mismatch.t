Packet mismatch detection
=========================

When the actual output doesn't match the test's expect line, 4ward
shows the trace (so you can see what happened) then reports the
mismatch and exits with code 1.

passthrough.p4 always outputs on port 1. If we expect port 9:

  $ cp "$P4" passthrough.p4

  $ 4ward run passthrough.p4 - 2>&1 << 'EOF'
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 9 FFFFFFFFFFFF 000000000001 0800
  > EOF
  parse: start -> accept
  output port 1, 14 bytes
  FAIL
    expected packet on port 9 but got none
  [1]

The trace shows exactly what happened -- the packet exited port 1.
The FAIL message explains the mismatch. Exit code 1 lets scripts
detect test failures programmatically.
