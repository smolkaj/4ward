package fourward.p4runtime

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
}
