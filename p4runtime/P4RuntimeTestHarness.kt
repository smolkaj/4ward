package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import fourward.simulator.Simulator
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import p4.v1.P4RuntimeGrpcKt.P4RuntimeCoroutineStub
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.PacketOut
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse
import p4.v1.P4RuntimeOuterClass.Uint128
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest
import p4.v1.P4RuntimeOuterClass.WriteResponse

/**
 * In-process test harness for the P4Runtime gRPC service.
 *
 * Starts an in-process gRPC server backed by a real [Simulator] and provides typed helpers for
 * common operations. Uses grpc-java's InProcessTransport so no real network ports are needed.
 */
class P4RuntimeTestHarness : Closeable {

  private val serverName = InProcessServerBuilder.generateName()
  private val simulator = Simulator()
  private val service = P4RuntimeService(simulator)

  private val server =
    InProcessServerBuilder.forName(serverName).directExecutor().addService(service).build().start()

  private val channel: ManagedChannel =
    InProcessChannelBuilder.forName(serverName).directExecutor().build()

  val stub: P4RuntimeCoroutineStub = P4RuntimeCoroutineStub(channel)

  // ---------------------------------------------------------------------------
  // Pipeline management
  // ---------------------------------------------------------------------------

  fun loadPipeline(config: PipelineConfig): SetForwardingPipelineConfigResponse = runBlocking {
    stub.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
        .setConfig(
          ForwardingPipelineConfig.newBuilder()
            .setP4Info(config.p4Info)
            .setP4DeviceConfig(config.behavioral.toByteString())
        )
        .build()
    )
  }

  fun loadPipeline(configPath: Path): SetForwardingPipelineConfigResponse {
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(configPath.toFile().readText(), builder)
    return loadPipeline(builder.build())
  }

  // ---------------------------------------------------------------------------
  // Table entry management
  // ---------------------------------------------------------------------------

  fun installEntry(entity: Entity): WriteResponse = runBlocking {
    stub.write(
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .addUpdates(Update.newBuilder().setType(Update.Type.INSERT).setEntity(entity))
        .build()
    )
  }

  fun modifyEntry(entity: Entity): WriteResponse = runBlocking {
    stub.write(
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .addUpdates(Update.newBuilder().setType(Update.Type.MODIFY).setEntity(entity))
        .build()
    )
  }

  fun deleteEntry(entity: Entity): WriteResponse = runBlocking {
    stub.write(
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .addUpdates(Update.newBuilder().setType(Update.Type.DELETE).setEntity(entity))
        .build()
    )
  }

  fun readEntries(request: ReadRequest = ReadRequest.getDefaultInstance()): List<Entity> =
    runBlocking {
      val entities = mutableListOf<Entity>()
      stub.read(request).collect { response -> entities.addAll(response.entitiesList) }
      entities
    }

  // ---------------------------------------------------------------------------
  // StreamChannel helpers
  // ---------------------------------------------------------------------------

  /** Opens a persistent bidirectional stream. Callers should use [Closeable.use] for cleanup. */
  fun openStream(): StreamSession = StreamSession()

  /**
   * Convenience: opens a stream, arbitrates, sends one PacketOut, and returns the responses.
   *
   * Use [openStream] for multi-packet scenarios.
   */
  fun sendPacketViaStream(
    payload: ByteArray,
    ingressPort: Int = 0,
    expectedResponses: Int = 2,
  ): List<StreamMessageResponse> =
    openStream().use { session ->
      val responses = mutableListOf<StreamMessageResponse>()
      responses.add(session.arbitrate())
      if (expectedResponses > 1 && payload.isNotEmpty()) {
        session.sendPacket(payload, ingressPort)?.let { responses.add(it) }
      }
      responses
    }

  /**
   * A persistent bidirectional StreamChannel session.
   *
   * Call [arbitrate] once to become master, then [sendPacket] for each packet. The session
   * maintains a single gRPC stream, avoiding re-arbitration per packet.
   */
  inner class StreamSession : Closeable {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val requestChannel = Channel<StreamMessageRequest>(Channel.UNLIMITED)
    private val responseChannel = Channel<StreamMessageResponse>(Channel.UNLIMITED)

    private val job =
      scope.launch {
        stub.streamChannel(requestChannel.consumeAsFlow()).collect { responseChannel.send(it) }
      }

    fun arbitrate(deviceId: Long = 1, electionId: Long = 1): StreamMessageResponse = runBlocking {
      requestChannel.send(
        StreamMessageRequest.newBuilder()
          .setArbitration(
            MasterArbitrationUpdate.newBuilder()
              .setDeviceId(deviceId)
              .setElectionId(Uint128.newBuilder().setHigh(0).setLow(electionId))
          )
          .build()
      )
      withTimeout(STREAM_TIMEOUT_MS) { responseChannel.receive() }
    }

    @Suppress("MagicNumber")
    fun sendPacket(payload: ByteArray, ingressPort: Int = 0): StreamMessageResponse? = runBlocking {
      requestChannel.send(
        StreamMessageRequest.newBuilder()
          .setPacket(
            PacketOut.newBuilder()
              .setPayload(ByteString.copyFrom(payload))
              .addMetadata(
                p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
                  .setMetadataId(1) // ingress_port
                  .setValue(portToBytes(ingressPort))
              )
          )
          .build()
      )
      withTimeoutOrNull(STREAM_TIMEOUT_MS) { responseChannel.receive() }
    }

    override fun close() {
      requestChannel.close()
      scope.cancel()
    }
  }

  override fun close() {
    channel.shutdownNow()
    server.shutdownNow()
  }

  companion object {
    private const val STREAM_TIMEOUT_MS = 5000L

    /** Minimum-width unsigned big-endian encoding of a port number. */
    private fun portToBytes(port: Int): ByteString {
      val bytes = ByteArray(4) { i -> (port shr ((3 - i) * 8) and 0xFF).toByte() }
      val firstNonZero = bytes.indexOfFirst { it != 0.toByte() }
      val start = if (firstNonZero < 0) 3 else firstNonZero
      return ByteString.copyFrom(bytes, start, bytes.size - start)
    }
  }
}
