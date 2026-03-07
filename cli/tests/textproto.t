Textproto output format
=======================

The default trace is human-readable but lossy -- it omits source
locations and raw byte payloads. The --format=textproto flag outputs
the full trace tree as a protocol buffer, for programmatic analysis
or diffing between runs.

  $ cp "$P4" passthrough.p4

  $ 4ward run --format=textproto passthrough.p4 - << 'EOF'
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 1 FFFFFFFFFFFF 000000000001 0800
  > EOF
  events {
    parser_transition {
      parser_name: "MyParser"
      from_state: "start"
      to_state: "accept"
    }
    source_info {
      file: "passthrough.p4"
      line: 26
      column: 10
      source_fragment: "start"
    }
  }
  packet_outcome {
    output {
      egress_port: 1
      payload: "\377\377\377\377\377\377\000\000\000\000\000\001\b\000"
    }
  }
  PASS

Compare with the human-readable output from getting_started.t --
same packet, same result, but the textproto includes the source
location (passthrough.p4:26) and the raw payload bytes.
