package fourward.p4runtime

import fourward.sim.v1.DataplaneGrpcKt
import fourward.sim.v1.SimulatorProto.ProcessPacketRequest
import fourward.sim.v1.SimulatorProto.ProcessPacketResponse
import fourward.sim.v1.SimulatorProto.ProcessPacketWithTraceTreeResponse
import fourward.simulator.Simulator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dataplane gRPC service: injects packets into the simulator and returns output packets.
 *
 * Serialized via a shared [lock] with [P4RuntimeService] to prevent races between control-plane
 * writes and data-plane packet processing.
 *
 * Unlike [P4RuntimeService.streamChannel], this service operates on raw data-plane values — no type
 * translation is performed. Callers should use simulator-native port numbers and field widths, not
 * SDN-translated values.
 */
class DataplaneService(private val simulator: Simulator, private val lock: Mutex) :
  DataplaneGrpcKt.DataplaneCoroutineImplBase() {

  override suspend fun processPacket(request: ProcessPacketRequest): ProcessPacketResponse {
    val result =
      lock.withLock { simulator.processPacket(request.ingressPort, request.payload.toByteArray()) }
    return ProcessPacketResponse.newBuilder().addAllOutputPackets(result.outputPackets).build()
  }

  override suspend fun processPacketWithTraceTree(
    request: ProcessPacketRequest
  ): ProcessPacketWithTraceTreeResponse {
    val result =
      lock.withLock { simulator.processPacket(request.ingressPort, request.payload.toByteArray()) }
    return ProcessPacketWithTraceTreeResponse.newBuilder()
      .addAllOutputPackets(result.outputPackets)
      .setTrace(result.trace)
      .build()
  }
}
