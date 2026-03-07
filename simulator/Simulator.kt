package fourward.simulator

import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.SimulatorProto.ProcessPacketResponse

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

    // The behavioral IR uses its own table/action names (TableBehavior.name,
    // ActionDecl.name/current_name), which may differ from p4info aliases
    // (e.g. inlined controls: behavioral "c_t" vs p4info alias "t").
    // Resolve p4info IDs to behavioral names so the TableStore uses the same
    // keys as the interpreter.
    val behavioralTables = config.device.behavioral.tablesList.map { it.name }
    val behavioralActions =
      (config.device.behavioral.actionsList +
          config.device.behavioral.controlsList.flatMap { it.localActionsList })
        .flatMap { action -> listOfNotNull(action.name, action.currentName.ifEmpty { null }) }

    fun resolveName(alias: String, candidates: List<String>): String =
      candidates.find { it == alias } ?: candidates.find { it.endsWith("_$alias") } ?: alias

    val tableNameById =
      config.p4Info.tablesList.associate { table ->
        val alias = table.preamble.alias.ifEmpty { table.preamble.name }
        table.preamble.id to resolveName(alias, behavioralTables)
      }
    val actionNameById =
      config.p4Info.actionsList.associate { action ->
        val alias = action.preamble.alias.ifEmpty { action.preamble.name }
        action.preamble.id to resolveName(alias, behavioralActions)
      }
    tableStore.loadMappings(
      tableNameById,
      actionNameById,
      config.p4Info.tablesList,
      config.p4Info.registersList,
      config.p4Info.actionProfilesList,
      config.p4Info.countersList,
      config.p4Info.metersList,
    )

    for (table in config.p4Info.tablesList) {
      // const_default_action_id: immutable default set in the P4 source with `const`.
      // initial_default_action: mutable default set in the P4 source without `const`.
      val defaultActionId =
        if (table.constDefaultActionId != 0) table.constDefaultActionId
        else if (table.hasInitialDefaultAction()) table.initialDefaultAction.actionId else 0
      if (defaultActionId != 0) {
        val tableName = tableNameById[table.preamble.id] ?: continue
        val actionName = actionNameById[defaultActionId] ?: "NoAction"
        // Convert p4info TableActionCall.Argument to P4Runtime Action.Param.
        val params =
          if (table.hasInitialDefaultAction())
            table.initialDefaultAction.argumentsList.map { arg ->
              p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(arg.paramId)
                .setValue(arg.value)
                .build()
            }
          else emptyList()
        tableStore.setDefaultAction(tableName, actionName, params)
      }
    }

    // Install static table entries declared with `const entries` in the P4
    // source. These are serialized by p4c's P4Runtime serializer and embedded
    // in the PipelineConfig at compile time.
    for (update in config.device.staticEntries.updatesList) {
      tableStore.write(update)
    }

    architecture =
      when (val archName = config.device.behavioral.architecture.name) {
        "v1model" -> V1ModelArchitecture()
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
    // (non-deterministic programs) have no output_packets in the response.
    val trace = result.trace
    val responseBuilder = ProcessPacketResponse.newBuilder().setTrace(trace)
    if (trace.hasPacketOutcome() && trace.packetOutcome.hasOutput()) {
      responseBuilder.addOutputPackets(trace.packetOutcome.output)
    }
    return responseBuilder.build()
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
        else -> emptyList()
      }
    }
}
