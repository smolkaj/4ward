package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
import fourward.p4runtime.P4RuntimeTestHarness.Companion.extractBatchErrors
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import io.grpc.Status
import io.grpc.StatusException
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import p4.v1.P4RuntimeOuterClass.Entity
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
    assertEquals("Error message mismatch for $testName", expected, actual)
  }

  // ---------------------------------------------------------------------------
  // Error triggers
  // ---------------------------------------------------------------------------

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
        .apply {
          tableEntryBuilder.actionBuilder.actionBuilder.setActionId(99999).clearParams()
        }
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
    val entity =
      valid.toBuilder().apply { tableEntryBuilder.clearMatch() }.build()
    harness.installEntry(entity)
  }

  private fun triggerDuplicateMatchField() {
    val config = loadBasicTable()
    harness.loadPipeline(config)
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    // Add the same match field a second time.
    val entity =
      valid
        .toBuilder()
        .apply { tableEntryBuilder.addMatch(valid.tableEntry.getMatch(0)) }
        .build()
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
    val bad =
      Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(99999)).build()
    val request = harness.buildBatchRequest(Update.Type.INSERT, listOf(good, bad))
    harness.writeRaw(request)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun loadBasicTable(): PipelineConfig =
    loadConfig("e2e_tests/basic_table/basic_table.txtpb")

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
      )

    private fun goldenDir(): java.nio.file.Path {
      val r = System.getenv("JAVA_RUNFILES") ?: "."
      return Paths.get(r, "_main/p4runtime/golden_errors")
    }
  }
}
