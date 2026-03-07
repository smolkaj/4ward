Match-action tables
===================

passthrough.p4 hardcodes the output port. Real P4 programs use tables
to make forwarding decisions at runtime.

basic_table.p4 defines a table (port_table) keyed on the Ethernet type
field. If the etherType matches an installed entry, forward to the
specified port; otherwise, the default action drops the packet.

The STF test installs one entry (etherType 0x0800 -> forward to port 1)
and sends two packets: an IPv4 frame (0x0800, matches the entry) and an
ARP frame (0x0806, no match, dropped by default action).

  $ $FOURWARD run $P4 $STF
  parse: start -> accept
  table port_table: hit -> forward
  action forward(port=0001)
  output port 1, 18 bytes
  parse: start -> accept
  table port_table: miss -> drop
  action drop
  mark_to_drop()
  drop (mark_to_drop())
  PASS

The trace shows both outcomes side by side. The IPv4 packet hits the
table, runs the 'forward' action, and exits on port 1. The ARP packet
misses, runs the default 'drop' action, and is discarded.

This is where glass-box tracing shines -- you can see exactly why one
packet was forwarded and the other was dropped.
