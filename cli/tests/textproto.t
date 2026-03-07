Textproto output format
=======================

The --format=textproto flag prints the trace tree as a protocol buffer
text format instead of the human-readable summary.

  $ cp "$P4" passthrough.p4 && cp "$STF" passthrough.stf

  $ 4ward run --format=textproto passthrough.p4 passthrough.stf > out.txt 2>&1
  $ grep -q parser_transition out.txt
  $ tail -1 out.txt
  PASS
