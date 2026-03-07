Getting started with 4ward
==========================

4ward is a glass-box P4 simulator. Give it a P4 program and a test,
and it shows you exactly what happens to each packet -- parser states,
table lookups, and where the packet ends up.

passthrough.p4 is the simplest possible program: parse an Ethernet
header, hardcode the output port to 1, emit the packet unchanged.

  $ cp "$P4" passthrough.p4

The test sends one packet on port 0 and expects it back on port 1
with the same bytes. The '-' tells 4ward to read the test from stdin:

  $ 4ward run passthrough.p4 - << 'EOF'
  > # Send one Ethernet frame on port 0, expect it on port 1.
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 1 FFFFFFFFFFFF 000000000001 0800
  > EOF
  parse: start -> accept
  output port 1, 14 bytes
  PASS

Three lines, each a window into the data plane:

"parse: start -> accept" -- the parser extracted an Ethernet header.
"output port 1, 14 bytes" -- the packet exits port 1, unchanged.
"PASS" -- the actual output matched the expected output.

This is what glass-box means: you see every decision the simulator
made. When something goes wrong, the trace tells you exactly why
(see packet_mismatch.t for an example).
