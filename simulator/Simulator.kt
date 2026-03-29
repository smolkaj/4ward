package fourward.simulator

import fourward.ir.PipelineConfig
import fourward.sim.SimulatorProto.ForkMode
import fourward.sim.SimulatorProto.OutputPacket
import fourward.sim.SimulatorProto.TraceTree

/**
 * Result of processing a single packet through the pipeline.
 *
 * [possibleOutcomes] captures both kinds of nondeterminism in the trace tree:
 * - **Parallel forks** (clone, multicast, resubmit, recirculate): all branches execute
 *   simultaneously, so their outputs are combined within each possible outcome.
 * - **Alternative forks** (action selector): exactly one branch executes at runtime, so each branch
 *   produces a separate possible outcome.
 *
 * Programs with no alternative forks have exactly one possible outcome. Programs with action
 * selectors have one possible outcome per alternative (Cartesian product when nested inside
 * parallel forks).
 *
 * Decouples the simulator from the gRPC wire format ([fourward.sim.SimulatorProto
 * .InjectPacketResponse]). Each RPC method builds its own wire proto from this data class.
 */
data class ProcessPacketResult(val possibleOutcomes: List<List<OutputPacket>>, val trace: TraceTree)

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
class Simulator(
  /**
   * Override for the v1model drop port. When null (default), derived from `standard_metadata` port
   * width: `2^N - 1` (511 for 9-bit ports). Passed through to [V1ModelArchitecture].
   */
  private val dropPortOverride: Int? = null
) : TableDataReader {

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
        "v1model" -> V1ModelArchitecture(dropPortOverride)
        "psa" -> PSAArchitecture()
        "pna" -> PNAArchitecture()
        else -> throw IllegalArgumentException("unsupported architecture: $archName")
      }
  }

  /**
   * Like [loadPipeline], but preserves forwarding state for tables whose schema is unchanged.
   *
   * Used by RECONCILE_AND_COMMIT. Snapshots the current write-state, loads the new pipeline (which
   * clears all state), then restores entries for tables that exist in both pipelines. The caller
   * must verify schema compatibility before calling this.
   */
  fun loadPipelinePreservingEntries(config: PipelineConfig) {
    val snapshot = tableStore.snapshotWriteState()
    val oldAliasByName = tableStore.tableAliasByName
    loadPipeline(config)
    tableStore.restoreTableEntries(snapshot, oldAliasByName)
  }

  /**
   * Processes a single packet through the pipeline.
   *
   * @throws IllegalStateException if no pipeline is loaded.
   */
  fun processPacket(ingressPort: Int, payload: ByteArray): ProcessPacketResult {
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
    // of truth for packet outcomes. Parallel forks (clone, multicast) combine outputs within
    // each world; alternative forks (action selector) produce separate possible worlds.
    val trace = result.trace
    return ProcessPacketResult(possibleOutcomes = collectPossibleOutcomes(trace), trace = trace)
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

  // -------------------------------------------------------------------------
  // Raw data accessors (used by the P4Runtime layer's EntityReader)
  // -------------------------------------------------------------------------

  /** ID→name maps populated by [loadPipeline], for building P4Runtime Entity protos. */
  val tableNameById: Map<Int, String>
    get() = tableStore.tableNameById

  val actionNameById: Map<Int, String>
    get() = tableStore.actionNameById

  /** P4info alias names of tables that have at least one installed entry. */
  val populatedTableAliases: Set<String>
    get() = tableStore.populatedTableAliases()

  override fun getTableEntries(tableName: String) = tableStore.getTableEntries(tableName)

  override fun getDefaultAction(tableName: String) = tableStore.getDefaultAction(tableName)

  override fun isDefaultModified(tableName: String) = tableStore.isDefaultModified(tableName)

  override fun getDirectCounterData(entry: p4.v1.P4RuntimeOuterClass.TableEntry) =
    tableStore.getDirectCounterData(entry)

  override fun getDirectMeterData(entry: p4.v1.P4RuntimeOuterClass.TableEntry) =
    tableStore.getDirectMeterData(entry)

  override fun hasDirectCounter(tableName: String) = tableStore.hasDirectCounter(tableName)

  override fun hasDirectMeter(tableName: String) = tableStore.hasDirectMeter(tableName)

  /**
   * Reads non-table entities matching the given filters.
   *
   * Table entries are handled by [fourward.p4runtime.EntityReader] in the P4Runtime layer; this
   * method handles all other entity types.
   */
  fun readEntries(
    filters: List<p4.v1.P4RuntimeOuterClass.Entity>
  ): List<p4.v1.P4RuntimeOuterClass.Entity> =
    filters.flatMap { entity ->
      when {
        entity.hasTableEntry() -> emptyList()
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
        entity.hasValueSetEntry() -> tableStore.readValueSetEntries(entity.valueSetEntry)
        else -> error("unsupported entity type for read: ${entity.entityCase}")
      }
    }

  /** Returns the short p4info alias for a behavioral name (table or action), or the name itself. */
  fun displayName(behavioralName: String): String = tableStore.displayName(behavioralName)
}

/**
 * Collects all possible outcome sets from a trace tree, respecting fork semantics.
 * - **Parallel forks** (clone, multicast, resubmit, recirculate): outputs from all branches are
 *   combined within each possible world (Cartesian product of branch outcomes).
 * - **Alternative forks** (action selector): each branch is a separate possible world; outcomes are
 *   concatenated (union of branch outcomes).
 * - **Leaf (output):** one possible world with one packet.
 * - **Leaf (drop):** one possible world with no packets.
 *
 * Returns a non-empty list. Each inner list is one complete set of output packets that could result
 * from a single real execution.
 */
fun collectPossibleOutcomes(tree: TraceTree): List<List<OutputPacket>> {
  if (!tree.hasForkOutcome()) {
    return if (tree.hasPacketOutcome() && tree.packetOutcome.hasOutput()) {
      listOf(listOf(tree.packetOutcome.output))
    } else {
      listOf(emptyList())
    }
  }
  val fork = tree.forkOutcome
  val branchOutcomes = fork.branchesList.map { collectPossibleOutcomes(it.subtree) }
  if (branchOutcomes.isEmpty()) return listOf(emptyList())
  return when (fork.mode) {
    // Alternative: each branch adds its worlds to the result.
    ForkMode.FORK_MODE_ALTERNATIVE -> branchOutcomes.flatten()
    // Parallel (and unspecified, for forward compatibility): Cartesian product across
    // branches — for each combination of one world per branch, concatenate the packets.
    else ->
      branchOutcomes.reduce { acc, next ->
        acc.flatMap { world -> next.map { branchWorld -> world + branchWorld } }
      }
  }
}
