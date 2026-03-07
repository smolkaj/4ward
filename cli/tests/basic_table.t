Match-action tables
===================

passthrough.p4 hardcodes the output port. Real P4 programs use tables
to make forwarding decisions at runtime.

basic_table.p4 defines a table (port_table) keyed on the Ethernet type
field. If the etherType matches an installed entry, forward to the
specified port; otherwise, the default action drops the packet.

The '-' argument tells 4ward to read the STF test from stdin, so we
can inline it directly:

  $ cp "$P4" basic_table.p4

  $ 4ward run basic_table.p4 - << 'EOF'
  > # Install one entry: etherType 0x0800 (IPv4) -> forward to port 1.
  > add port_table hdr.ethernet.etherType:0x0800 forward(1)
  > # IPv4 frame -- matches the entry, forwarded to port 1.
  > packet 0 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
  > expect 1 FFFFFFFFFFFF 000000000001 0800 DEADBEEF
  > # ARP frame -- no matching entry, dropped by default action.
  > packet 0 FFFFFFFFFFFF 000000000001 0806 DEADBEEF
  > EOF
  packet received: port 0, 18 bytes
    parse: start -> accept
    table port_table: hit -> forward
    action forward(port=1)
    output port 1, 18 bytes
  packet received: port 0, 18 bytes
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
