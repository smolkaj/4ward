Textproto output format
=======================

The --format=textproto flag prints the trace tree as a protocol buffer
text format instead of the human-readable summary.

  $ cp "$P4" passthrough.p4 && cp "$STF" passthrough.stf

  $ 4ward run --format=textproto passthrough.p4 passthrough.stf
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
