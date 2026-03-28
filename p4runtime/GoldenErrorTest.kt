package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.Architecture
import fourward.ir.BehavioralConfig
import fourward.ir.DeviceConfig
import fourward.ir.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
import fourward.p4runtime.P4RuntimeTestHarness.Companion.extractBatchErrors
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import fourward.p4runtime.P4RuntimeTestHarness.Companion.uint128
import io.grpc.Status
import io.grpc.StatusException
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * Golden tests for P4Runtime error messages.
 *
 * Each test triggers a specific error path and compares the full gRPC error description against a
 * golden file. This catches unintentional changes to user-facing error messages.
 *
 * Set `UPDATE_GOLDEN=1` to regenerate golden files from actual output.
 */
@RunWith(Parameterized::class)
class GoldenErrorTest(private val testName: String) {

  private lateinit var harness: P4RuntimeTestHarness

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  @Test
  fun test() {
    val actual = captureError { triggerError(testName) }
    val goldenPath = goldenDir().resolve("$testName.golden.txt")

    if (System.getenv("UPDATE_GOLDEN") != null) {
      goldenPath.toFile().parentFile.mkdirs()
      goldenPath.toFile().writeText(actual + "\n")
      // Also print to stdout so the message can be captured from test logs.
      println("GOLDEN[$testName]: $actual")
      return
    }

    val expected = goldenPath.toFile().readText().trim()
    if (expected != actual) {
      fail(
        "Error message mismatch for '$testName'.\n\n" +
          "Expected (golden):\n  $expected\n\n" +
          "Actual:\n  $actual\n\n" +
          "To update, run:\n" +
          "  bazel test ${System.getenv("TEST_TARGET") ?: "<test target>"}" +
          " --test_env=UPDATE_GOLDEN=1"
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Error triggers
  // ---------------------------------------------------------------------------

  @Suppress("CyclomaticComplexMethod")
  private fun triggerError(name: String) {
    when (name) {
      "no-pipeline-loaded" -> triggerNoPipelineLoaded()
      "unknown-table-id" -> triggerUnknownTableId()
      "unknown-action-id" -> triggerUnknownActionId()
      "wrong-param-count" -> triggerWrongParamCount()
      "wrong-match-width" -> triggerWrongMatchWidth()
      "wrong-match-kind" -> triggerWrongMatchKind()
      "missing-exact-field" -> triggerMissingExactField()
      "duplicate-match-field" -> triggerDuplicateMatchField()
      "const-table-write" -> triggerConstTableWrite()
      "batch-write-failure" -> triggerBatchWriteFailure()
      "invalid-device-config" -> triggerInvalidDeviceConfig()
      "missing-pipeline-config" -> triggerMissingPipelineConfig()
      "wrong-device-id" -> triggerWrongDeviceId()
      "not-primary-controller" -> triggerNotPrimaryController()
      "digest-not-supported" -> triggerDigestNotSupported()
      "extern-entry-not-supported" -> triggerExternEntryNotSupported()
      "unrecognized-atomicity" -> triggerUnrecognizedAtomicity()
      "idle-timeout-not-supported" -> triggerIdleTimeoutNotSupported()
      "default-entry-wrong-type" -> triggerDefaultEntryWrongType()
      "default-entry-with-match" -> triggerDefaultEntryWithMatch()
      "unknown-param-id" -> triggerUnknownParamId()
      "unknown-match-field-id" -> triggerUnknownMatchFieldId()
      "match-field-no-value" -> triggerMatchFieldNoValue()
      "priority-must-be-zero" -> triggerPriorityMustBeZero()
      "table-entry-already-exists" -> triggerTableEntryAlreadyExists()
      "table-entry-not-found" -> triggerTableEntryNotFound()
      "commit-without-save" -> triggerCommitWithoutSave()
      "unrecognized-pipeline-action" -> triggerUnrecognizedPipelineAction()
      "simulator-rejected-pipeline" -> triggerSimulatorRejectedPipeline()
      "param-width-mismatch" -> triggerParamWidthMismatch()
      "priority-required" -> triggerPriorityRequired()
      "ternary-masked-bits" -> triggerTernaryMaskedBits()
      "lpm-trailing-bits" -> triggerLpmTrailingBits()
      "counter-insert-not-supported" -> triggerCounterInsertNotSupported()
      "meter-insert-not-supported" -> triggerMeterInsertNotSupported()
      "register-insert-not-supported" -> triggerRegisterInsertNotSupported()
      "pre-entry-missing-type" -> triggerPreEntryMissingType()
      "clone-session-already-exists" -> triggerCloneSessionAlreadyExists()
      "clone-session-not-found" -> triggerCloneSessionNotFound()
      "multicast-group-already-exists" -> triggerMulticastGroupAlreadyExists()
      "multicast-group-not-found" -> triggerMulticastGroupNotFound()
      else -> error("unknown test: $name")
    }
  }

  private fun triggerNoPipelineLoaded() {
    val entity = Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
    harness.installEntry(entity)
  }

  private fun triggerUnknownTableId() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(99999)).build()
    harness.installEntry(entity)
  }

  private fun triggerUnknownActionId() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid
        .toBuilder()
        .apply { tableEntryBuilder.actionBuilder.actionBuilder.setActionId(99999).clearParams() }
        .build()
    harness.installEntry(entity)
  }

  private fun triggerWrongParamCount() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid
        .toBuilder()
        .apply { tableEntryBuilder.actionBuilder.actionBuilder.clearParams() }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerWrongMatchWidth() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // etherType is 16-bit (2 bytes); send 1 byte.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder
            .getMatchBuilder(0)
            .exactBuilder
            .setValue(ByteString.copyFrom(byteArrayOf(0x08)))
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerWrongMatchKind() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val matchField = config.p4Info.tablesList.first().matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.setPriority(1)
          tableEntryBuilder
            .getMatchBuilder(0)
            .clearExact()
            .setFieldId(matchField.id)
            .ternaryBuilder
            .setValue(ByteString.copyFrom(longToBytes(0x0800, byteWidth)))
            .setMask(ByteString.copyFrom(longToBytes(0xFFFF, byteWidth)))
        }
        .build()
    harness.installEntry(entity)
  }

  private fun triggerMissingExactField() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity = valid.toBuilder().apply { tableEntryBuilder.clearMatch() }.build()
    harness.installEntry(entity)
  }

  private fun triggerDuplicateMatchField() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Add the same match field a second time.
    val entity =
      valid.toBuilder().apply { tableEntryBuilder.addMatch(valid.tableEntry.getMatch(0)) }.build()
    harness.installEntry(entity)
  }

  private fun triggerConstTableWrite() {
    val config = loadConfig("e2e_tests/trace_tree/clone_with_egress.txtpb")
    harness.loadPipeline(config)
    val constTable = config.p4Info.tablesList.find { it.isConstTable }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(TableEntry.newBuilder().setTableId(constTable.preamble.id))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerBatchWriteFailure() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val good = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val bad = Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(99999)).build()
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(good, bad))
    harness.writeRaw(request)
  }

  private fun triggerInvalidDeviceConfig() = runBlocking {
    harness.stub.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
        .setConfig(
          ForwardingPipelineConfig.newBuilder()
            .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
            .setP4DeviceConfig(ByteString.copyFromUtf8("garbage"))
        )
        .build()
    )
  }

  private fun triggerMissingPipelineConfig() = runBlocking {
    harness.stub.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
        .setConfig(
          ForwardingPipelineConfig.newBuilder()
            .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
        )
        .build()
    )
  }

  @Suppress("MagicNumber")
  private fun triggerWrongDeviceId() {
    harness.loadPipeline(loadBasicTable())
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(999)
        .addUpdates(
          Update.newBuilder()
            .setType(Update.Type.INSERT)
            .setEntity(
              Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
            )
        )
        .build()
    harness.writeRaw(request)
  }

  private fun triggerNotPrimaryController() {
    harness.loadPipeline(loadBasicTable())
    // Establish a primary with election_id=5, then attempt a write with a lower election_id.
    harness.openStream().use { stream -> stream.arbitrate(electionId = 5) }
    val entry = buildExactEntry(loadBasicTable(), matchValue = 0x0800, port = 1)
    harness.installEntry(entry, uint128(low = 3))
  }

  private fun triggerDigestNotSupported() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setDigestEntry(P4RuntimeOuterClass.DigestEntry.newBuilder().setDigestId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerExternEntryNotSupported() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setExternEntry(P4RuntimeOuterClass.ExternEntry.newBuilder().setExternTypeId(1))
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnrecognizedAtomicity() {
    harness.loadPipeline(loadBasicTable())
    val request =
      WriteRequest.newBuilder()
        .setDeviceId(1)
        .setAtomicityValue(999)
        .addUpdates(
          Update.newBuilder()
            .setType(Update.Type.INSERT)
            .setEntity(
              Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
            )
        )
        .build()
    harness.writeRaw(request)
  }

  @Suppress("MagicNumber")
  private fun triggerIdleTimeoutNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity = valid.toBuilder().apply { tableEntryBuilder.setIdleTimeoutNs(1000) }.build()
    harness.installEntry(entity)
  }

  private fun triggerDefaultEntryWrongType() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val tableId = config.p4Info.tablesList.first().preamble.id
    val entity =
      Entity.newBuilder()
        .setTableEntry(TableEntry.newBuilder().setTableId(tableId).setIsDefaultAction(true))
        .build()
    // INSERT a default entry (should be MODIFY).
    harness.installEntry(entity)
  }

  private fun triggerDefaultEntryWithMatch() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // MODIFY a default entry that still has match fields set.
    val entity = valid.toBuilder().apply { tableEntryBuilder.setIsDefaultAction(true) }.build()
    harness.modifyEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownParamId() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Replace the param with one that has the correct count but wrong ID.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.actionBuilder.actionBuilder.setParams(
            0,
            valid.tableEntry.action.action.getParams(0).toBuilder().setParamId(99999),
          )
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerParamWidthMismatch() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // The 'port' param is 9-bit (2 bytes canonical); send 4 bytes instead.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.actionBuilder.actionBuilder.setParams(
            0,
            valid.tableEntry.action.action
              .getParams(0)
              .toBuilder()
              .setValue(ByteString.copyFrom(byteArrayOf(0, 0, 0, 1))),
          )
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerUnknownMatchFieldId() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entity =
      valid.toBuilder().apply { tableEntryBuilder.getMatchBuilder(0).setFieldId(99999) }.build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMatchFieldNoValue() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val matchField = config.p4Info.tablesList.first().matchFieldsList.first()
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Set a FieldMatch with only field_id — no exact/ternary/lpm/etc.
    val entity =
      valid
        .toBuilder()
        .apply {
          tableEntryBuilder.setMatch(
            0,
            P4RuntimeOuterClass.FieldMatch.newBuilder().setFieldId(matchField.id),
          )
        }
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerPriorityMustBeZero() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Exact-match table: priority must be zero.
    val entity = valid.toBuilder().apply { tableEntryBuilder.setPriority(42) }.build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerTableEntryAlreadyExists() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    // INSERT the same entry again.
    harness.installEntry(entry)
  }

  @Suppress("MagicNumber")
  private fun triggerTableEntryNotFound() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // DELETE an entry that was never inserted.
    harness.deleteEntry(entry)
  }

  private fun triggerCommitWithoutSave() = runBlocking {
    harness.stub.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.COMMIT)
        .build()
    )
  }

  private fun triggerUnrecognizedPipelineAction() = runBlocking {
    harness.stub.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.UNSPECIFIED)
        .build()
    )
  }

  private fun triggerSimulatorRejectedPipeline() {
    // Build a DeviceConfig with an unsupported architecture name. The proto parses fine
    // but the simulator rejects it at loadPipeline time.
    val deviceConfig =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder()
            .setArchitecture(Architecture.newBuilder().setName("bogus_arch"))
        )
        .build()
    val fwdConfig =
      ForwardingPipelineConfig.newBuilder()
        .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
        .setP4DeviceConfig(deviceConfig.toByteString())
        .build()
    runBlocking {
      harness.stub.setForwardingPipelineConfig(
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
          .setConfig(fwdConfig)
          .build()
      )
    }
  }

  @Suppress("MagicNumber")
  private fun triggerPriorityRequired() {
    val config = loadTernaryAcl()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setPriority(0)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setTernary(
                  P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x06, byteWidth)))
                    .setMask(ByteString.copyFrom(longToBytes(0xFF, byteWidth)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(
                          ByteString.copyFrom(
                            longToBytes(1, (forwardAction.paramsList.first().bitwidth + 7) / 8)
                          )
                        )
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerTernaryMaskedBits() {
    val config = loadTernaryAcl()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    // value=0xFF but mask=0x0F — high nibble has bits set where mask is 0.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setPriority(1)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setTernary(
                  P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0xFF, byteWidth)))
                    .setMask(ByteString.copyFrom(longToBytes(0x0F, byteWidth)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(
                          ByteString.copyFrom(
                            longToBytes(1, (forwardAction.paramsList.first().bitwidth + 7) / 8)
                          )
                        )
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerLpmTrailingBits() {
    val config = loadLpmRouting()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    // 10.0.0.255/24 — the last byte (255) extends beyond the 24-bit prefix.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setLpm(
                  P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
                    .setValue(ByteString.copyFrom(longToBytes(0x0A0000FF, byteWidth)))
                    .setPrefixLen(24)
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(forwardAction.paramsList.first().id)
                        .setValue(
                          ByteString.copyFrom(
                            longToBytes(1, (forwardAction.paramsList.first().bitwidth + 7) / 8)
                          )
                        )
                    )
                )
            )
        )
        .build()
    harness.installEntry(entity)
  }

  private fun triggerCounterInsertNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entity =
      Entity.newBuilder()
        .setCounterEntry(P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerMeterInsertNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val entity =
      Entity.newBuilder()
        .setMeterEntry(P4RuntimeOuterClass.MeterEntry.newBuilder().setMeterId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerRegisterInsertNotSupported() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    // INSERT a register entry — registers only support MODIFY.
    val entity =
      Entity.newBuilder()
        .setRegisterEntry(P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(1))
        .build()
    harness.installEntry(entity)
  }

  private fun triggerPreEntryMissingType() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.getDefaultInstance()
        )
        .build()
    harness.installEntry(entity)
  }

  private fun triggerCloneSessionAlreadyExists() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setCloneSessionEntry(
              P4RuntimeOuterClass.CloneSessionEntry.newBuilder().setSessionId(1)
            )
        )
        .build()
    harness.installEntry(entity)
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerCloneSessionNotFound() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setCloneSessionEntry(
              P4RuntimeOuterClass.CloneSessionEntry.newBuilder().setSessionId(999)
            )
        )
        .build()
    harness.deleteEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMulticastGroupAlreadyExists() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(
              P4RuntimeOuterClass.MulticastGroupEntry.newBuilder().setMulticastGroupId(42)
            )
        )
        .build()
    harness.installEntry(entity)
    harness.installEntry(entity)
  }

  @Suppress("MagicNumber")
  private fun triggerMulticastGroupNotFound() {
    harness.loadPipeline(loadBasicTable())
    val entity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(
              P4RuntimeOuterClass.MulticastGroupEntry.newBuilder().setMulticastGroupId(999)
            )
        )
        .build()
    harness.deleteEntry(entity)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun loadBasicTable(): PipelineConfig =
    loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  private fun loadTernaryAcl(): PipelineConfig =
    loadConfig("e2e_tests/ternary_acl/ternary_acl.txtpb")

  private fun loadLpmRouting(): PipelineConfig =
    loadConfig("e2e_tests/lpm_routing/lpm_routing.txtpb")

  /**
   * Captures the error description from a gRPC call.
   *
   * For single-update batches that return UNKNOWN with per-update details, unwraps the batch error
   * to get the underlying error message. For multi-update batches, captures the top-level message.
   */
  private fun captureError(block: () -> Unit): String {
    try {
      block()
      fail("expected gRPC error but call succeeded")
      error("unreachable")
    } catch (e: StatusException) {
      // For batch errors (UNKNOWN status), check if there's exactly one failing update.
      if (e.status.code == Status.Code.UNKNOWN) {
        val batchErrors = extractBatchErrors(e)
        if (batchErrors != null && batchErrors.size == 1) {
          // Single-update batch: return the per-update error message.
          return "${statusCodeName(batchErrors[0].canonicalCode)}: ${batchErrors[0].message}"
        }
        // Multi-update batch: return the top-level UNKNOWN message.
        return "${e.status.code}: ${e.status.description}"
      }
      return "${e.status.code}: ${e.status.description}"
    }
  }

  private fun statusCodeName(canonicalCode: Int): String =
    Status.Code.values().find { it.value() == canonicalCode }?.name ?: "CODE_$canonicalCode"

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testNames(): List<String> =
      listOf(
        "no-pipeline-loaded",
        "unknown-table-id",
        "unknown-action-id",
        "wrong-param-count",
        "wrong-match-width",
        "wrong-match-kind",
        "missing-exact-field",
        "duplicate-match-field",
        "const-table-write",
        "batch-write-failure",
        "invalid-device-config",
        "missing-pipeline-config",
        "wrong-device-id",
        "not-primary-controller",
        "digest-not-supported",
        "extern-entry-not-supported",
        "unrecognized-atomicity",
        "idle-timeout-not-supported",
        "default-entry-wrong-type",
        "default-entry-with-match",
        "unknown-param-id",
        "unknown-match-field-id",
        "match-field-no-value",
        "priority-must-be-zero",
        "table-entry-already-exists",
        "table-entry-not-found",
        "commit-without-save",
        "unrecognized-pipeline-action",
        "simulator-rejected-pipeline",
        "param-width-mismatch",
        "priority-required",
        "ternary-masked-bits",
        "lpm-trailing-bits",
        "counter-insert-not-supported",
        "meter-insert-not-supported",
        "register-insert-not-supported",
        "pre-entry-missing-type",
        "clone-session-already-exists",
        "clone-session-not-found",
        "multicast-group-already-exists",
        "multicast-group-not-found",
      )

    private fun goldenDir(): java.nio.file.Path {
      val r = System.getenv("JAVA_RUNFILES") ?: "."
      return Paths.get(r, "_main/p4runtime/golden_errors")
    }
  }
}
