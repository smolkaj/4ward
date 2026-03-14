package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.dataplane.v1.DataplaneGrpcKt
import fourward.dataplane.v1.DataplaneProto.InjectPacketRequest
import fourward.dataplane.v1.DataplaneProto.InjectPacketResponse
import fourward.dataplane.v1.DataplaneProto.ProcessPacketResult as ProcessPacketResultProto
import fourward.dataplane.v1.DataplaneProto.SubscribeResultsRequest
import fourward.dataplane.v1.DataplaneProto.SubscribeResultsResponse
import fourward.dataplane.v1.DataplaneProto.SubscriptionActive
import fourward.sim.v1.SimulatorProto.InputPacket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
class DataplaneService(private val broker: PacketBroker, private val lock: Mutex) :
  DataplaneGrpcKt.DataplaneCoroutineImplBase() {

  override suspend fun injectPacket(request: InjectPacketRequest): InjectPacketResponse {
    val packet = request.packet
    val result =
      lock.withLock { broker.processPacket(packet.ingressPort, packet.payload.toByteArray()) }
    return InjectPacketResponse.newBuilder()
      .addAllOutputPackets(result.outputPackets)
      .setTrace(result.trace)
      .build()
  }

  override fun subscribeResults(request: SubscribeResultsRequest): Flow<SubscribeResultsResponse> =
    callbackFlow {
      // Send the handshake message confirming the subscription is active.
      send(
        SubscribeResultsResponse.newBuilder()
          .setActive(SubscriptionActive.getDefaultInstance())
          .build()
      )

      val handle =
        broker.subscribe { subResult ->
          val result =
            ProcessPacketResultProto.newBuilder()
              .setInput(
                InputPacket.newBuilder()
                  .setIngressPort(subResult.ingressPort)
                  .setPayload(ByteString.copyFrom(subResult.payload))
              )
              .addAllOutputPackets(subResult.outputPackets)
              .setTrace(subResult.trace)
              .build()
          trySend(SubscribeResultsResponse.newBuilder().setResult(result).build())
        }

      awaitClose { handle.unsubscribe() }
    }
}
