package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.assertGrpcError
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import io.grpc.Status
import org.junit.After
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Spec-driven conformance tests for P4Runtime Write RPC error conditions.
 *
 * Each test encodes a specific requirement from the P4Runtime specification. The spec section and
 * error code are cited in a comment above each test.
 *
 * Uses the basic_table.p4 fixture (exact-match table with forward/drop actions).
 */
class P4RuntimeWriteErrorTest {

  private lateinit var harness: P4RuntimeTestHarness

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  private fun loadBasicTableConfig(): PipelineConfig =
    P4RuntimeTestHarness.loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  // =========================================================================
  // Preconditions
  // =========================================================================

  // P4Runtime spec §9.1: all Write operations require a forwarding pipeline to be configured.
  @Test
  fun `write without pipeline returns FAILED_PRECONDITION`() {
    val entity = Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(1)).build()
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.installEntry(entity) }
  }

  // =========================================================================
  // INSERT errors
  // =========================================================================

  // P4Runtime spec §9.1: INSERT of an entry that already exists must return ALREADY_EXISTS.
  @Test
  fun `insert existing entry returns ALREADY_EXISTS`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    assertGrpcError(Status.Code.ALREADY_EXISTS) { harness.installEntry(entry) }
  }

  // P4Runtime spec §9.1: INSERT of an entry with an unknown table_id must return NOT_FOUND.
  @Test
  fun `insert with unknown table ID returns NOT_FOUND`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entity =
      Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(99999)).build()
    assertGrpcError(Status.Code.NOT_FOUND, "unknown table ID") { harness.installEntry(entity) }
  }

  // =========================================================================
  // MODIFY errors
  // =========================================================================

  // P4Runtime spec §9.1: MODIFY of an entry that does not exist must return NOT_FOUND.
  @Test
  fun `modify non-existent entry returns NOT_FOUND`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)

    assertGrpcError(Status.Code.NOT_FOUND) { harness.modifyEntry(entry) }
  }

  // =========================================================================
  // DELETE errors
  // =========================================================================

  // P4Runtime spec §9.1: DELETE of an entry that does not exist must return NOT_FOUND.
  @Test
  fun `delete non-existent entry returns NOT_FOUND`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)

    assertGrpcError(Status.Code.NOT_FOUND) { harness.deleteEntry(entry) }
  }

  // =========================================================================
  // Lifecycle: INSERT → MODIFY → DELETE → DELETE should fail
  // =========================================================================

  // Verifies the full entry lifecycle: insert, modify, delete succeed; second delete fails.
  @Test
  fun `entry lifecycle insert-modify-delete succeeds then delete again fails`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)

    harness.installEntry(entry)
    harness.modifyEntry(buildExactEntry(config, matchValue = 0x0800, port = 2))
    harness.deleteEntry(entry)

    assertGrpcError(Status.Code.NOT_FOUND) { harness.deleteEntry(entry) }
  }

  // =========================================================================
  // Write validation errors
  // =========================================================================

  // P4Runtime spec §9.1.2: action_id must be in the table's action_refs.
  @Test
  fun `insert with unknown action ID returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setExact(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(
                      ByteString.copyFrom(longToBytes(0x0800, (matchField.bitwidth + 7) / 8))
                    )
                )
            )
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(p4.v1.P4RuntimeOuterClass.Action.newBuilder().setActionId(99999))
            )
        )
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unknown action ID") {
      harness.installEntry(entity)
    }
  }

  // P4Runtime spec §9.1.2: action parameters must match the p4info schema.
  @Test
  fun `insert with wrong param count returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    // forward expects 1 param (port); send 0 params.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setExact(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(
                      ByteString.copyFrom(longToBytes(0x0800, (matchField.bitwidth + 7) / 8))
                    )
                )
            )
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                )
            )
        )
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "expects") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: exact-only tables must have priority == 0.
  @Test
  fun `insert with nonzero priority on exact table returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setPriority(10) // Not allowed on exact-match table.
            .addMatch(
              p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setExact(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(
                      ByteString.copyFrom(longToBytes(0x0800, (matchField.bitwidth + 7) / 8))
                    )
                )
            )
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
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
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "priority") { harness.installEntry(entity) }
  }

  // P4Runtime spec §8.3: match field value must have canonical byte width.
  @Test
  fun `insert with wrong match value width returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    // etherType is 16-bit (2 bytes); send 1 byte.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .addMatch(
              p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setExact(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(byteArrayOf(0x08)))
                )
            )
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
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
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "bytes") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: match field kind must match p4info.
  @Test
  fun `insert with ternary encoding on exact field returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val matchField = table.matchFieldsList.first()
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setPriority(1)
            .addMatch(
              p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(matchField.id)
                .setTernary(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                    .setValue(
                      ByteString.copyFrom(longToBytes(0x0800, (matchField.bitwidth + 7) / 8))
                    )
                    .setMask(
                      ByteString.copyFrom(longToBytes(0xFFFF, (matchField.bitwidth + 7) / 8))
                    )
                )
            )
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
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
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "EXACT") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: exact match fields are required (cannot be omitted).
  @Test
  fun `insert with missing exact match field returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val table = config.p4Info.tablesList.first()
    val forwardAction = config.p4Info.actionsList.find { it.preamble.name.contains("forward") }!!
    // No match fields at all — the table requires an exact match.
    val entity =
      Entity.newBuilder()
        .setTableEntry(
          TableEntry.newBuilder()
            .setTableId(table.preamble.id)
            .setAction(
              p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(forwardAction.preamble.id)
                    .addParams(
                      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
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
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "missing") { harness.installEntry(entity) }
  }
}
