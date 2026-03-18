package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.dataplane.DataplaneGrpcKt
import fourward.dataplane.DataplaneProto
import fourward.dataplane.DataplaneProto.InjectPacketRequest
import fourward.dataplane.DataplaneProto.InjectPacketResponse
import fourward.dataplane.DataplaneProto.ProcessPacketResult as ProcessPacketResultProto
import fourward.dataplane.DataplaneProto.SubscribeResultsRequest
import fourward.dataplane.DataplaneProto.SubscribeResultsResponse
import fourward.dataplane.DataplaneProto.SubscriptionActive
import fourward.sim.SimulatorProto.TraceTree
import io.grpc.Status
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dataplane gRPC service: injects packets into the simulator and returns output packets with dual
 * port encoding (dataplane + P4Runtime) and P4RT-enriched traces.
 *
 * Serialized via a shared [lock] with [P4RuntimeService] to prevent races between control-plane
 * writes and data-plane packet processing.
 *
 * @param typeTranslator provides the current [TypeTranslator] from the loaded pipeline, or null if
 *   no pipeline is loaded or the pipeline has no type translation.
 */
class DataplaneService(
  private val broker: PacketBroker,
  private val lock: Mutex,
  private val typeTranslator: () -> TypeTranslator? = { null },
) : DataplaneGrpcKt.DataplaneCoroutineImplBase() {

  override suspend fun injectPacket(request: InjectPacketRequest): InjectPacketResponse {
    val translator = typeTranslator()
    val pt = translator?.portTranslator
    val ingressPort = resolveIngressPort(request, pt)
    val payload = request.payload.toByteArray()
    val result = lock.withLock { broker.processPacket(ingressPort, payload) }
    return InjectPacketResponse.newBuilder()
      .addAllOutputPackets(result.outputPackets.map { it.toDualEncoded(pt) })
      .setTrace(enrichTrace(result.trace, translator))
      .build()
  }

  override fun subscribeResults(request: SubscribeResultsRequest): Flow<SubscribeResultsResponse> =
    callbackFlow {
      send(
        SubscribeResultsResponse.newBuilder()
          .setActive(SubscriptionActive.getDefaultInstance())
          .build()
      )

      val handle =
        broker.subscribe { subResult ->
          val translator = typeTranslator()
          val pt = translator?.portTranslator
          val result =
            ProcessPacketResultProto.newBuilder()
              .setInputPacket(
                DataplaneProto.InputPacket.newBuilder()
                  .setDataplaneIngressPort(subResult.ingressPort)
                  .apply {
                    pt?.dataplaneToP4rt(subResult.ingressPort)?.let { setP4RtIngressPort(it) }
                  }
                  .setPayload(ByteString.copyFrom(subResult.payload))
              )
              .addAllOutputPackets(subResult.outputPackets.map { it.toDualEncoded(pt) })
              .setTrace(enrichTrace(subResult.trace, translator))
              .build()
          trySend(SubscribeResultsResponse.newBuilder().setResult(result).build())
        }

      awaitClose { handle.unsubscribe() }
    }

  /** Resolves the ingress port from the request's oneof. */
  private fun resolveIngressPort(request: InjectPacketRequest, pt: PortTranslator?): Int =
    when (request.ingressPortCase) {
      InjectPacketRequest.IngressPortCase.DATAPLANE_INGRESS_PORT -> request.dataplaneIngressPort
      InjectPacketRequest.IngressPortCase.P4RT_INGRESS_PORT -> {
        val translator =
          pt
            ?: throw Status.FAILED_PRECONDITION.withDescription(
                "P4Runtime port translation requires a loaded pipeline with " +
                  "@p4runtime_translation on the port type"
              )
              .asException()
        translator.p4rtToDataplane(request.p4RtIngressPort)
      }
      InjectPacketRequest.IngressPortCase.INGRESSPORT_NOT_SET,
      null -> 0
    }
}

private fun enrichTrace(trace: TraceTree, translator: TypeTranslator?): TraceTree =
  translator?.let { TraceEnricher.enrich(trace, it) } ?: trace

/**
 * Converts a simulator [fourward.sim.SimulatorProto.OutputPacket] to a dual-encoded
 * [DataplaneProto.OutputPacket].
 */
private fun fourward.sim.SimulatorProto.OutputPacket.toDualEncoded(
  pt: PortTranslator?
): DataplaneProto.OutputPacket =
  DataplaneProto.OutputPacket.newBuilder()
    .setDataplaneEgressPort(dataplaneEgressPort)
    .apply { pt?.dataplaneToP4rt(dataplaneEgressPort)?.let { setP4RtEgressPort(it) } }
    .setPayload(payload)
    .build()
