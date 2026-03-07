Error handling
==============

No arguments -- prints usage, exits 2:

  $ $FOURWARD
  Usage: 4ward <command> [options]
  
  Commands:
    compile  program.p4 -o output.txtpb   Compile a P4 program to a pipeline config.
    sim      pipeline.txtpb test.stf       Run an STF test against a compiled pipeline.
    run      program.p4 test.stf           Compile and simulate in one step.
  
  Options:
    --format=human|textproto   Trace output format (default: human).
    --help                     Show this help message.
  [2]

Unknown command:

  $ $FOURWARD bogus 2>/dev/null
  [2]

Missing file:

  $ $FOURWARD sim /nonexistent/pipeline.txtpb /nonexistent/test.stf 2>/dev/null
  [1]

--help exits 0 and lists all subcommands:

  $ $FOURWARD --help | grep -c -E "compile|sim|run"
  3

Subcommand help:

  $ $FOURWARD sim --help | grep -q pipeline
