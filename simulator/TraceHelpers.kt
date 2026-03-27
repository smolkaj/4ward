package fourward.simulator

import fourward.ir.PipelineStage
import fourward.sim.SimulatorProto.Drop
import fourward.sim.SimulatorProto.DropReason
import fourward.sim.SimulatorProto.Fork
import fourward.sim.SimulatorProto.ForkBranch
import fourward.sim.SimulatorProto.ForkReason
import fourward.sim.SimulatorProto.PacketIngressEvent
import fourward.sim.SimulatorProto.PacketOutcome
import fourward.sim.SimulatorProto.PipelineStageEvent
import fourward.sim.SimulatorProto.TraceEvent
import fourward.sim.SimulatorProto.TraceTree

/** Builds a [TraceTree] representing a dropped packet with the given trace events and reason. */
internal fun buildDropTrace(
  events: List<TraceEvent>,
  reason: DropReason = DropReason.MARK_TO_DROP,
): TraceTree {
  val outcome = PacketOutcome.newBuilder().setDrop(Drop.newBuilder().setReason(reason)).build()
  return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
}

/** Builds a [TraceTree] representing a packet output on the given port. */
internal fun buildOutputTrace(events: List<TraceEvent>, port: Int, payload: ByteArray): TraceTree {
  val output =
    fourward.sim.SimulatorProto.OutputPacket.newBuilder()
      .setDataplaneEgressPort(port)
      .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
      .build()
  val outcome = PacketOutcome.newBuilder().setOutput(output).build()
  return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
}

/** Creates a [TraceEvent] recording the packet's ingress port. */
internal fun packetIngressEvent(ingressPort: UInt): TraceEvent =
  TraceEvent.newBuilder()
    .setPacketIngress(PacketIngressEvent.newBuilder().setDataplaneIngressPort(ingressPort.toInt()))
    .build()

/** Creates a [TraceEvent] marking the entry/exit of a pipeline stage. */
internal fun stageEvent(stage: PipelineStage, direction: PipelineStageEvent.Direction): TraceEvent =
  TraceEvent.newBuilder()
    .setPipelineStage(
      PipelineStageEvent.newBuilder()
        .setStageName(stage.name)
        .setStageKind(stage.kind)
        .setDirection(direction)
    )
    .build()

/** Builds a [TraceTree] with a fork outcome from accumulated events and branches. */
internal fun buildForkTree(
  events: List<TraceEvent>,
  reason: ForkReason,
  branches: List<ForkBranch>,
): TraceTree =
  TraceTree.newBuilder()
    .addAllEvents(events)
    .setForkOutcome(Fork.newBuilder().setReason(reason).addAllBranches(branches))
    .build()
