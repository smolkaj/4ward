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
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import p4.v1.P4RuntimeOuterClass

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
  private val lock: ReadWriteMutex,
  private val typeTranslator: () -> TypeTranslator? = { null },
  private val applyUpdates: (List<P4RuntimeOuterClass.Update>) -> Unit = {},
) : DataplaneGrpcKt.DataplaneCoroutineImplBase() {

  /**
   * The currently registered pre-packet hook, or null if none. The hook is a pair of channels:
   * - invocations: server sends [DataplaneProto.PrePacketHookInvocation] to the client
   * - responses: client sends [DataplaneProto.PrePacketHookResponse] back
   */
  private data class Hook(
    val invocations: Channel<DataplaneProto.PrePacketHookInvocation>,
    val responses: Channel<DataplaneProto.PrePacketHookResponse>,
  )

  private val hook = AtomicReference<Hook?>(null)

  /**
   * Fires the pre-packet hook if one is registered. Must be called while holding the write lock.
   * Sends an invocation to the client, waits for the response, and applies any P4Runtime updates.
   *
   * Uses [runBlocking] because this is called under a Java
   * [java.util.concurrent.locks.ReentrantReadWriteLock] which must be released on the same thread
   * that acquired it. Suspend functions can resume on a different thread, which would cause
   * [IllegalMonitorStateException] on unlock.
   */
  private fun fireHookIfRegistered() {
    val h = hook.get() ?: return
    runBlocking {
      h.invocations.send(DataplaneProto.PrePacketHookInvocation.getDefaultInstance())
      val response = h.responses.receive()
      if (response.updatesList.isNotEmpty()) {
        applyUpdates(response.updatesList)
      }
    }
  }

  override suspend fun injectPacket(request: InjectPacketRequest): InjectPacketResponse {
    val translator = typeTranslator()
    val pt = translator?.portTranslator
    val ingressPort = resolveIngressPort(request, pt)
    val payload = request.payload.toByteArray()
    val result =
      if (hook.get() != null) {
        lock.withWriteLock {
          fireHookIfRegistered()
          broker.processPacket(ingressPort, payload)
        }
      } else {
        lock.withReadLock { broker.processPacket(ingressPort, payload) }
      }
    try {
      val possibleOutcomes =
        result.possibleOutcomes.map { world ->
          DataplaneProto.PacketSet.newBuilder()
            .addAllPackets(world.map { it.toDualEncoded(pt) })
            .build()
        }
      return InjectPacketResponse.newBuilder()
        .addAllPossibleOutcomes(possibleOutcomes)
        .setTrace(enrichTrace(result.trace, translator))
        .build()
    } catch (e: IllegalStateException) {
      throw Status.INTERNAL.withDescription(e.message).withCause(e).asException()
    }
  }

  override suspend fun injectPackets(
    requests: Flow<InjectPacketRequest>
  ): DataplaneProto.InjectPacketsResponse {
    val pt = typeTranslator()?.portTranslator
    if (hook.get() != null) {
      // With a hook registered, acquire the write lock for the entire stream
      // and fire the hook once at the start.
      lock.withWriteLock {
        fireHookIfRegistered()
        val futures = mutableListOf<ForkJoinTask<*>>()
        requests.collect { req ->
          val port = resolveIngressPort(req, pt)
          val payload = req.payload.toByteArray()
          // Process under the already-held write lock (no additional locking).
          futures.add(ForkJoinPool.commonPool().submit { broker.processPacket(port, payload) })
        }
        futures.forEach { it.join() }
      }
    } else {
      // No hook — use read lock per packet for maximum concurrency.
      val futures = mutableListOf<ForkJoinTask<*>>()
      requests.collect { req ->
        val port = resolveIngressPort(req, pt)
        val payload = req.payload.toByteArray()
        futures.add(
          ForkJoinPool.commonPool().submit {
            lock.withReadLockBlocking { broker.processPacket(port, payload) }
          }
        )
      }
      futures.forEach { it.join() }
    }
    return DataplaneProto.InjectPacketsResponse.getDefaultInstance()
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
          try {
            val translator = typeTranslator()
            val pt = translator?.portTranslator
            val result =
              ProcessPacketResultProto.newBuilder()
                .setInputPacket(
                  DataplaneProto.InputPacket.newBuilder()
                    .setDataplaneIngressPort(subResult.ingressPort)
                    .apply {
                      // Ingress ports may come from InjectPacket.dataplane_ingress_port (raw int,
                      // never forward-allocated), so a missing reverse mapping is expected.
                      pt?.dataplaneToP4rt(subResult.ingressPort)?.let { setP4RtIngressPort(it) }
                    }
                    .setPayload(ByteString.copyFrom(subResult.payload))
                )
                .addAllPossibleOutcomes(
                  subResult.possibleOutcomes.map { world ->
                    DataplaneProto.PacketSet.newBuilder()
                      .addAllPackets(world.map { it.toDualEncoded(pt) })
                      .build()
                  }
                )
                .setTrace(enrichTrace(subResult.trace, translator))
                .build()
            trySend(SubscribeResultsResponse.newBuilder().setResult(result).build())
          } catch (
            @Suppress("TooGenericExceptionCaught") // Any translation/encoding failure should
            e: Exception // terminate this subscription stream, not crash the packet sender.
          ) {
            close(e)
          }
        }

      awaitClose { handle.unsubscribe() }
    }

  override fun registerPrePacketHook(
    requests: Flow<DataplaneProto.PrePacketHookResponse>
  ): Flow<DataplaneProto.PrePacketHookInvocation> = callbackFlow {
    val invocations = Channel<DataplaneProto.PrePacketHookInvocation>(Channel.RENDEZVOUS)
    val responses = Channel<DataplaneProto.PrePacketHookResponse>(Channel.RENDEZVOUS)
    val newHook = Hook(invocations, responses)

    if (!hook.compareAndSet(null, newHook)) {
      throw Status.ALREADY_EXISTS.withDescription("A pre-packet hook is already registered")
        .asException()
    }

    // Forward invocations from the internal channel to the gRPC stream.
    launch {
      for (invocation in invocations) {
        send(invocation)
      }
    }

    // Forward responses from the gRPC stream to the internal channel.
    launch { requests.collect { response -> responses.send(response) } }

    awaitClose {
      hook.set(null)
      invocations.close()
      responses.close()
    }
  }

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
 *
 * When a [PortTranslator] is present, every egress port must be reverse-translatable — either
 * forward-allocated by a controller Write or installed via `Replica.port` (bytes). A missing
 * mapping means the clone session or multicast group used the deprecated `Replica.egress_port`
 * (int32), which bypasses port translation.
 */
private fun fourward.sim.SimulatorProto.OutputPacket.toDualEncoded(
  pt: PortTranslator?
): DataplaneProto.OutputPacket =
  DataplaneProto.OutputPacket.newBuilder()
    .setDataplaneEgressPort(dataplaneEgressPort)
    .apply {
      if (pt != null) {
        val p4rtPort =
          pt.dataplaneToP4rt(dataplaneEgressPort)
            ?: error(
              "PortTranslator has no reverse mapping for dataplane egress port " +
                "$dataplaneEgressPort. Use Replica.port (bytes) instead of the deprecated " +
                "Replica.egress_port (int32) to enable port translation for clone sessions " +
                "and multicast groups."
            )
        setP4RtEgressPort(p4rtPort)
      }
    }
    .setPayload(payload)
    .build()
