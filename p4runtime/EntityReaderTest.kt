package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.ActionDecl
import fourward.ir.v1.Architecture
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.DeviceConfig
import fourward.ir.v1.PipelineConfig
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.StageKind
import fourward.ir.v1.TableBehavior
import fourward.simulator.Simulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Unit tests for [EntityReader]'s table entry → Entity proto assembly.
 *
 * Tests the read-filtering logic (wildcard, per-table, per-entry) and default entry / direct
 * counter/meter attachment directly, without going through the P4Runtime gRPC service.
 */
class EntityReaderTest {

  private val simulator = Simulator()
  private lateinit var reader: EntityReader

  @Before
  fun setUp() {
    loadPipeline(buildP4Info())
  }

  private fun loadPipeline(p4info: P4InfoOuterClass.P4Info) {
    val config = buildPipelineConfig(p4info)
    simulator.loadPipeline(config)
    reader = EntityReader.create(p4info, simulator.tableNameById, simulator.actionNameById)
  }

  // ---------------------------------------------------------------------------
  // Wildcard reads
  // ---------------------------------------------------------------------------

  @Test
  fun `wildcard read with no entries returns only default entries`() {
    val result = readWildcard()
    // Both tables have NoAction as implicit default → default entries returned.
    assertTrue("should only contain defaults", result.all { it.tableEntry.isDefaultAction })
  }

  @Test
  fun `wildcard read returns entries from all tables`() {
    insertEntry(TABLE_A_ID, byteArrayOf(1))
    insertEntry(TABLE_B_ID, byteArrayOf(2))

    val nonDefault = readWildcard().filter { !it.tableEntry.isDefaultAction }
    assertEquals(2, nonDefault.size)
    val tableIds = nonDefault.map { it.tableEntry.tableId }.toSet()
    assertEquals(setOf(TABLE_A_ID, TABLE_B_ID), tableIds)
  }

  // ---------------------------------------------------------------------------
  // Per-table reads
  // ---------------------------------------------------------------------------

  @Test
  fun `per-table read returns only entries from that table`() {
    insertEntry(TABLE_A_ID, byteArrayOf(1))
    insertEntry(TABLE_B_ID, byteArrayOf(2))

    val result = readTable(TABLE_A_ID).filter { !it.tableEntry.isDefaultAction }
    assertEquals(1, result.size)
    assertEquals(TABLE_A_ID, result[0].tableEntry.tableId)
  }

  @Test
  fun `per-table read includes default entry`() {
    // Table A has initial_default_action = drop (set in buildP4Info).
    val result = readTable(TABLE_A_ID)
    val defaults = result.filter { it.tableEntry.isDefaultAction }
    assertEquals(1, defaults.size)
    assertEquals(TABLE_A_ID, defaults[0].tableEntry.tableId)
    assertEquals(DROP_ACTION_ID, defaults[0].tableEntry.action.action.actionId)
  }

  // ---------------------------------------------------------------------------
  // Per-entry (match filter) reads
  // ---------------------------------------------------------------------------

  @Test
  fun `match filter returns only the matching entry`() {
    insertEntry(TABLE_A_ID, byteArrayOf(1))
    insertEntry(TABLE_A_ID, byteArrayOf(2))

    val filter = entryFilter(TABLE_A_ID, byteArrayOf(1))
    val result = reader.readTableEntities(filter, simulator)
    assertEquals(1, result.size)
    assertEquals(byteArrayOf(1).toList(), matchValue(result[0]))
  }

  @Test
  fun `match filter excludes default entry`() {
    // Table A has a default action (drop), but per-entry reads should not include it.
    insertEntry(TABLE_A_ID, byteArrayOf(1))

    val filter = entryFilter(TABLE_A_ID, byteArrayOf(1))
    val result = reader.readTableEntities(filter, simulator)
    assertEquals(1, result.size)
    assertTrue("should not include default", !result[0].tableEntry.isDefaultAction)
  }

  @Test
  fun `match filter with no match returns empty`() {
    insertEntry(TABLE_A_ID, byteArrayOf(1))

    val filter = entryFilter(TABLE_A_ID, byteArrayOf(99))
    val result = reader.readTableEntities(filter, simulator)
    assertTrue(result.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Default entry assembly
  // ---------------------------------------------------------------------------

  @Test
  fun `default entry has correct table_id and is_default_action`() {
    // Table A has initial_default_action = drop (set in buildP4Info).
    val defaults = readTable(TABLE_A_ID).filter { it.tableEntry.isDefaultAction }
    assertEquals(1, defaults.size)
    val entry = defaults[0].tableEntry
    assertTrue(entry.isDefaultAction)
    assertEquals(TABLE_A_ID, entry.tableId)
    assertEquals(DROP_ACTION_ID, entry.action.action.actionId)
  }

  @Test
  fun `table with no explicit default uses NoAction`() {
    // Table B has no initial_default_action, so default falls back to NoAction.
    val defaults = readTable(TABLE_B_ID).filter { it.tableEntry.isDefaultAction }
    assertEquals(1, defaults.size)
    assertEquals(NO_ACTION_ID, defaults[0].tableEntry.action.action.actionId)
  }

  // ---------------------------------------------------------------------------
  // Direct counter/meter data
  // ---------------------------------------------------------------------------

  @Test
  fun `entry in table with direct counter includes counter data`() {
    loadPipeline(buildP4InfoWithDirectCounter())

    insertEntry(TABLE_A_ID, byteArrayOf(1))
    // Write counter data for the entry via a direct counter update.
    val counterEntry =
      P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
        .setTableEntry(entryFilter(TABLE_A_ID, byteArrayOf(1)))
        .setData(
          P4RuntimeOuterClass.CounterData.newBuilder().setByteCount(100).setPacketCount(5)
        )
        .build()
    simulator.writeEntry(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(Entity.newBuilder().setDirectCounterEntry(counterEntry))
        .build()
    )

    val result = readTable(TABLE_A_ID).filter { !it.tableEntry.isDefaultAction }
    assertEquals(1, result.size)
    assertTrue(result[0].tableEntry.hasCounterData())
    assertEquals(100, result[0].tableEntry.counterData.byteCount)
    assertEquals(5, result[0].tableEntry.counterData.packetCount)
  }

  @Test
  fun `entry in table without direct counter has no counter data`() {
    insertEntry(TABLE_A_ID, byteArrayOf(1))
    val result = readTable(TABLE_A_ID).filter { !it.tableEntry.isDefaultAction }
    assertEquals(1, result.size)
    assertTrue("should not have counter data", !result[0].tableEntry.hasCounterData())
  }

  // ---------------------------------------------------------------------------
  // Unknown table ID
  // ---------------------------------------------------------------------------

  @Test
  fun `per-table read for unknown table returns empty`() {
    val filter = TableEntry.newBuilder().setTableId(99999).build()
    assertTrue(reader.readTableEntities(filter, simulator).isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun readWildcard(): List<Entity> =
    reader.readTableEntities(TableEntry.getDefaultInstance(), simulator)

  private fun readTable(tableId: Int): List<Entity> =
    reader.readTableEntities(TableEntry.newBuilder().setTableId(tableId).build(), simulator)

  private fun insertEntry(tableId: Int, matchValue: ByteArray) {
    val entry =
      TableEntry.newBuilder()
        .setTableId(tableId)
        .addMatch(
          P4RuntimeOuterClass.FieldMatch.newBuilder()
            .setFieldId(MATCH_FIELD_ID)
            .setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(ByteString.copyFrom(matchValue))
            )
        )
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(DROP_ACTION_ID))
        )
        .build()
    val entity = Entity.newBuilder().setTableEntry(entry).build()
    simulator.writeEntry(
      Update.newBuilder().setType(Update.Type.INSERT).setEntity(entity).build()
    )
  }

  private fun entryFilter(tableId: Int, matchValue: ByteArray): TableEntry =
    TableEntry.newBuilder()
      .setTableId(tableId)
      .addMatch(
        P4RuntimeOuterClass.FieldMatch.newBuilder()
          .setFieldId(MATCH_FIELD_ID)
          .setExact(
            P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
              .setValue(ByteString.copyFrom(matchValue))
          )
      )
      .build()

  private fun matchValue(entity: Entity): List<Byte> =
    entity.tableEntry.getMatch(0).exact.value.toByteArray().toList()

  // ---------------------------------------------------------------------------
  // P4Info builders
  // ---------------------------------------------------------------------------

  /** Wraps p4info in a PipelineConfig with a minimal v1model device config. */
  private fun buildPipelineConfig(p4info: P4InfoOuterClass.P4Info): PipelineConfig {
    val arch =
      Architecture.newBuilder()
        .setName("v1model")
        .addStages(PipelineStage.newBuilder().setKind(StageKind.PARSER).setBlockName("p"))
        .addStages(PipelineStage.newBuilder().setKind(StageKind.CONTROL).setBlockName("ig"))
        .addStages(PipelineStage.newBuilder().setKind(StageKind.CONTROL).setBlockName("eg"))
        .addStages(PipelineStage.newBuilder().setKind(StageKind.DEPARSER).setBlockName("dep"))
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .setArchitecture(arch)
        .addTables(TableBehavior.newBuilder().setName(TABLE_A_NAME))
        .addTables(TableBehavior.newBuilder().setName(TABLE_B_NAME))
        .addActions(ActionDecl.newBuilder().setName("NoAction"))
        .addActions(ActionDecl.newBuilder().setName("drop"))
        .build()
    return PipelineConfig.newBuilder()
      .setP4Info(p4info)
      .setDevice(DeviceConfig.newBuilder().setBehavioral(behavioral))
      .build()
  }

  private fun buildP4Info(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(table(TABLE_A_ID, TABLE_A_NAME, initialDefaultActionId = DROP_ACTION_ID))
      .addTables(table(TABLE_B_ID, TABLE_B_NAME))
      .addActions(action(NO_ACTION_ID, "NoAction"))
      .addActions(action(DROP_ACTION_ID, "drop"))
      .build()

  private fun buildP4InfoWithDirectCounter(): P4InfoOuterClass.P4Info =
    buildP4Info().toBuilder()
      .addDirectCounters(
        P4InfoOuterClass.DirectCounter.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(DIRECT_COUNTER_ID))
          .setDirectTableId(TABLE_A_ID)
      )
      .build()

  private fun table(
    id: Int,
    name: String,
    initialDefaultActionId: Int = 0,
  ): P4InfoOuterClass.Table {
    val builder =
      P4InfoOuterClass.Table.newBuilder()
        .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setAlias(name))
    if (initialDefaultActionId != 0) {
      builder.initialDefaultAction =
        P4InfoOuterClass.TableActionCall.newBuilder()
          .setActionId(initialDefaultActionId)
          .build()
    }
    return builder.build()
  }

  private fun action(id: Int, name: String): P4InfoOuterClass.Action =
    P4InfoOuterClass.Action.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name).setAlias(name))
      .build()

  companion object {
    private const val TABLE_A_ID = 1
    private const val TABLE_A_NAME = "tableA"
    private const val TABLE_B_ID = 2
    private const val TABLE_B_NAME = "tableB"
    private const val MATCH_FIELD_ID = 1
    private const val NO_ACTION_ID = 100
    private const val DROP_ACTION_ID = 200
    private const val DIRECT_COUNTER_ID = 300
  }
}
