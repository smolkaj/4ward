Error handling
==============

4ward gives clear errors, not stacktraces.

Missing file:

  $ 4ward sim missing.txtpb missing.stf 2>&1
  error: missing.txtpb (No such file or directory)
  [2]

Unknown command:

  $ 4ward bogus 2>&1 | head -1
  error: unknown command 'bogus'

Every subcommand has --help:

  $ 4ward compile --help | head -1
  Usage: 4ward compile [options] <program.p4>

  $ 4ward sim --help | head -1
  Usage: 4ward sim [--format=human|textproto] <pipeline.txtpb> <test.stf>

  $ 4ward run --help | head -1
  Usage: 4ward run [--format=human|textproto] <program.p4> <test.stf>
