Getting started with 4ward
==========================

4ward is a glass-box P4 simulator. You give it a P4 program and a test
file (.stf), and it shows you exactly what the simulator does with each
packet: which parser states it visits, which tables it looks up, and
where the packet ends up.

Let's try passthrough.p4 -- the simplest possible program. It parses an
Ethernet header, hardcodes the output port to 1, and emits the packet
unchanged. No tables, no conditionals.

The test file sends one packet on port 0 and expects it on port 1 with
the same bytes (packet 0 ... / expect 1 ...).

  $ cp "$P4" passthrough.p4 && cp "$STF" passthrough.stf

The 'run' subcommand compiles the P4 source and simulates in one shot:

  $ 4ward run passthrough.p4 passthrough.stf
  parse: start -> accept
  output port 1, 14 bytes
  PASS

The trace shows every decision the simulator made:

"parse: start -> accept" means the parser extracted the Ethernet header.
"output port 1, 14 bytes" means the packet exits on port 1.
"PASS" means the actual output matched the STF's 'expect' line.

This is the key feature of 4ward: glass-box visibility into the data
plane. Compare this to basic_table.t, which introduces match-action
tables and shows a richer trace with hits, misses, and drops.
