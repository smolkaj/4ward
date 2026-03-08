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

    val behavioral = config.device.behavioral
    tableStore.loadMappings(
      p4info = config.p4Info,
      behavioralTableNames = behavioral.tablesList.map { it.name },
      behavioralActionNames =
        (behavioral.actionsList + behavioral.controlsList.flatMap { it.localActionsList })
          .flatMap { action -> listOfNotNull(action.name, action.currentName.ifEmpty { null }) },
      staticEntries = config.device.staticEntries.updatesList,
    )

    architecture =
      when (val archName = behavioral.architecture.name) {
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

/** Recursively collects output packets from trace tree leaves (for forking programs). */
fun collectOutputsFromTrace(tree: TraceTree): List<OutputPacket> =
  when {
    tree.hasForkOutcome() ->
      tree.forkOutcome.branchesList.flatMap { collectOutputsFromTrace(it.subtree) }
    tree.hasPacketOutcome() && tree.packetOutcome.hasOutput() -> listOf(tree.packetOutcome.output)
    else -> emptyList()
  }
