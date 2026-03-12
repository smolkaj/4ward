package fourward.simulator

import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.TypeDecl
import fourward.sim.v1.SimulatorProto.Drop
import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.PacketIngressEvent
import fourward.sim.v1.SimulatorProto.PacketOutcome
import fourward.sim.v1.SimulatorProto.PipelineStageEvent
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree

/**
 * PSA (Portable Switch Architecture) pipeline implementation.
 *
 * PSA is a two-pipeline architecture with separate ingress and egress stages. The egress pipeline
 * re-parses the deparsed packet, so header and metadata state is NOT shared between ingress and
 * egress — only the raw bytes cross the boundary.
 *
 * Pipeline structure: IngressParser → Ingress → IngressDeparser → (traffic manager) → EgressParser
 * → Egress → EgressDeparser
 *
 * Key differences from v1model:
 * - Ingress drops by default (`ostd.drop` starts `true`). To forward, call `send_to_port()`.
 * - Egress does NOT drop by default (`ostd.drop` starts `false`).
 * - Port width is `bit<32>` (not `bit<9>`).
 * - Each stage has distinct metadata structs (no shared `standard_metadata`).
 *
 * References:
 * - PSA spec: https://p4.org/p4-spec/docs/PSA.html
 * - BMv2 psa.p4: https://github.com/p4lang/p4c/blob/main/p4include/bmv2/psa.p4
 */
class PSAArchitecture : Architecture {

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    val typesByName = config.typesList.associateBy { it.name }
    val stages = config.architecture.stagesList

    // Build a lookup from block name → params for per-stage parameter binding.
    val blockParams = buildBlockParamsMap(config)

    // === Ingress pipeline ===
    val ingressCtx = PacketContext(payload)
    val ingressEnv = Environment()
    val ingressValues = createDefaultValues(config, typesByName)

    initIngressMetadata(ingressValues, ingressPort)
    val ingressOutput = ingressValues["psa_ingress_output_metadata_t"] as? StructVal

    val ingressInterpreter =
      Interpreter(config, tableStore, ingressCtx, emptyMap(), createPsaExternHandler(tableStore))

    ingressCtx.addTraceEvent(packetIngressEvent(ingressPort))

    // --- Ingress Parser ---
    val ingressParserStage = stages.first { it.name == "ingress_parser" }
    bindStageParams(ingressEnv, ingressParserStage.blockName, blockParams, ingressValues)
    runParserStage(ingressInterpreter, ingressCtx, ingressEnv, ingressParserStage) { e ->
      (ingressValues["psa_ingress_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // --- Ingress Control ---
    val ingressStage = stages.first { it.name == "ingress" }
    bindStageParams(ingressEnv, ingressStage.blockName, blockParams, ingressValues)
    runControlStage(ingressInterpreter, ingressCtx, ingressEnv, ingressStage)

    // --- Ingress drop check (PSA: drop=true by default) ---
    if ((ingressOutput?.fields?.get("drop") as? BoolVal)?.value != false) {
      return buildDropResult(ingressCtx.getEvents())
    }

    val egressPort =
      (ingressOutput?.fields?.get("egress_port") as? BitVal)?.bits?.value?.toInt() ?: 0

    // --- Ingress Deparser ---
    val ingressDeparserStage = stages.first { it.name == "ingress_deparser" }
    bindStageParams(ingressEnv, ingressDeparserStage.blockName, blockParams, ingressValues)
    runControlStage(ingressInterpreter, ingressCtx, ingressEnv, ingressDeparserStage)

    val deparsedBytes = ingressCtx.outputPayload() + ingressCtx.drainRemainingInput()
    val ingressEvents = ingressCtx.getEvents()

    // === Egress pipeline (re-parses the deparsed packet) ===
    val egressCtx = PacketContext(deparsedBytes)
    val egressEnv = Environment()
    val egressValues = createDefaultValues(config, typesByName)

    initEgressMetadata(egressValues, egressPort, ingressOutput)
    val egressOutput = egressValues["psa_egress_output_metadata_t"] as? StructVal

    val egressInterpreter =
      Interpreter(config, tableStore, egressCtx, emptyMap(), createPsaExternHandler(tableStore))

    // Carry ingress trace events into the egress context.
    for (event in ingressEvents) egressCtx.addTraceEvent(event)

    // --- Egress Parser ---
    val egressParserStage = stages.first { it.name == "egress_parser" }
    bindStageParams(egressEnv, egressParserStage.blockName, blockParams, egressValues)
    runParserStage(egressInterpreter, egressCtx, egressEnv, egressParserStage) { e ->
      (egressValues["psa_egress_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // --- Egress Control ---
    val egressStage = stages.first { it.name == "egress" }
    bindStageParams(egressEnv, egressStage.blockName, blockParams, egressValues)
    runControlStage(egressInterpreter, egressCtx, egressEnv, egressStage)

    // --- Egress drop check (PSA: egress does NOT drop by default) ---
    if ((egressOutput?.fields?.get("drop") as? BoolVal)?.value == true) {
      return buildDropResult(egressCtx.getEvents())
    }

    // --- Egress Deparser ---
    val egressDeparserStage = stages.first { it.name == "egress_deparser" }
    bindStageParams(egressEnv, egressDeparserStage.blockName, blockParams, egressValues)
    runControlStage(egressInterpreter, egressCtx, egressEnv, egressDeparserStage)

    val outputBytes = egressCtx.outputPayload() + egressCtx.drainRemainingInput()
    return buildOutputResult(egressCtx.getEvents(), egressPort, outputBytes)
  }

  // ---------------------------------------------------------------------------
  // Metadata initialisation
  // ---------------------------------------------------------------------------

  private fun initIngressMetadata(values: Map<String, Value>, ingressPort: UInt) {
    (values["psa_ingress_parser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("ingress_port", ingressPort.toLong())
      it.fields["packet_path"] = EnumVal("NORMAL")
    }
    (values["psa_ingress_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("ingress_port", ingressPort.toLong())
      it.fields["packet_path"] = EnumVal("NORMAL")
      it.fields["parser_error"] = ErrorVal("NoError")
    }
    // PSA spec: ingress output metadata defaults to drop=true.
    (values["psa_ingress_output_metadata_t"] as? StructVal)?.let {
      it.fields["drop"] = BoolVal(true)
    }
  }

  private fun initEgressMetadata(
    values: Map<String, Value>,
    egressPort: Int,
    ingressOutput: StructVal?,
  ) {
    val cos =
      (ingressOutput?.fields?.get("class_of_service") as? BitVal)?.bits?.value?.toLong() ?: 0
    (values["psa_egress_parser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("egress_port", egressPort.toLong())
      it.fields["packet_path"] = EnumVal("NORMAL_UNICAST")
    }
    (values["psa_egress_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("class_of_service", cos)
      it.setBitField("egress_port", egressPort.toLong())
      it.fields["packet_path"] = EnumVal("NORMAL_UNICAST")
      it.fields["parser_error"] = ErrorVal("NoError")
    }
    // PSA spec: egress output metadata defaults to drop=false.
    (values["psa_egress_output_metadata_t"] as? StructVal)?.let {
      it.fields["drop"] = BoolVal(false)
    }
    (values["psa_egress_deparser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("egress_port", egressPort.toLong())
    }
  }

  // ---------------------------------------------------------------------------
  // Stage execution helpers
  // ---------------------------------------------------------------------------

  private fun runParserStage(
    interpreter: Interpreter,
    ctx: PacketContext,
    env: Environment,
    stage: PipelineStage,
    onParserError: (ParserErrorException) -> Unit,
  ) {
    ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.ENTER))
    try {
      interpreter.runParser(stage.blockName, env)
    } catch (e: ParserErrorException) {
      onParserError(e)
    } finally {
      ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.EXIT))
    }
  }

  private fun runControlStage(
    interpreter: Interpreter,
    ctx: PacketContext,
    env: Environment,
    stage: PipelineStage,
  ) {
    ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.ENTER))
    try {
      interpreter.runControl(stage.blockName, env)
    } catch (_: ExitException) {
      // PSA: exit terminates the current control, but the pipeline continues.
    } finally {
      ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.EXIT))
    }
  }

  // ---------------------------------------------------------------------------
  // Parameter binding
  // ---------------------------------------------------------------------------

  /**
   * Binds a stage's parameters in the environment, mapping each param name to the shared value for
   * its type.
   *
   * PSA stages reuse parameter names (e.g. `istd`) with different types, so parameters must be
   * re-bound before each stage — unlike v1model where all params can be bound once upfront.
   */
  private fun bindStageParams(
    env: Environment,
    blockName: String,
    blockParams: Map<String, List<BlockParam>>,
    valueByType: Map<String, Value>,
  ) {
    for (param in blockParams[blockName] ?: emptyList()) {
      valueByType[param.typeName]?.let { env.define(param.name, it) }
    }
  }

  /** Simplified parameter descriptor: just name and type. */
  private data class BlockParam(val name: String, val typeName: String)

  /** Builds a map from block name to parameter list across all parsers and controls. */
  private fun buildBlockParamsMap(config: BehavioralConfig): Map<String, List<BlockParam>> {
    val result = mutableMapOf<String, List<BlockParam>>()
    for (parser in config.parsersList) {
      result[parser.name] =
        parser.paramsList
          .filter { it.type.hasNamed() && it.type.named !in IO_TYPES }
          .map { BlockParam(it.name, it.type.named) }
    }
    for (control in config.controlsList) {
      result[control.name] =
        control.paramsList
          .filter { it.type.hasNamed() && it.type.named !in IO_TYPES }
          .map { BlockParam(it.name, it.type.named) }
    }
    return result
  }

  /**
   * Creates default values for all named types referenced by parser/control parameters.
   *
   * Returns a mutable map keyed by type name. The same value instance is shared across all
   * parameters of that type (e.g., `headers` is shared between parser `parsed_hdr` and control
   * `hdr`), so mutations in one stage are visible to subsequent stages.
   */
  private fun createDefaultValues(
    config: BehavioralConfig,
    typesByName: Map<String, TypeDecl>,
  ): MutableMap<String, Value> {
    val values = mutableMapOf<String, Value>()
    for (parser in config.parsersList) {
      for (param in parser.paramsList) {
        if (param.type.hasNamed() && param.type.named !in IO_TYPES) {
          values.getOrPut(param.type.named) { defaultValue(param.type.named, typesByName) }
        }
      }
    }
    for (control in config.controlsList) {
      for (param in control.paramsList) {
        if (param.type.hasNamed() && param.type.named !in IO_TYPES) {
          values.getOrPut(param.type.named) { defaultValue(param.type.named, typesByName) }
        }
      }
    }
    return values
  }

  // ---------------------------------------------------------------------------
  // PSA extern handler
  // ---------------------------------------------------------------------------

  /**
   * Creates a minimal PSA extern handler.
   *
   * Currently handles `send_to_port` (sets egress_port and drop=false on the ingress output
   * metadata). Additional PSA externs will be added as tests require them.
   */
  private fun createPsaExternHandler(tableStore: TableStore): ExternHandler =
    ExternHandler { call, eval ->
      when (call) {
        is ExternCall.FreeFunction ->
          when (call.name) {
            // send_to_port(inout ostd, in PortId_t port)
            // After midend, becomes: send_to_port(ostd, port) where ostd is the output metadata.
            "send_to_port" -> {
              val ostd = eval.evalArg(0) as StructVal
              val port = eval.evalArg(1) as BitVal
              ostd.fields["drop"] = BoolVal(false)
              ostd.fields["egress_port"] = port
              UnitVal
            }
            "ingress_drop",
            "egress_drop" -> {
              val ostd = eval.evalArg(0) as StructVal
              ostd.fields["drop"] = BoolVal(true)
              UnitVal
            }
            else -> error("unhandled PSA extern: ${call.name}")
          }
        is ExternCall.Method ->
          when (call.method) {
            // PSA register.read(index) returns T directly (unlike v1model's void + out param).
            "read" -> {
              val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
              tableStore.registerRead(call.instanceName, index)
                ?: eval.defaultValue(eval.returnType())
            }
            "write" -> {
              val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
              tableStore.registerWrite(call.instanceName, index, eval.evalArg(1))
              UnitVal
            }
            "count" -> UnitVal
            else ->
              error(
                "unhandled PSA extern method: ${call.externType}.${call.method}" +
                  " on ${call.instanceName}"
              )
          }
      }
    }

  // ---------------------------------------------------------------------------
  // Trace helpers
  // ---------------------------------------------------------------------------

  private fun buildDropResult(events: List<TraceEvent>): PipelineResult {
    val outcome =
      PacketOutcome.newBuilder()
        .setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
        .build()
    return PipelineResult(
      TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
    )
  }

  private fun buildOutputResult(
    events: List<TraceEvent>,
    port: Int,
    payload: ByteArray,
  ): PipelineResult {
    val output =
      fourward.sim.v1.SimulatorProto.OutputPacket.newBuilder()
        .setEgressPort(port)
        .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
        .build()
    val outcome = PacketOutcome.newBuilder().setOutput(output).build()
    return PipelineResult(
      TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
    )
  }

  private fun packetIngressEvent(ingressPort: UInt): TraceEvent =
    TraceEvent.newBuilder()
      .setPacketIngress(PacketIngressEvent.newBuilder().setIngressPort(ingressPort.toInt()))
      .build()

  private fun stageEvent(
    stage: PipelineStage,
    direction: PipelineStageEvent.Direction,
  ): TraceEvent =
    TraceEvent.newBuilder()
      .setPipelineStage(
        PipelineStageEvent.newBuilder()
          .setStageName(stage.name)
          .setStageKind(stage.kind)
          .setDirection(direction)
      )
      .build()

  private companion object {
    /** Architecture-level packet I/O types that are not user-visible parameters. */
    val IO_TYPES = setOf("packet_in", "packet_out")
  }
}
