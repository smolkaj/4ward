package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.ErrorCode
import fourward.sim.v1.LoadPipelineRequest
import fourward.sim.v1.ProcessPacketRequest
import fourward.sim.v1.ReadEntriesRequest
import fourward.sim.v1.SimRequest
import fourward.sim.v1.WriteEntryRequest
import fourward.simulator.Simulator
import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest
import p4.v1.P4RuntimeOuterClass.CapabilitiesResponse
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.PacketIn
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse
import p4.v1.P4RuntimeOuterClass.WriteRequest
import p4.v1.P4RuntimeOuterClass.WriteResponse

/**
 * P4Runtime gRPC service backed by a 4ward [Simulator].
 *
 * Simplifications: single controller (first connection is master), synchronous packet processing,
 * no digest support.
 */
class P4RuntimeService(private val simulator: Simulator) :
  P4RuntimeGrpcKt.P4RuntimeCoroutineImplBase() {

  @Volatile private var currentConfig: PipelineConfig? = null
  @Volatile private var typeTranslator: TypeTranslator? = null

  private fun requirePipeline() {
    if (currentConfig == null) {
      throw Status.FAILED_PRECONDITION.withDescription(
          "No pipeline loaded; call SetForwardingPipelineConfig first"
        )
        .asException()
    }
  }

  // ---------------------------------------------------------------------------
  // SetForwardingPipelineConfig
  // ---------------------------------------------------------------------------

  override suspend fun setForwardingPipelineConfig(
    request: SetForwardingPipelineConfigRequest
  ): SetForwardingPipelineConfigResponse {
    val fwdConfig = request.config
    if (!fwdConfig.hasP4Info() || fwdConfig.p4DeviceConfig.isEmpty) {
      throw Status.INVALID_ARGUMENT.withDescription(
          "ForwardingPipelineConfig must include p4info and p4_device_config"
        )
        .asException()
    }

    val behavioral =
      try {
        P4BehavioralConfig.parseFrom(fwdConfig.p4DeviceConfig)
      } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
        throw Status.INVALID_ARGUMENT.withDescription(
            "p4_device_config is not a valid P4BehavioralConfig: ${e.message}"
          )
          .asException()
      }

    val pipelineConfig =
      PipelineConfig.newBuilder().setP4Info(fwdConfig.p4Info).setBehavioral(behavioral).build()

    val simRequest =
      SimRequest.newBuilder()
        .setLoadPipeline(LoadPipelineRequest.newBuilder().setConfig(pipelineConfig))
        .build()

    val simResponse = synchronized(simulator) { simulator.handle(simRequest) }
    if (simResponse.hasError()) {
      throw Status.INTERNAL.withDescription(
          "Simulator rejected pipeline: ${simResponse.error.message}"
        )
        .asException()
    }

    currentConfig = pipelineConfig
    typeTranslator = TypeTranslator.create(fwdConfig.p4Info, behavioral)
    return SetForwardingPipelineConfigResponse.getDefaultInstance()
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  override suspend fun write(request: WriteRequest): WriteResponse {
    requirePipeline()
    val translator = typeTranslator?.takeIf { it.hasTranslations }
    for (rawUpdate in request.updatesList) {
      val update = translator?.translateForWrite(rawUpdate) ?: rawUpdate
      val simRequest =
        SimRequest.newBuilder()
          .setWriteEntry(WriteEntryRequest.newBuilder().setUpdate(update))
          .build()
      val simResponse = synchronized(simulator) { simulator.handle(simRequest) }
      if (simResponse.hasError()) {
        val grpcStatus =
          when (simResponse.error.code) {
            ErrorCode.ALREADY_EXISTS -> Status.ALREADY_EXISTS
            ErrorCode.ENTITY_NOT_FOUND -> Status.NOT_FOUND
            ErrorCode.NO_PIPELINE_LOADED -> Status.FAILED_PRECONDITION
            else -> Status.INVALID_ARGUMENT
          }
        throw grpcStatus.withDescription(simResponse.error.message).asException()
      }
    }
    return WriteResponse.getDefaultInstance()
  }

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  override fun read(request: ReadRequest): Flow<ReadResponse> = flow {
    requirePipeline()
    val simRequest =
      SimRequest.newBuilder()
        .setReadEntries(ReadEntriesRequest.newBuilder().setRequest(request))
        .build()
    val simResponse = synchronized(simulator) { simulator.handle(simRequest) }
    if (simResponse.hasError()) {
      throw Status.INTERNAL.withDescription("Read failed: ${simResponse.error.message}")
        .asException()
    }
    if (simResponse.readEntries.entitiesCount > 0) {
      val translator = typeTranslator?.takeIf { it.hasTranslations }
      val entities =
        if (translator != null) {
          simResponse.readEntries.entitiesList.map { translator.translateForRead(it) }
        } else {
          simResponse.readEntries.entitiesList
        }
      emit(ReadResponse.newBuilder().addAllEntities(entities).build())
    }
  }

  // ---------------------------------------------------------------------------
  // StreamChannel
  // ---------------------------------------------------------------------------

  override fun streamChannel(requests: Flow<StreamMessageRequest>): Flow<StreamMessageResponse> =
    flow {
      requests.collect { msg ->
        when {
          msg.hasArbitration() -> {
            emit(
              StreamMessageResponse.newBuilder()
                .setArbitration(
                  MasterArbitrationUpdate.newBuilder()
                    .setDeviceId(msg.arbitration.deviceId)
                    .setElectionId(msg.arbitration.electionId)
                    .setStatus(
                      com.google.rpc.Status.newBuilder().setCode(com.google.rpc.Code.OK_VALUE)
                    )
                )
                .build()
            )
          }
          msg.hasPacket() -> {
            val packetOut = msg.packet
            // Extract ingress port from packet_out metadata (field "ingress_port", ID 0 or 1).
            val ingressPort = extractIngressPort(packetOut.metadataList)

            val simRequest =
              SimRequest.newBuilder()
                .setProcessPacket(
                  ProcessPacketRequest.newBuilder()
                    .setIngressPort(ingressPort)
                    .setPayload(packetOut.payload)
                )
                .build()

            val simResponse = synchronized(simulator) { simulator.handle(simRequest) }
            if (simResponse.hasError()) {
              return@collect
            }

            // Convert output packets to PacketIn messages.
            for (outputPacket in simResponse.processPacket.outputPacketsList) {
              emit(
                StreamMessageResponse.newBuilder()
                  .setPacket(
                    PacketIn.newBuilder()
                      .setPayload(outputPacket.payload)
                      .addMetadata(
                        p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
                          .setMetadataId(EGRESS_PORT_METADATA_ID)
                          .setValue(intToBytes(outputPacket.egressPort))
                      )
                  )
                  .build()
              )
            }
          }
        }
      }
    }

  /** Extracts ingress port from PacketOut metadata, defaulting to port 0. */
  private fun extractIngressPort(metadata: List<p4.v1.P4RuntimeOuterClass.PacketMetadata>): Int {
    val portMeta = metadata.find { it.metadataId == INGRESS_PORT_METADATA_ID }
    return portMeta?.value?.toByteArray()?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
      ?: 0
  }

  private fun intToBytes(value: Int): ByteString {
    // Minimum-width unsigned big-endian (P4Runtime canonical form).
    val bytes = ByteArray(4) { i -> (value shr ((3 - i) * 8) and 0xFF).toByte() }
    val firstNonZero = bytes.indexOfFirst { it != 0.toByte() }
    val start = if (firstNonZero < 0) 3 else firstNonZero
    return ByteString.copyFrom(bytes, start, bytes.size - start)
  }

  // ---------------------------------------------------------------------------
  // GetForwardingPipelineConfig
  // ---------------------------------------------------------------------------

  override suspend fun getForwardingPipelineConfig(
    request: GetForwardingPipelineConfigRequest
  ): GetForwardingPipelineConfigResponse {
    throw Status.UNIMPLEMENTED.withDescription("GetForwardingPipelineConfig not yet implemented")
      .asException()
  }

  // ---------------------------------------------------------------------------
  // Capabilities
  // ---------------------------------------------------------------------------

  override suspend fun capabilities(request: CapabilitiesRequest): CapabilitiesResponse {
    throw Status.UNIMPLEMENTED.withDescription("Capabilities not yet implemented").asException()
  }

  companion object {
    // Well-known metadata IDs for v1model packet_in/packet_out headers.
    private const val INGRESS_PORT_METADATA_ID = 1
    private const val EGRESS_PORT_METADATA_ID = 2
  }
}
