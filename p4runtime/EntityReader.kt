package fourward.p4runtime

import fourward.simulator.Simulator
import fourward.simulator.sameKey
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Assembles P4Runtime [Entity] protos for table entry reads.
 *
 * Owns the p4info ID↔name mappings needed for proto construction; delegates to [Simulator] for raw
 * data-plane state (entries, default actions, direct counter/meter data).
 *
 * Created once per pipeline load and bundled into [P4RuntimeService]'s `PipelineState`.
 */
class EntityReader
private constructor(
  private val tableNameById: Map<Int, String>,
  private val tableIdByName: Map<String, Int>,
  private val actionIdByName: Map<String, Int>,
  private val noActionId: Int,
) {

  /**
   * Returns table entries as [Entity] protos, filtered by [filter].
   * - `table_id=0`, no match fields: wildcard — all entries from all tables.
   * - `table_id=N`, no match fields: all entries from table N.
   * - `table_id=N` with match fields: single entry matching the key (P4Runtime spec §11.1).
   */
  fun readTableEntities(filter: TableEntry, simulator: Simulator): List<Entity> {
    val tableIds =
      if (filter.tableId == 0) tableIdByName.values else listOfNotNull(filter.tableId)
    val hasMatchFilter = filter.matchCount > 0
    val result = mutableListOf<Entity>()

    for (tableId in tableIds) {
      val tableName = tableNameById[tableId] ?: continue
      val hasDirectCounter = simulator.hasDirectCounter(tableName)
      val hasDirectMeter = simulator.hasDirectMeter(tableName)
      val entries = simulator.getTableEntries(tableName)

      // Regular entries.
      val matched =
        if (hasMatchFilter) listOfNotNull(entries.find { it.sameKey(filter) }) else entries
      for (entry in matched) {
        result.add(buildTableEntryEntity(entry, hasDirectCounter, hasDirectMeter, simulator))
      }

      // P4Runtime spec §11.1: wildcard and per-table reads include the default entry.
      if (!hasMatchFilter) {
        buildDefaultEntryEntity(tableName, tableId, simulator)?.let { result.add(it) }
      }
    }
    return result
  }

  /** Wraps a [TableEntry] in an [Entity], attaching direct counter/meter data if applicable. */
  private fun buildTableEntryEntity(
    entry: TableEntry,
    hasDirectCounter: Boolean,
    hasDirectMeter: Boolean,
    simulator: Simulator,
  ): Entity {
    if (!hasDirectCounter && !hasDirectMeter) {
      return Entity.newBuilder().setTableEntry(entry).build()
    }
    // P4Runtime spec §9.1: table entry reads include direct counter/meter data.
    val builder = entry.toBuilder()
    if (hasDirectCounter) {
      builder.counterData =
        simulator.getDirectCounterData(entry)
          ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
    }
    if (hasDirectMeter) {
      simulator.getDirectMeterData(entry)?.let { builder.meterConfig = it }
    }
    return Entity.newBuilder().setTableEntry(builder).build()
  }

  /** Builds the default entry [Entity] for a table, or null if no default action is resolvable. */
  private fun buildDefaultEntryEntity(
    tableName: String,
    tableId: Int,
    simulator: Simulator,
  ): Entity? {
    val default = simulator.getDefaultAction(tableName)
    val actionId = default?.let { actionIdByName[it.name] } ?: noActionId
    if (actionId == 0) return null
    val actionBuilder = P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId)
    default?.params?.forEach { actionBuilder.addParams(it) }
    return Entity.newBuilder()
      .setTableEntry(
        TableEntry.newBuilder()
          .setTableId(tableId)
          .setIsDefaultAction(true)
          .setAction(P4RuntimeOuterClass.TableAction.newBuilder().setAction(actionBuilder))
      )
      .build()
  }

  companion object {
    /**
     * Builds an [EntityReader] from the simulator's name resolution maps and p4info.
     *
     * Called after [Simulator.loadPipeline] so the name maps are already populated.
     */
    fun create(
      p4info: P4Info,
      tableNameById: Map<Int, String>,
      actionNameById: Map<Int, String>,
    ): EntityReader {
      val tableIdByName = tableNameById.entries.associate { (id, name) -> name to id }
      val actionIdByName = actionNameById.entries.associate { (id, name) -> name to id }
      val noActionId =
        p4info.actionsList.find { it.preamble.name == "NoAction" }?.preamble?.id ?: 0
      return EntityReader(tableNameById, tableIdByName, actionIdByName, noActionId)
    }
  }
}
