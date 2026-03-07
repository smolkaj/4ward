package fourward.p4runtime

import fourward.ir.v1.DeviceConfig
import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.ErrorCode
import fourward.sim.v1.LoadPipelineRequest
import fourward.sim.v1.ProcessPacketRequest
import fourward.sim.v1.ReadEntriesRequest
import fourward.sim.v1.SimRequest
import fourward.sim.v1.WriteEntryRequest
import fourward.simulator.Simulator
import io.grpc.Status
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest
import p4.v1.P4RuntimeOuterClass.CapabilitiesResponse
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
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
import p4.v1.P4RuntimeOuterClass.Uint128
import p4.v1.P4RuntimeOuterClass.WriteRequest
import p4.v1.P4RuntimeOuterClass.WriteResponse

/**
 * P4Runtime gRPC service backed by a 4ward [Simulator].
 *
 * Supports basic multi-controller arbitration: the controller with the highest election_id is
 * primary and may write; all controllers may read. No demotion notifications or disconnect
 * handling.
 */
class P4RuntimeService(
  private val simulator: Simulator,
  private val constraintValidatorBinary: Path? = null,
) : P4RuntimeGrpcKt.P4RuntimeCoroutineImplBase(), Closeable {

  /** Bundled pipeline state — atomically swapped on pipeline load to avoid torn reads. */
  private data class PipelineState(
    val config: PipelineConfig,
    val typeTranslator: TypeTranslator?,
    val writeValidator: WriteValidator,
    val constraintValidator: ConstraintValidator?,
  )

  @Volatile private var pipeline: PipelineState? = null

  // P4Runtime spec §10: highest election_id is the primary controller.
  // null = no arbitration has occurred; writes are allowed for backward compatibility.
  @Volatile private var primaryElectionId: Uint128? = null

  private fun requirePipeline(): PipelineState =
    pipeline
      ?: throw Status.FAILED_PRECONDITION.withDescription(
          "No pipeline loaded; call SetForwardingPipelineConfig first"
        )
        .asException()

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

    val deviceConfig =
      try {
        DeviceConfig.parseFrom(fwdConfig.p4DeviceConfig)
      } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
        throw Status.INVALID_ARGUMENT.withDescription(
            "p4_device_config is not a valid DeviceConfig: ${e.message}"
          )
          .asException()
      }

    val pipelineConfig =
      PipelineConfig.newBuilder().setP4Info(fwdConfig.p4Info).setDevice(deviceConfig).build()

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

    pipeline?.constraintValidator?.close()
    pipeline =
      PipelineState(
        config = pipelineConfig,
        typeTranslator = TypeTranslator.create(fwdConfig.p4Info, deviceConfig.translationsList),
        writeValidator = WriteValidator(pipelineConfig.p4Info),
        constraintValidator =
          constraintValidatorBinary?.let { ConstraintValidator.create(fwdConfig.p4Info, it) },
      )
    return SetForwardingPipelineConfigResponse.getDefaultInstance()
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  override suspend fun write(request: WriteRequest): WriteResponse {
    val state = requirePipeline()
    requirePrimaryOrNoArbitration(request.electionId)
    val translator = state.typeTranslator?.takeIf { it.hasTranslations }
    val validator = state.constraintValidator
    for (rawUpdate in request.updatesList) {
      // Validate against p4info before type translation so SDN-visible values
      // are checked at canonical widths (P4Runtime spec §8.3, §9.1).
      if (rawUpdate.entity.hasTableEntry()) {
        state.writeValidator.validate(rawUpdate)
      }
      val update = translator?.translateForWrite(rawUpdate) ?: rawUpdate

      // Validate constraints before forwarding to the simulator.
      // Skip DELETE — you can always remove an entry regardless of constraints.
      if (
        validator != null &&
          update.entity.hasTableEntry() &&
          update.type != p4.v1.P4RuntimeOuterClass.Update.Type.DELETE
      ) {
        val violation = validator.validateEntry(update.entity.tableEntry)
        if (violation != null) {
          throw Status.INVALID_ARGUMENT.withDescription(violation).asException()
        }
      }

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
            ErrorCode.INVALID_REQUEST -> Status.INVALID_ARGUMENT
            ErrorCode.RESOURCE_EXHAUSTED -> Status.RESOURCE_EXHAUSTED
            ErrorCode.INTERNAL_ERROR,
            ErrorCode.ERROR_CODE_UNSPECIFIED,
            ErrorCode.UNRECOGNIZED -> Status.INTERNAL
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
    val state = requirePipeline()
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
      val translator = state.typeTranslator?.takeIf { it.hasTranslations }
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
            val incomingId = msg.arbitration.electionId
            val current = primaryElectionId
            val isPrimary = current == null || compareUint128(incomingId, current) >= 0
            if (isPrimary) primaryElectionId = incomingId
            val statusCode =
              if (isPrimary) com.google.rpc.Code.OK_VALUE
              else com.google.rpc.Code.ALREADY_EXISTS_VALUE
            emit(
              StreamMessageResponse.newBuilder()
                .setArbitration(
                  MasterArbitrationUpdate.newBuilder()
                    .setDeviceId(msg.arbitration.deviceId)
                    .setElectionId(incomingId)
                    .setStatus(com.google.rpc.Status.newBuilder().setCode(statusCode))
                )
                .build()
            )
          }
          msg.hasPacket() -> {
            val translator = pipeline?.typeTranslator?.takeIf { it.hasTranslations }
            val packetOut = translator?.translatePacketOut(msg.packet) ?: msg.packet
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
              val rawPacketIn =
                PacketIn.newBuilder()
                  .setPayload(outputPacket.payload)
                  .addMetadata(
                    p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
                      .setMetadataId(EGRESS_PORT_METADATA_ID)
                      .setValue(encodeMinWidth(outputPacket.egressPort))
                  )
                  .build()
              emit(
                StreamMessageResponse.newBuilder()
                  .setPacket(translator?.translatePacketIn(rawPacketIn) ?: rawPacketIn)
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

  // ---------------------------------------------------------------------------
  // GetForwardingPipelineConfig
  // ---------------------------------------------------------------------------

  override suspend fun getForwardingPipelineConfig(
    request: GetForwardingPipelineConfigRequest
  ): GetForwardingPipelineConfigResponse {
    val config = requirePipeline().config

    val fwdConfig = ForwardingPipelineConfig.newBuilder()
    when (request.responseType) {
      GetForwardingPipelineConfigRequest.ResponseType.ALL,
      GetForwardingPipelineConfigRequest.ResponseType.UNRECOGNIZED -> {
        fwdConfig.setP4Info(config.p4Info)
        fwdConfig.setP4DeviceConfig(config.device.toByteString())
      }
      GetForwardingPipelineConfigRequest.ResponseType.COOKIE_ONLY -> {
        // No cookie support — return empty config.
      }
      GetForwardingPipelineConfigRequest.ResponseType.P4INFO_AND_COOKIE -> {
        fwdConfig.setP4Info(config.p4Info)
      }
      GetForwardingPipelineConfigRequest.ResponseType.DEVICE_CONFIG_AND_COOKIE -> {
        fwdConfig.setP4DeviceConfig(config.device.toByteString())
      }
    }

    return GetForwardingPipelineConfigResponse.newBuilder().setConfig(fwdConfig).build()
  }

  // ---------------------------------------------------------------------------
  // Capabilities
  // ---------------------------------------------------------------------------

  override suspend fun capabilities(request: CapabilitiesRequest): CapabilitiesResponse =
    CapabilitiesResponse.newBuilder().setP4RuntimeApiVersion(P4RUNTIME_API_VERSION).build()

  /**
   * Ensures the requester is the primary controller, or that no arbitration has occurred.
   *
   * P4Runtime spec §10: only the primary (highest election_id) may write. If no arbitration has
   * occurred, writes are allowed for backward compatibility with single-controller clients.
   */
  private fun requirePrimaryOrNoArbitration(requestElectionId: Uint128) {
    val primary = primaryElectionId ?: return
    if (requestElectionId != primary) {
      throw Status.PERMISSION_DENIED.withDescription(
          "only the primary controller (election_id=${primary.low}) may write"
        )
        .asException()
    }
  }

  override fun close() {
    pipeline?.constraintValidator?.close()
  }

  companion object {

    /**
     * Compares two Uint128 values as unsigned 128-bit integers. Returns negative if a < b, zero if
     * a == b, positive if a > b.
     */
    fun compareUint128(a: Uint128, b: Uint128): Int {
      // Compare high as unsigned: convert to Long.toULong() for unsigned comparison.
      val highCmp = a.high.toULong().compareTo(b.high.toULong())
      if (highCmp != 0) return highCmp
      return a.low.toULong().compareTo(b.low.toULong())
    }

    // Well-known metadata IDs for v1model packet_in/packet_out headers.
    private const val INGRESS_PORT_METADATA_ID = 1
    private const val EGRESS_PORT_METADATA_ID = 2

    // Matches the p4runtime proto version declared in MODULE.bazel.
    private const val P4RUNTIME_API_VERSION = "1.5.0"
  }
}
