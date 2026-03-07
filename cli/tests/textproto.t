Textproto output format
=======================

The --format=textproto flag prints the trace tree as a protocol buffer
text format instead of the human-readable summary.

  $ $FOURWARD run --format=textproto $P4 $STF > "$CRAMTMP/out.txt" 2>&1
  $ grep -q parser_transition "$CRAMTMP/out.txt"
  $ tail -1 "$CRAMTMP/out.txt"
  PASS
