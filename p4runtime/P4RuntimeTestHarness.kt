package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import fourward.simulator.Simulator
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
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
import p4.v1.P4RuntimeOuterClass.CapabilitiesRequest
import p4.v1.P4RuntimeOuterClass.CapabilitiesResponse
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.PacketOut
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigResponse
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse
import p4.v1.P4RuntimeOuterClass.TableEntry
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
            .setP4DeviceConfig(config.device.toByteString())
        )
        .build()
    )
  }

  fun loadPipeline(configPath: Path): SetForwardingPipelineConfigResponse {
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(configPath.toFile().readText(), builder)
    return loadPipeline(builder.build())
  }

  fun getConfig(
    responseType: GetForwardingPipelineConfigRequest.ResponseType =
      GetForwardingPipelineConfigRequest.ResponseType.ALL
  ): GetForwardingPipelineConfigResponse = runBlocking {
    stub.getForwardingPipelineConfig(
      GetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setResponseType(responseType)
        .build()
    )
  }

  fun capabilities(): CapabilitiesResponse = runBlocking {
    stub.capabilities(CapabilitiesRequest.getDefaultInstance())
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

  /** Wildcard read: returns all table entries (table_id=0 is the P4Runtime wildcard). */
  fun readEntries(): List<Entity> = readTableEntries(0)

  /** Per-table read: returns entries from a single table (or all tables if tableId is 0). */
  fun readTableEntries(tableId: Int): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(tableId)))
        .build()
    )

  /** Per-entry read: uses the entity's match fields as a filter. */
  fun readEntry(entity: Entity): List<Entity> =
    readEntries(ReadRequest.newBuilder().setDeviceId(1).addEntities(entity).build())

  fun readEntries(request: ReadRequest): List<Entity> = runBlocking {
    val entities = mutableListOf<Entity>()
    stub.read(request).collect { response -> entities.addAll(response.entitiesList) }
    entities
  }

  /** Reads action profile members, optionally filtered by profile ID. */
  fun readProfileMembers(actionProfileId: Int = 0): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setActionProfileMember(
              p4.v1.P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(actionProfileId)
            )
        )
        .build()
    )

  /** Reads action profile groups, optionally filtered by profile ID. */
  fun readProfileGroups(actionProfileId: Int = 0): List<Entity> =
    readEntries(
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(
          Entity.newBuilder()
            .setActionProfileGroup(
              p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(actionProfileId)
            )
        )
        .build()
    )

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

    /**
     * Asserts that [block] throws a [StatusException] with the expected gRPC [code]. Optionally
     * checks that the error description contains [messageContains].
     */
    inline fun assertGrpcError(
      code: Status.Code,
      messageContains: String? = null,
      block: () -> Unit,
    ) {
      try {
        block()
        throw AssertionError("expected gRPC error $code but call succeeded")
      } catch (e: StatusException) {
        if (code != e.status.code) {
          throw AssertionError("expected gRPC status $code but got ${e.status.code}", e)
        }
        if (
          messageContains != null &&
            e.status.description?.contains(messageContains, ignoreCase = true) != true
        ) {
          throw AssertionError(
            "expected message containing '$messageContains' but got '${e.status.description}'",
            e,
          )
        }
      }
    }

    /**
     * Builds a read-filter entity: table_id + exact match key, no action. Useful as a ReadRequest
     * filter for per-entry reads.
     */
    @Suppress("MagicNumber")
    fun buildMatchFilter(config: PipelineConfig, matchValue: Long): Entity {
      val table = config.p4Info.tablesList.first()
      val matchField = table.matchFieldsList.first()
      val fieldMatch =
        p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
          .setFieldId(matchField.id)
          .setExact(
            p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(matchValue, (matchField.bitwidth + 7) / 8)))
          )
          .build()
      return Entity.newBuilder()
        .setTableEntry(
          p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(fieldMatch)
        )
        .build()
    }

    /**
     * Builds a table entry for the basic_table fixture: exact match on the table's first match
     * field → forward(port).
     */
    @Suppress("MagicNumber")
    fun buildExactEntry(config: PipelineConfig, matchValue: Long, port: Int): Entity {
      val p4info = config.p4Info
      val table = p4info.tablesList.first()
      val forwardAction = p4info.actionsList.find { it.preamble.name.contains("forward") }!!

      val filter = buildMatchFilter(config, matchValue)
      val actionParam =
        p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(forwardAction.paramsList.first().id)
          .setValue(
            ByteString.copyFrom(
              longToBytes(port.toLong(), (forwardAction.paramsList.first().bitwidth + 7) / 8)
            )
          )
          .build()

      return Entity.newBuilder()
        .setTableEntry(
          filter.tableEntry
            .toBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(actionParam)
                )
            )
        )
        .build()
    }

    /** Minimum-width unsigned big-endian encoding of a port number. */
    private fun portToBytes(port: Int): ByteString {
      val bytes = ByteArray(4) { i -> (port shr ((3 - i) * 8) and 0xFF).toByte() }
      val firstNonZero = bytes.indexOfFirst { it != 0.toByte() }
      val start = if (firstNonZero < 0) 3 else firstNonZero
      return ByteString.copyFrom(bytes, start, bytes.size - start)
    }

    /** Loads a PipelineConfig from a Bazel runfiles-relative text proto path. */
    fun loadConfig(relativePath: String): PipelineConfig {
      val r = System.getenv("JAVA_RUNFILES") ?: "."
      val path = java.nio.file.Paths.get(r, "_main/$relativePath")
      val builder = PipelineConfig.newBuilder()
      com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
      return builder.build()
    }

    /** Builds a minimal Ethernet frame: dst=FF:FF:FF:FF:FF:FF src=00:00:00:00:00:01 + etherType. */
    @Suppress("MagicNumber")
    fun buildEthernetFrame(etherType: Int): ByteArray {
      val frame = ByteArray(18) // 14-byte header + 4 bytes payload
      for (i in 0 until 6) frame[i] = 0xFF.toByte()
      frame[11] = 0x01
      frame[12] = (etherType shr 8).toByte()
      frame[13] = (etherType and 0xFF).toByte()
      frame[14] = 0xDE.toByte()
      frame[15] = 0xAD.toByte()
      frame[16] = 0xBE.toByte()
      frame[17] = 0xEF.toByte()
      return frame
    }

    /** Encodes a long value as unsigned big-endian bytes with the given byte length. */
    fun longToBytes(value: Long, byteLen: Int): ByteArray {
      val bytes = ByteArray(byteLen)
      for (i in 0 until byteLen) {
        bytes[byteLen - 1 - i] = (value shr (i * 8) and 0xFF).toByte()
      }
      return bytes
    }

    /** Builds an Entity wrapping an ActionProfileMember with a single action param. */
    fun buildMemberEntity(actionProfileId: Int, memberId: Int, actionId: Int): Entity =
      Entity.newBuilder()
        .setActionProfileMember(
          p4.v1.P4RuntimeOuterClass.ActionProfileMember.newBuilder()
            .setActionProfileId(actionProfileId)
            .setMemberId(memberId)
            .setAction(p4.v1.P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId))
        )
        .build()

    /** Builds an Entity wrapping an ActionProfileGroup with the given member IDs. */
    fun buildGroupEntity(actionProfileId: Int, groupId: Int, memberIds: List<Int>): Entity =
      Entity.newBuilder()
        .setActionProfileGroup(
          p4.v1.P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
            .setActionProfileId(actionProfileId)
            .setGroupId(groupId)
            .addAllMembers(
              memberIds.map { mid ->
                p4.v1.P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                  .setMemberId(mid)
                  .setWeight(1)
                  .build()
              }
            )
        )
        .build()
  }
}
