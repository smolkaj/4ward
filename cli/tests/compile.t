Compiling and simulating separately
====================================

The 'run' subcommand (see getting_started.t) compiles and simulates in
one shot. But sometimes you want to separate the two steps — to inspect
the compiled output, or to run many tests against one compiled pipeline.

Step 1: compile a P4 program to a pipeline config.

  $ $FOURWARD compile -o "$CRAMTMP/compiled.txtpb" $P4

The output is a text-format protobuf containing the compiled program
(parser, match-action pipeline, expressions) and its P4Info metadata
(tables, actions, match fields).

  $ test -s "$CRAMTMP/compiled.txtpb"
  $ grep -q p4info "$CRAMTMP/compiled.txtpb"

Step 2: simulate an STF test against the compiled pipeline.

  $ $FOURWARD sim "$CRAMTMP/compiled.txtpb" $STF
  parse: start -> accept
  output port 1, 14 bytes
  PASS

Same trace, same verdict — but now you can reuse the compiled pipeline
for multiple tests without recompiling each time.
