package fourward.simulator

import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.SimulatorProto.OutputPacket
import fourward.sim.v1.SimulatorProto.ProcessPacketResponse
import fourward.sim.v1.SimulatorProto.TraceTree

/**
 * The top-level simulator state machine.
 *
 * Holds the loaded pipeline configuration and all mutable data-plane state (table entries,
 * counter/meter/register values).
 *
 * Public methods: [loadPipeline], [processPacket], [writeEntry], [readEntries]. These use natural
 * Kotlin error handling (exceptions, sealed results).
 *
 * One [Simulator] instance runs for the lifetime of the process; it is single-threaded (callers
 * must serialise concurrent requests).
 */
class Simulator {

  private var pipeline: PipelineConfig? = null
  private var architecture: Architecture? = null
  private val tableStore = TableStore()

  /**
   * Installs a compiled P4 program. Must be called before [processPacket].
   *
   * Replaces the current program and clears all table entries.
   *
   * @throws IllegalArgumentException if the architecture is unsupported.
   */
  fun loadPipeline(config: PipelineConfig) {
    pipeline = config

    tableStore.loadMappings(config.p4Info, config.device)

    architecture =
      when (val archName = config.device.behavioral.architecture.name) {
        "v1model" -> V1ModelArchitecture()
        "psa" -> PSAArchitecture()
        else -> throw IllegalArgumentException("unsupported architecture: $archName")
      }
  }

  /**
   * Processes a single packet through the pipeline.
   *
   * @throws IllegalStateException if no pipeline is loaded.
   */
  fun processPacket(ingressPort: Int, payload: ByteArray): ProcessPacketResponse {
    val config = checkNotNull(pipeline) { "no pipeline loaded" }
    val arch = checkNotNull(architecture) { "no pipeline loaded" }

    val result =
      arch.processPacket(
        ingressPort = ingressPort.toUInt(),
        payload = payload,
        config = config.device.behavioral,
        tableStore = tableStore,
      )

    // Output packets are extracted from trace tree leaves — the tree is the single source
    // of truth for packet outcomes. Non-forking trees have a single leaf; forking trees
    // (multicast, clone) have multiple leaves whose outputs are collected recursively.
    val trace = result.trace
    return ProcessPacketResponse.newBuilder()
      .setTrace(trace)
      .addAllOutputPackets(collectOutputsFromTrace(trace))
      .build()
  }

  /**
   * Writes a table entry (insert, modify, or delete).
   *
   * @throws IllegalStateException if no pipeline is loaded.
   */
  fun writeEntry(update: p4.v1.P4RuntimeOuterClass.Update): WriteResult {
    checkNotNull(pipeline) { "no pipeline loaded" }
    return tableStore.write(update)
  }

  /** Captures a snapshot of all mutable write-state for rollback. */
  fun snapshotWriteState(): TableStore.WriteState = tableStore.snapshot()

  /** Restores write-state to a previously captured snapshot. */
  fun restoreWriteState(snapshot: TableStore.WriteState) = tableStore.restore(snapshot)

  /**
   * Returns true if the table with p4info [tableId] has an entry with match field [fieldId] equal
   * to [value].
   *
   * Used by `@refers_to` referential integrity validation.
   */
  fun hasTableEntryWithFieldValue(
    tableId: Int,
    fieldId: Int,
    value: com.google.protobuf.ByteString,
  ): Boolean = tableStore.hasEntryWithFieldValue(tableId, fieldId, value)

  /** Returns true if a multicast group with [groupId] exists in the PRE. */
  fun hasMulticastGroup(groupId: Int): Boolean = tableStore.getMulticastGroup(groupId) != null

  /**
   * Reads entities matching the given filters.
   *
   * P4Runtime spec §11.1: each entity in the list is a filter; the result is the union.
   */
  fun readEntries(
    filters: List<p4.v1.P4RuntimeOuterClass.Entity>
  ): List<p4.v1.P4RuntimeOuterClass.Entity> =
    filters.flatMap { entity ->
      when {
        entity.hasTableEntry() -> tableStore.readEntities(entity.tableEntry)
        entity.hasActionProfileMember() -> tableStore.readProfileMembers(entity.actionProfileMember)
        entity.hasActionProfileGroup() -> tableStore.readProfileGroups(entity.actionProfileGroup)
        entity.hasRegisterEntry() -> tableStore.readRegisterEntries(entity.registerEntry)
        entity.hasCounterEntry() -> tableStore.readCounterEntries(entity.counterEntry)
        entity.hasMeterEntry() -> tableStore.readMeterEntries(entity.meterEntry)
        entity.hasDirectCounterEntry() ->
          tableStore.readDirectCounterEntries(entity.directCounterEntry)
        entity.hasDirectMeterEntry() -> tableStore.readDirectMeterEntries(entity.directMeterEntry)
        entity.hasPacketReplicationEngineEntry() ->
          tableStore.readPreEntries(entity.packetReplicationEngineEntry)
        else -> emptyList()
      }
    }
}

/** Recursively collects output packets from trace tree leaves (for forking programs). */
fun collectOutputsFromTrace(tree: TraceTree): List<OutputPacket> =
  when {
    tree.hasForkOutcome() ->
      tree.forkOutcome.branchesList.flatMap { collectOutputsFromTrace(it.subtree) }
    tree.hasPacketOutcome() && tree.packetOutcome.hasOutput() -> listOf(tree.packetOutcome.output)
    else -> emptyList()
  }
