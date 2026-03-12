package fourward.simulator

import fourward.ir.v1.PipelineStage
import fourward.sim.v1.SimulatorProto.Drop
import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.PacketIngressEvent
import fourward.sim.v1.SimulatorProto.PacketOutcome
import fourward.sim.v1.SimulatorProto.PipelineStageEvent
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree

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
    fourward.sim.v1.SimulatorProto.OutputPacket.newBuilder()
      .setEgressPort(port)
      .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
      .build()
  val outcome = PacketOutcome.newBuilder().setOutput(output).build()
  return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
}

/** Creates a [TraceEvent] recording the packet's ingress port. */
internal fun packetIngressEvent(ingressPort: UInt): TraceEvent =
  TraceEvent.newBuilder()
    .setPacketIngress(PacketIngressEvent.newBuilder().setIngressPort(ingressPort.toInt()))
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
