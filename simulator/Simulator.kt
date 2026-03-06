package fourward.simulator

import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.ErrorCode
import fourward.sim.v1.ErrorResponse
import fourward.sim.v1.LoadPipelineResponse
import fourward.sim.v1.ProcessPacketResponse
import fourward.sim.v1.ReadEntriesResponse
import fourward.sim.v1.SimRequest
import fourward.sim.v1.SimResponse
import fourward.sim.v1.WriteEntryResponse

/**
 * The top-level simulator state machine.
 *
 * Holds the loaded pipeline configuration and all mutable data-plane state (table entries,
 * counter/meter/register values). Dispatches [SimRequest] messages to the appropriate handler and
 * returns [SimResponse] messages.
 *
 * One [Simulator] instance runs for the lifetime of the process; it is single-threaded (callers
 * must serialise concurrent requests).
 */
class Simulator {

  private var pipeline: PipelineConfig? = null
  private var architecture: Architecture? = null
  private val tableStore = TableStore()

  fun handle(request: SimRequest): SimResponse =
    when {
      request.hasLoadPipeline() -> handleLoadPipeline(request.loadPipeline)
      request.hasProcessPacket() -> handleProcessPacket(request.processPacket)
      request.hasWriteEntry() -> handleWriteEntry(request.writeEntry)
      request.hasReadEntries() -> handleReadEntries(request.readEntries)
      else -> error("unhandled request kind: $request")
    }

  // -------------------------------------------------------------------------
  // Pipeline loading
  // -------------------------------------------------------------------------

  private fun handleLoadPipeline(req: fourward.sim.v1.LoadPipelineRequest): SimResponse {
    val config = req.config
    pipeline = config

    // The behavioral IR uses its own table/action names (TableBehavior.name,
    // ActionDecl.name/current_name), which may differ from p4info aliases
    // (e.g. inlined controls: behavioral "c_t" vs p4info alias "t").
    // Resolve p4info IDs to behavioral names so the TableStore uses the same
    // keys as the interpreter.
    val behavioralTables = config.behavioral.tablesList.map { it.name }
    val behavioralActions =
      (config.behavioral.actionsList +
          config.behavioral.controlsList.flatMap { it.localActionsList })
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
    tableStore.loadMappings(tableNameById, actionNameById, config.p4Info.tablesList)

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
    for (update in config.staticEntries.updatesList) {
      tableStore.write(update)
    }

    architecture =
      when (val archName = config.behavioral.architecture.name) {
        "v1model" -> V1ModelArchitecture()
        else -> return simError("unsupported architecture: $archName")
      }

    return SimResponse.newBuilder()
      .setLoadPipeline(LoadPipelineResponse.getDefaultInstance())
      .build()
  }

  // -------------------------------------------------------------------------
  // Packet processing
  // -------------------------------------------------------------------------

  private fun handleProcessPacket(req: fourward.sim.v1.ProcessPacketRequest): SimResponse {
    val config = pipeline ?: return simError("no pipeline loaded", ErrorCode.NO_PIPELINE_LOADED)
    val arch = architecture ?: return simError("no pipeline loaded", ErrorCode.NO_PIPELINE_LOADED)

    val result =
      arch.processPacket(
        ingressPort = req.ingressPort.toUInt(),
        payload = req.payload.toByteArray(),
        config = config.behavioral,
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
    val response = responseBuilder.build()

    return SimResponse.newBuilder().setProcessPacket(response).build()
  }

  // -------------------------------------------------------------------------
  // Table entry management
  // -------------------------------------------------------------------------

  private fun handleWriteEntry(req: fourward.sim.v1.WriteEntryRequest): SimResponse {
    pipeline ?: return simError("no pipeline loaded", ErrorCode.NO_PIPELINE_LOADED)
    return when (val result = tableStore.write(req.update)) {
      is WriteResult.Success ->
        SimResponse.newBuilder().setWriteEntry(WriteEntryResponse.getDefaultInstance()).build()
      is WriteResult.AlreadyExists -> simError(result.message, ErrorCode.ALREADY_EXISTS)
      is WriteResult.NotFound -> simError(result.message, ErrorCode.ENTITY_NOT_FOUND)
      is WriteResult.InvalidArgument -> simError(result.message, ErrorCode.INVALID_REQUEST)
    }
  }

  private fun handleReadEntries(req: fourward.sim.v1.ReadEntriesRequest): SimResponse {
    // P4Runtime spec §11.1: each entity in the ReadRequest is a filter; the response is the union.
    // A TableEntry with table_id=0 is a wildcard (all tables). An empty entity list returns
    // nothing.
    val entities =
      req.request.entitiesList.flatMap { filter ->
        when {
          filter.hasTableEntry() -> tableStore.readEntities(filter.tableEntry.tableId)
          else -> emptyList()
        }
      }
    return SimResponse.newBuilder()
      .setReadEntries(ReadEntriesResponse.newBuilder().addAllEntities(entities))
      .build()
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private fun simError(message: String, code: ErrorCode = ErrorCode.INTERNAL_ERROR): SimResponse =
    SimResponse.newBuilder()
      .setError(ErrorResponse.newBuilder().setMessage(message).setCode(code))
      .build()
}
