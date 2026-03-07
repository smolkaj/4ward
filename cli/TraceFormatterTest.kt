package fourward.cli

import com.google.protobuf.ByteString
import fourward.sim.v1.ActionExecutionEvent
import fourward.sim.v1.Drop
import fourward.sim.v1.DropReason
import fourward.sim.v1.Fork
import fourward.sim.v1.ForkBranch
import fourward.sim.v1.ForkReason
import fourward.sim.v1.MarkToDropEvent
import fourward.sim.v1.OutputPacket
import fourward.sim.v1.PacketOutcome
import fourward.sim.v1.ParserTransitionEvent
import fourward.sim.v1.TableLookupEvent
import fourward.sim.v1.TraceEvent
import fourward.sim.v1.TraceTree
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
      |action forward(port=01)
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
      |drop (mark_to_drop())
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
      |  branch: original
      |    output port 1, 0 bytes
      |  branch: clone
      |    output port 2, 0 bytes
      |"""
        .trimMargin(),
      output,
    )
  }
}
