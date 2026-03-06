package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.assertGrpcError
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
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

  /** Builds a valid exact entry and applies a mutation to produce an invalid one. */
  private fun buildInvalidEntry(config: PipelineConfig, mutate: (Entity.Builder) -> Unit): Entity {
    val valid = buildExactEntry(config, matchValue = 0x0800, port = 1)
    return valid.toBuilder().apply(mutate).build()
  }

  // P4Runtime spec §9.1.2: action_id must be in the table's action_refs.
  @Test
  fun `insert with unknown action ID returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder.actionBuilder.actionBuilder.setActionId(99999).clearParams()
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "unknown action ID") {
      harness.installEntry(entity)
    }
  }

  // P4Runtime spec §9.1.2: action parameters must match the p4info schema.
  @Test
  fun `insert with wrong param count returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // forward expects 1 param (port); send 0 params.
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder.actionBuilder.actionBuilder.clearParams()
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "expects") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: exact-only tables must have priority == 0.
  @Test
  fun `insert with nonzero priority on exact table returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entity = buildInvalidEntry(config) { b -> b.tableEntryBuilder.setPriority(10) }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "priority") { harness.installEntry(entity) }
  }

  // P4Runtime spec §8.3: match field value must have canonical byte width.
  @Test
  fun `insert with wrong match value width returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // etherType is 16-bit (2 bytes); send 1 byte.
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder
          .getMatchBuilder(0)
          .exactBuilder
          .setValue(ByteString.copyFrom(byteArrayOf(0x08)))
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "bytes") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: match field kind must match p4info.
  @Test
  fun `insert with ternary encoding on exact field returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val matchField = config.p4Info.tablesList.first().matchFieldsList.first()
    val byteWidth = (matchField.bitwidth + 7) / 8
    val entity =
      buildInvalidEntry(config) { b ->
        b.tableEntryBuilder.setPriority(1)
        b.tableEntryBuilder
          .getMatchBuilder(0)
          .clearExact()
          .setFieldId(matchField.id)
          .ternaryBuilder
          .setValue(ByteString.copyFrom(P4RuntimeTestHarness.longToBytes(0x0800, byteWidth)))
          .setMask(ByteString.copyFrom(P4RuntimeTestHarness.longToBytes(0xFFFF, byteWidth)))
      }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "EXACT") { harness.installEntry(entity) }
  }

  // P4Runtime spec §9.1.1: exact match fields are required (cannot be omitted).
  @Test
  fun `insert with missing exact match field returns INVALID_ARGUMENT`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // No match fields at all — the table requires an exact match.
    val entity = buildInvalidEntry(config) { b -> b.tableEntryBuilder.clearMatch() }
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "missing") { harness.installEntry(entity) }
  }
}
