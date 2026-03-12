package fourward.p4runtime

import com.google.protobuf.Any as ProtoAny
import fourward.ir.v1.DeviceConfig
import fourward.ir.v1.PipelineConfig
import fourward.simulator.Simulator
import fourward.simulator.WriteResult
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass
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
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest
import p4.v1.P4RuntimeOuterClass.WriteResponse

/**
 * Compares two [Uint128] values as unsigned 128-bit integers. Returns negative if [a] < [b], zero
 * if equal, positive if [a] > [b].
 */
fun compareUint128(a: Uint128, b: Uint128): Int {
  val highCmp = a.high.toULong().compareTo(b.high.toULong())
  if (highCmp != 0) return highCmp
  return a.low.toULong().compareTo(b.low.toULong())
}

/**
 * P4Runtime gRPC service backed by a 4ward [Simulator].
 *
 * Supports multi-controller arbitration per P4Runtime spec §5: the controller with the highest
 * non-zero election_id is primary and may write; all controllers may read. Demotion notifications
 * are sent when a higher election_id displaces the current primary, and automatic promotion occurs
 * when the primary disconnects.
 */
class P4RuntimeService(
  private val simulator: Simulator,
  private val constraintValidatorBinary: Path? = null,
  private val lock: Mutex = Mutex(),
  private val deviceId: Long = DEFAULT_DEVICE_ID,
) : P4RuntimeGrpcKt.P4RuntimeCoroutineImplBase(), Closeable {

  /** Bundled pipeline state — atomically swapped on pipeline load to avoid torn reads. */
  private data class PipelineState(
    val config: PipelineConfig,
    val cookie: ForwardingPipelineConfig.Cookie,
    val typeTranslator: TypeTranslator?,
    val writeValidator: WriteValidator,
    val referenceValidator: ReferenceValidator?,
    val constraintValidator: ConstraintValidator?,
    val packetHeaderCodec: PacketHeaderCodec?,
    val entityReader: EntityReader,
  )

  @Volatile private var pipeline: PipelineState? = null

  // ---------------------------------------------------------------------------
  // Arbitration state (P4Runtime spec §5)
  // ---------------------------------------------------------------------------

  /** Per-stream controller state, tracked for demotion/promotion notifications. */
  private data class ControllerStream(
    val electionId: Uint128,
    val notifications: SendChannel<StreamMessageResponse>,
  )

  private val arbitrationMutex = Mutex()
  private val controllers = mutableMapOf<Any, ControllerStream>()

  // True once any controller has sent MasterArbitrationUpdate. Once set, writes require
  // a matching primary election_id (even if all controllers later disconnect).
  @Volatile private var arbitrationOccurred: Boolean = false

  // Highest non-zero election_id among active controllers, or null if none.
  @Volatile private var primaryElectionId: Uint128? = null

  private fun requirePipeline(): PipelineState =
    pipeline ?: throw Status.FAILED_PRECONDITION.withDescription(NO_PIPELINE_MESSAGE).asException()

  // ---------------------------------------------------------------------------
  // SetForwardingPipelineConfig
  // ---------------------------------------------------------------------------

  override suspend fun setForwardingPipelineConfig(
    request: SetForwardingPipelineConfigRequest
  ): SetForwardingPipelineConfigResponse =
    lock.withLock {
      requireDeviceId(request.deviceId)
      requirePrimaryOrNoArbitration(request.electionId)
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
            .withCause(e)
            .asException()
        }

      val pipelineConfig =
        PipelineConfig.newBuilder().setP4Info(fwdConfig.p4Info).setDevice(deviceConfig).build()

      try {
        simulator.loadPipeline(pipelineConfig)
      } catch (e: IllegalArgumentException) {
        throw Status.INTERNAL.withDescription("Simulator rejected pipeline: ${e.message}")
          .withCause(e)
          .asException()
      }

      pipeline?.constraintValidator?.close()
      val entityReader =
        EntityReader.create(fwdConfig.p4Info, simulator.tableNameById, simulator.actionNameById)
      pipeline =
        PipelineState(
          config = pipelineConfig,
          cookie = fwdConfig.cookie,
          typeTranslator = TypeTranslator.create(fwdConfig.p4Info, deviceConfig.translationsList),
          writeValidator = WriteValidator(pipelineConfig.p4Info),
          referenceValidator = ReferenceValidator.create(fwdConfig.p4Info),
          constraintValidator =
            constraintValidatorBinary?.let { ConstraintValidator.create(fwdConfig.p4Info, it) },
          packetHeaderCodec = PacketHeaderCodec.create(fwdConfig.p4Info, deviceConfig.behavioral),
          entityReader = entityReader,
        )
      SetForwardingPipelineConfigResponse.getDefaultInstance()
    }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  override suspend fun write(request: WriteRequest): WriteResponse =
    lock.withLock {
      requireDeviceId(request.deviceId)
      val state = requirePipeline()
      requirePrimaryOrNoArbitration(request.electionId)
      when (request.atomicity) {
        WriteRequest.Atomicity.CONTINUE_ON_ERROR,
        WriteRequest.Atomicity.UNRECOGNIZED -> writeContinueOnError(request.updatesList, state)
        // P4Runtime spec §12.2: ROLLBACK_ON_ERROR and DATAPLANE_ATOMIC both guarantee
        // all-or-none semantics. DATAPLANE_ATOMIC additionally requires data-plane atomicity,
        // which we get for free because the write lock serializes all operations.
        WriteRequest.Atomicity.ROLLBACK_ON_ERROR,
        WriteRequest.Atomicity.DATAPLANE_ATOMIC -> writeAtomic(request.updatesList, state)
      }
    }

  /**
   * CONTINUE_ON_ERROR (P4Runtime spec §12.2): attempt all updates, report per-update status.
   *
   * Per §12.3: if any update fails, the gRPC status is UNKNOWN with one [P4RuntimeOuterClass.Error]
   * per update packed into `google.rpc.Status.details`.
   */
  private fun writeContinueOnError(updates: List<Update>, state: PipelineState): WriteResponse {
    val errors = ArrayList<P4RuntimeOuterClass.Error>(updates.size)
    var hasError = false
    for (rawUpdate in updates) {
      try {
        processUpdate(rawUpdate, state)
        errors.add(P4RuntimeOuterClass.Error.newBuilder().setCanonicalCode(OK_CODE).build())
      } catch (e: StatusException) {
        hasError = true
        errors.add(
          P4RuntimeOuterClass.Error.newBuilder()
            .setCanonicalCode(e.status.code.value())
            .setMessage(e.status.description ?: "")
            .build()
        )
      }
    }
    if (hasError) {
      throw buildBatchError(errors)
    }
    return WriteResponse.getDefaultInstance()
  }

  /**
   * ROLLBACK_ON_ERROR / DATAPLANE_ATOMIC (P4Runtime spec §12.2): all-or-none semantics.
   *
   * Snapshots write-state before the batch and restores it if any update fails.
   */
  private fun writeAtomic(updates: List<Update>, state: PipelineState): WriteResponse {
    val snapshot = simulator.snapshotWriteState()
    try {
      for (rawUpdate in updates) {
        processUpdate(rawUpdate, state)
      }
    } catch (e: StatusException) {
      simulator.restoreWriteState(snapshot)
      throw e
    }
    return WriteResponse.getDefaultInstance()
  }

  /** Validates and applies a single update. Throws [StatusException] on failure. */
  private fun processUpdate(rawUpdate: Update, state: PipelineState) {
    rejectUnsupportedEntity(rawUpdate.entity)
    // Validate against p4info before type translation so SDN-visible values
    // are checked at canonical widths (P4Runtime spec §8.3, §9.1).
    if (rawUpdate.entity.hasTableEntry()) {
      state.writeValidator.validate(rawUpdate)
    }
    val translator = state.typeTranslator?.takeIf { it.hasTranslations }
    val update = translator?.translateForWrite(rawUpdate) ?: rawUpdate

    // Validate @refers_to referential integrity after translation so values
    // are in dataplane form (matching what's stored in the simulator).
    state.referenceValidator?.validate(
      update,
      simulator::hasTableEntryWithFieldValue,
      simulator::hasMulticastGroup,
    )

    // Validate constraints before forwarding to the simulator.
    // Skip DELETE — you can always remove an entry regardless of constraints.
    val validator = state.constraintValidator
    if (validator != null && update.entity.hasTableEntry() && update.type != Update.Type.DELETE) {
      val violation = validator.validateEntry(update.entity.tableEntry)
      if (violation != null) {
        throw Status.INVALID_ARGUMENT.withDescription(violation).asException()
      }
    }

    when (val result = simulator.writeEntry(update)) {
      is WriteResult.Success -> {}
      is WriteResult.AlreadyExists ->
        throw Status.ALREADY_EXISTS.withDescription(result.message).asException()
      is WriteResult.NotFound ->
        throw Status.NOT_FOUND.withDescription(result.message).asException()
      is WriteResult.InvalidArgument ->
        throw Status.INVALID_ARGUMENT.withDescription(result.message).asException()
      is WriteResult.ResourceExhausted ->
        throw Status.RESOURCE_EXHAUSTED.withDescription(result.message).asException()
    }
  }

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  override fun read(request: ReadRequest): Flow<ReadResponse> = flow {
    // Acquire the lock for the entire read (pipeline check + read + translation)
    // so the pipeline can't be swapped mid-read.
    val response =
      lock.withLock {
        requireDeviceId(request.deviceId)
        val state = requirePipeline()
        // Table entries are assembled by EntityReader (P4Runtime presentation layer);
        // all other entity types are read directly from the simulator.
        val entities =
          request.entitiesList.flatMap { entity ->
            if (entity.hasTableEntry()) {
              state.entityReader.readTableEntities(entity.tableEntry, simulator)
            } else {
              simulator.readEntries(listOf(entity))
            }
          }
        if (entities.isNotEmpty()) {
          val translator = state.typeTranslator?.takeIf { it.hasTranslations }
          if (translator != null) {
            entities.map { translator.translateForRead(it) }
          } else {
            entities
          }
        } else {
          null
        }
      }
    if (response != null) {
      emit(ReadResponse.newBuilder().addAllEntities(response).build())
    }
  }

  // ---------------------------------------------------------------------------
  // StreamChannel
  // ---------------------------------------------------------------------------

  override fun streamChannel(requests: Flow<StreamMessageRequest>): Flow<StreamMessageResponse> =
    channelFlow {
      val streamId = Any()
      val notifications = Channel<StreamMessageResponse>(Channel.UNLIMITED)

      // Forward async notifications (demotion/promotion) to the stream output.
      launch { for (msg in notifications) send(msg) }

      try {
        requests.collect { msg ->
          when {
            msg.hasArbitration() ->
              send(handleArbitration(streamId, msg.arbitration, notifications))
            msg.hasPacket() -> {
              val packetIns = lock.withLock { handlePacketOut(msg.packet) }
              packetIns?.forEach { send(it) }
            }
            msg.hasDigestAck() ->
              throw Status.UNIMPLEMENTED.withDescription(DIGEST_NOT_SUPPORTED).asException()
            // P4Runtime spec §16: unrecognized stream messages get an error response.
            else ->
              send(
                StreamMessageResponse.newBuilder()
                  .setError(
                    P4RuntimeOuterClass.StreamError.newBuilder()
                      .setCanonicalCode(com.google.rpc.Code.INVALID_ARGUMENT_VALUE)
                      .setMessage("unrecognized stream message")
                      .setOther(P4RuntimeOuterClass.StreamOtherError.getDefaultInstance())
                  )
                  .build()
              )
          }
        }
      } finally {
        notifications.close()
        handleDisconnect(streamId)
      }
    }

  /**
   * Processes a PacketOut: translates metadata, simulates, and returns PacketIn responses. Must be
   * called under [lock].
   */
  private fun handlePacketOut(
    packet: p4.v1.P4RuntimeOuterClass.PacketOut
  ): List<StreamMessageResponse>? {
    val state = pipeline ?: return null
    val translator = state.typeTranslator?.takeIf { it.hasTranslations }
    val codec = state.packetHeaderCodec
    val packetOut = translator?.translatePacketOut(packet) ?: packet

    // If the pipeline has a packet_out header, serialize metadata into a binary
    // header and prepend it. The parser expects the packet to arrive on the CPU
    // port with the header prepended.
    val (ingressPort, payload) =
      if (codec != null) {
        val header = codec.serializePacketOut(packetOut.metadataList)
        codec.cpuPort to (header + packetOut.payload.toByteArray())
      } else {
        extractIngressPort(packetOut.metadataList) to packetOut.payload.toByteArray()
      }

    val result = simulator.processPacket(ingressPort, payload)

    return result.outputPackets.map { outputPacket ->
      val rawPacketIn =
        if (codec != null) {
          val metadata = codec.buildPacketInMetadata(ingressPort, outputPacket.egressPort)
          PacketIn.newBuilder().setPayload(outputPacket.payload).addAllMetadata(metadata).build()
        } else {
          PacketIn.newBuilder()
            .setPayload(outputPacket.payload)
            .addMetadata(
              p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
                .setMetadataId(EGRESS_PORT_METADATA_ID)
                .setValue(encodeMinWidth(outputPacket.egressPort))
            )
            .build()
        }
      StreamMessageResponse.newBuilder()
        .setPacket(translator?.translatePacketIn(rawPacketIn) ?: rawPacketIn)
        .build()
    }
  }

  /** Extracts ingress port from PacketOut metadata, defaulting to port 0. */
  private fun extractIngressPort(metadata: List<p4.v1.P4RuntimeOuterClass.PacketMetadata>): Int {
    val portMeta = metadata.find { it.metadataId == INGRESS_PORT_METADATA_ID }
    return portMeta?.value?.toUnsignedInt() ?: 0
  }

  // ---------------------------------------------------------------------------
  // GetForwardingPipelineConfig
  // ---------------------------------------------------------------------------

  override suspend fun getForwardingPipelineConfig(
    request: GetForwardingPipelineConfigRequest
  ): GetForwardingPipelineConfigResponse {
    requireDeviceId(request.deviceId)
    val state = requirePipeline()

    val fwdConfig = ForwardingPipelineConfig.newBuilder().setCookie(state.cookie)
    when (request.responseType) {
      GetForwardingPipelineConfigRequest.ResponseType.ALL,
      GetForwardingPipelineConfigRequest.ResponseType.UNRECOGNIZED -> {
        fwdConfig.setP4Info(state.config.p4Info)
        fwdConfig.setP4DeviceConfig(state.config.device.toByteString())
      }
      GetForwardingPipelineConfigRequest.ResponseType.COOKIE_ONLY -> {}
      GetForwardingPipelineConfigRequest.ResponseType.P4INFO_AND_COOKIE -> {
        fwdConfig.setP4Info(state.config.p4Info)
      }
      GetForwardingPipelineConfigRequest.ResponseType.DEVICE_CONFIG_AND_COOKIE -> {
        fwdConfig.setP4DeviceConfig(state.config.device.toByteString())
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
   * P4Runtime spec §5: only the primary (highest non-zero election_id) may write. If no arbitration
   * has occurred, writes are allowed for backward compatibility with single-controller clients.
   */
  private fun requirePrimaryOrNoArbitration(requestElectionId: Uint128) {
    if (!arbitrationOccurred) return
    val primary = primaryElectionId
    if (primary == null || requestElectionId != primary) {
      val id =
        primary?.let { if (it.high == 0L) "${it.low}" else "(${it.high}, ${it.low})" } ?: "none"
      throw Status.PERMISSION_DENIED.withDescription(
          "only the primary controller (election_id=$id) may write"
        )
        .asException()
    }
  }

  // ---------------------------------------------------------------------------
  // Arbitration logic (P4Runtime spec §5)
  // ---------------------------------------------------------------------------

  /**
   * Processes a MasterArbitrationUpdate from a stream, updating controller tracking and sending
   * demotion/promotion notifications as needed.
   */
  private suspend fun handleArbitration(
    streamId: Any,
    arbitration: MasterArbitrationUpdate,
    notifications: SendChannel<StreamMessageResponse>,
  ): StreamMessageResponse {
    val incomingId = arbitration.electionId

    return arbitrationMutex.withLock {
      val oldPrimaryId = primaryElectionId
      controllers[streamId] = ControllerStream(incomingId, notifications)
      arbitrationOccurred = true

      val newPrimaryId = computePrimary()
      if (newPrimaryId != null) primaryElectionId = newPrimaryId

      // If primary changed, send demotion/promotion notifications to other streams.
      if (newPrimaryId != oldPrimaryId) {
        notifyRoleChanges(streamId, oldPrimaryId, newPrimaryId)
      }

      val isPrimary =
        !isZeroElectionId(incomingId) && newPrimaryId != null && incomingId == newPrimaryId
      buildArbitrationResponse(arbitration.deviceId, incomingId, isPrimary)
    }
  }

  /** Handles stream disconnect: removes the controller and promotes the next primary if needed. */
  private suspend fun handleDisconnect(streamId: Any) {
    arbitrationMutex.withLock {
      controllers.remove(streamId) ?: return

      val oldPrimaryId = primaryElectionId
      val newPrimaryId = computePrimary()
      if (newPrimaryId != null) primaryElectionId = newPrimaryId

      // If the disconnected controller was primary, notify the promoted controller.
      if (oldPrimaryId != null && newPrimaryId != oldPrimaryId && newPrimaryId != null) {
        for ((_, ctrl) in controllers) {
          if (ctrl.electionId == newPrimaryId) {
            ctrl.notifications.trySend(buildArbitrationResponse(deviceId, ctrl.electionId, true))
          }
        }
      }
    }
  }

  /** Returns the highest non-zero election_id among active controllers, or null. */
  private fun computePrimary(): Uint128? =
    controllers.values
      .map { it.electionId }
      .filter { !isZeroElectionId(it) }
      .maxWithOrNull(Comparator { a, b -> compareUint128(a, b) })

  /** Sends demotion/promotion notifications to other streams when the primary changes. */
  private fun notifyRoleChanges(
    excludeStreamId: Any,
    oldPrimaryId: Uint128?,
    newPrimaryId: Uint128?,
  ) {
    for ((id, ctrl) in controllers) {
      if (id == excludeStreamId) continue
      val wasPrimary = oldPrimaryId != null && ctrl.electionId == oldPrimaryId
      val isNowPrimary = newPrimaryId != null && ctrl.electionId == newPrimaryId
      when {
        wasPrimary && !isNowPrimary ->
          ctrl.notifications.trySend(buildArbitrationResponse(deviceId, ctrl.electionId, false))
        !wasPrimary && isNowPrimary ->
          ctrl.notifications.trySend(buildArbitrationResponse(deviceId, ctrl.electionId, true))
      }
    }
  }

  private fun buildArbitrationResponse(
    deviceId: Long,
    electionId: Uint128,
    isPrimary: Boolean,
  ): StreamMessageResponse =
    StreamMessageResponse.newBuilder()
      .setArbitration(
        MasterArbitrationUpdate.newBuilder()
          .setDeviceId(deviceId)
          .setElectionId(electionId)
          .setStatus(
            com.google.rpc.Status.newBuilder()
              .setCode(
                if (isPrimary) com.google.rpc.Code.OK_VALUE
                else com.google.rpc.Code.ALREADY_EXISTS_VALUE
              )
          )
      )
      .build()

  /** Rejects requests targeting a different device (P4Runtime spec §6.3). */
  private fun requireDeviceId(requestDeviceId: Long) {
    if (requestDeviceId != deviceId) {
      throw Status.NOT_FOUND.withDescription(
          "unknown device_id $requestDeviceId (this device is $deviceId)"
        )
        .asException()
    }
  }

  // ---------------------------------------------------------------------------
  // Unsupported feature guards
  // ---------------------------------------------------------------------------

  /** Rejects entity types that are documented but not implemented. */
  private fun rejectUnsupportedEntity(entity: P4RuntimeOuterClass.Entity) {
    if (entity.hasDigestEntry()) {
      throw Status.UNIMPLEMENTED.withDescription(DIGEST_NOT_SUPPORTED).asException()
    }
  }

  // ---------------------------------------------------------------------------
  // Batch error reporting (P4Runtime spec §12.3)
  // ---------------------------------------------------------------------------

  /**
   * Builds a gRPC UNKNOWN error with per-update [P4RuntimeOuterClass.Error] details.
   *
   * The `google.rpc.Status` is attached via the standard `grpc-status-details-bin` trailer so
   * clients can extract per-update results (P4Runtime spec §10, §12.3).
   */
  private fun buildBatchError(errors: List<P4RuntimeOuterClass.Error>): StatusException {
    val rpcStatus =
      com.google.rpc.Status.newBuilder()
        .setCode(com.google.rpc.Code.UNKNOWN_VALUE)
        .setMessage("Write failure.")
    for (error in errors) {
      rpcStatus.addDetails(ProtoAny.pack(error))
    }
    val metadata = Metadata()
    metadata.put(STATUS_DETAILS_KEY, rpcStatus.build().toByteArray())
    return Status.UNKNOWN.withDescription("Write failure.").asException(metadata)
  }

  override fun close() {
    pipeline?.constraintValidator?.close()
  }

  companion object {
    private const val DEFAULT_DEVICE_ID = 1L

    // Well-known metadata IDs for v1model packet_in/packet_out headers.
    private const val INGRESS_PORT_METADATA_ID = 1
    private const val EGRESS_PORT_METADATA_ID = 2

    // Matches the p4runtime proto version declared in MODULE.bazel.
    private const val P4RUNTIME_API_VERSION = "1.5.0"

    private const val NO_PIPELINE_MESSAGE =
      "No pipeline loaded; call SetForwardingPipelineConfig first"

    private const val DIGEST_NOT_SUPPORTED = "digest is not supported"

    private const val OK_CODE = com.google.rpc.Code.OK_VALUE

    private fun isZeroElectionId(id: Uint128): Boolean = id.high == 0L && id.low == 0L

    // Standard gRPC binary trailer for rich error details (P4Runtime spec §10).
    private val STATUS_DETAILS_KEY: Metadata.Key<ByteArray> =
      Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER)
  }
}
