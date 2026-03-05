package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import fourward.simulator.Simulator
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

  /**
   * Performs master arbitration over a StreamChannel, sends a PacketOut, and collects the
   * responses. Returns all StreamMessageResponse messages received (including the arbitration ack).
   */
  @Suppress("MagicNumber")
  fun sendPacketViaStream(
    payload: ByteArray,
    ingressPort: Int = 0,
    expectedResponses: Int = 2, // 1 arbitration ack + 1 packet_in per output
  ): List<StreamMessageResponse> = runBlocking {
    val requestFlow = MutableSharedFlow<StreamMessageRequest>(replay = 16)

    // Arbitration: become master.
    requestFlow.emit(
      StreamMessageRequest.newBuilder()
        .setArbitration(
          MasterArbitrationUpdate.newBuilder()
            .setDeviceId(1)
            .setElectionId(Uint128.newBuilder().setHigh(0).setLow(1))
        )
        .build()
    )

    // PacketOut.
    val packetOut =
      PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(payload))
        .addMetadata(
          p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(1) // ingress_port
            .setValue(ByteString.copyFrom(byteArrayOf(ingressPort.toByte())))
        )
        .build()
    requestFlow.emit(StreamMessageRequest.newBuilder().setPacket(packetOut).build())

    val responseFlow = stub.streamChannel(requestFlow.take(2))

    withTimeoutOrNull(STREAM_TIMEOUT_MS) { responseFlow.take(expectedResponses).toList() }
      ?: emptyList()
  }

  override fun close() {
    channel.shutdownNow()
    server.shutdownNow()
  }

  companion object {
    private const val STREAM_TIMEOUT_MS = 5000L
  }
}
