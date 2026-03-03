package fourward.simulator

import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.ErrorCode
import fourward.sim.v1.ErrorResponse
import fourward.sim.v1.LoadPipelineResponse
import fourward.sim.v1.OutputPacket
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

    // Use aliases (short names) so they match the originalName-based keys the behavioral IR uses.
    val tableNameById =
      config.p4Info.tablesList.associate {
        it.preamble.id to it.preamble.alias.ifEmpty { it.preamble.name }
      }
    val actionNameById =
      config.p4Info.actionsList.associate {
        it.preamble.id to it.preamble.alias.ifEmpty { it.preamble.name }
      }
    tableStore.loadMappings(tableNameById, actionNameById)

    for (table in config.p4Info.tablesList) {
      if (table.constDefaultActionId != 0) {
        val actionName = actionNameById[table.constDefaultActionId] ?: "NoAction"
        tableStore.setDefaultAction(table.preamble.name, actionName)
      }
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

    val response =
      ProcessPacketResponse.newBuilder()
        .addAllOutputPackets(
          result.outputPackets.map { pkt ->
            OutputPacket.newBuilder()
              .setEgressPort(pkt.port.toInt())
              .setPayload(com.google.protobuf.ByteString.copyFrom(pkt.payload))
              .build()
          }
        )
        .setTrace(result.trace)
        .build()

    return SimResponse.newBuilder().setProcessPacket(response).build()
  }

  // -------------------------------------------------------------------------
  // Table entry management
  // -------------------------------------------------------------------------

  private fun handleWriteEntry(req: fourward.sim.v1.WriteEntryRequest): SimResponse {
    pipeline ?: return simError("no pipeline loaded", ErrorCode.NO_PIPELINE_LOADED)
    tableStore.write(req.update)
    return SimResponse.newBuilder().setWriteEntry(WriteEntryResponse.getDefaultInstance()).build()
  }

  @Suppress("UnusedParameter") // req will be used when read filters are implemented
  private fun handleReadEntries(req: fourward.sim.v1.ReadEntriesRequest): SimResponse {
    // TODO: implement read support.
    return SimResponse.newBuilder().setReadEntries(ReadEntriesResponse.getDefaultInstance()).build()
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private fun simError(message: String, code: ErrorCode = ErrorCode.INTERNAL_ERROR): SimResponse =
    SimResponse.newBuilder()
      .setError(ErrorResponse.newBuilder().setMessage(message).setCode(code))
      .build()
}
