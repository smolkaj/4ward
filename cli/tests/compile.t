Compiling and simulating separately
====================================

The 'run' subcommand compiles and simulates in one shot. But sometimes
you want to separate the two steps -- to inspect the compiled output,
or to run many tests against one compiled pipeline.

  $ cp "$P4" passthrough.p4

Step 1: compile a P4 program to a pipeline config.

  $ 4ward compile -o pipeline.txtpb passthrough.p4

The output is a text-format protobuf. Let's peek at the structure:

  $ head -20 pipeline.txtpb
  p4info {
    pkg_info {
      arch: "v1model"
    }
  }
  device {
    behavioral {
      architecture {
        name: "v1model"
        stages {
          name: "parser"
          kind: PARSER
          block_name: "MyParser"
        }
        stages {
          name: "verify_checksum"
          kind: CONTROL
          block_name: "MyVerifyChecksum"
        }
        stages {

You can see the v1model pipeline stages (parser, verify_checksum,
ingress, egress, ...) and the block names from the P4 source. The
full file also contains the parser states, control flow, expressions,
table definitions -- everything the simulator needs.

Step 2: simulate a test against the compiled pipeline.

  $ 4ward sim pipeline.txtpb - << 'EOF'
  > packet 0 FFFFFFFFFFFF 000000000001 0800
  > expect 1 FFFFFFFFFFFF 000000000001 0800
  > EOF
  packet received: port 0, 14 bytes
    parse: start -> accept
    output port 1, 14 bytes
  PASS

Same trace, same verdict -- but now you can reuse the compiled
pipeline for multiple tests without recompiling each time.
