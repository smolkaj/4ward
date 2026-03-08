package fourward.cli

import com.google.protobuf.ByteString
import fourward.sim.v1.SimulatorProto.ActionExecutionEvent
import fourward.sim.v1.SimulatorProto.Drop
import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.Fork
import fourward.sim.v1.SimulatorProto.ForkBranch
import fourward.sim.v1.SimulatorProto.ForkReason
import fourward.sim.v1.SimulatorProto.MarkToDropEvent
import fourward.sim.v1.SimulatorProto.OutputPacket
import fourward.sim.v1.SimulatorProto.PacketOutcome
import fourward.sim.v1.SimulatorProto.ParserTransitionEvent
import fourward.sim.v1.SimulatorProto.TableLookupEvent
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceFormatterTest {

  @Test
  fun simplePassthrough() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setParserTransition(
              ParserTransitionEvent.newBuilder()
                .setParserName("MyParser")
                .setFromState("start")
                .setToState("accept")
            )
        )
        .addEvents(
          TraceEvent.newBuilder()
            .setActionExecution(ActionExecutionEvent.newBuilder().setActionName("NoAction"))
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(
              OutputPacket.newBuilder()
                .setEgressPort(1)
                .setPayload(ByteString.copyFrom(byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |parse: start -> accept
      |action NoAction
      |output port 1, 2 bytes
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun tableLookupWithParams() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setTableLookup(
              TableLookupEvent.newBuilder()
                .setTableName("port_table")
                .setHit(true)
                .setActionName("forward")
            )
        )
        .addEvents(
          TraceEvent.newBuilder()
            .setActionExecution(
              ActionExecutionEvent.newBuilder()
                .setActionName("forward")
                .putParams("port", ByteString.copyFrom(byteArrayOf(0x01)))
            )
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(OutputPacket.newBuilder().setEgressPort(1).setPayload(ByteString.EMPTY))
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |table port_table: hit -> forward
      |action forward(port=1)
      |output port 1, 0 bytes
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun dropWithMarkToDrop() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setMarkToDrop(MarkToDropEvent.newBuilder().setReason(DropReason.MARK_TO_DROP))
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder().setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |mark_to_drop()
      |drop (reason: mark_to_drop)
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun forkTree() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setParserTransition(
              ParserTransitionEvent.newBuilder().setFromState("start").setToState("accept")
            )
        )
        .setForkOutcome(
          Fork.newBuilder()
            .setReason(ForkReason.CLONE)
            .addBranches(
              ForkBranch.newBuilder()
                .setLabel("original")
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder().setEgressPort(1).setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
            .addBranches(
              ForkBranch.newBuilder()
                .setLabel("clone")
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder().setEgressPort(2).setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |parse: start -> accept
      |fork (clone)
      |├── original
      |│   output port 1, 0 bytes
      |└── clone
      |    output port 2, 0 bytes
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun colorOutput() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setParserTransition(
              ParserTransitionEvent.newBuilder().setFromState("start").setToState("accept")
            )
        )
        .addEvents(
          TraceEvent.newBuilder()
            .setTableLookup(
              TableLookupEvent.newBuilder().setTableName("t").setHit(true).setActionName("fwd")
            )
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder().setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
        )
        .build()

    val c = AnsiColor(enabled = true)
    val output = TraceFormatter.format(tree, c)
    // Verify ANSI escape codes are present.
    assert(output.contains("\u001b[")) { "Expected ANSI escape codes in color output" }
    assert(output.contains("\u001b[36m")) { "Expected cyan for parse" }
    assert(output.contains("\u001b[32m")) { "Expected green for hit" }
    assert(output.contains("\u001b[31m")) { "Expected red for drop" }
  }

  @Test
  fun nestedForkTree() {
    val innerFork =
      TraceTree.newBuilder()
        .setForkOutcome(
          Fork.newBuilder()
            .setReason(ForkReason.CLONE)
            .addBranches(
              ForkBranch.newBuilder()
                .setLabel("a")
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder().setEgressPort(1).setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
            .addBranches(
              ForkBranch.newBuilder()
                .setLabel("b")
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
                    )
                )
            )
        )
        .build()

    val tree =
      TraceTree.newBuilder()
        .setForkOutcome(
          Fork.newBuilder()
            .setReason(ForkReason.ACTION_SELECTOR)
            .addBranches(ForkBranch.newBuilder().setLabel("original").setSubtree(innerFork))
            .addBranches(
              ForkBranch.newBuilder()
                .setLabel("clone")
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder().setEgressPort(3).setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |fork (selector)
      |├── original
      |│   fork (clone)
      |│   ├── a
      |│   │   output port 1, 0 bytes
      |│   └── b
      |│       drop (reason: mark_to_drop)
      |└── clone
      |    output port 3, 0 bytes
      |"""
        .trimMargin(),
      output,
    )
  }
}
