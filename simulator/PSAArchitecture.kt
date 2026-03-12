package fourward.simulator

import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.ExternInstanceDecl
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.TypeDecl
import fourward.sim.v1.SimulatorProto.Drop
import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.Fork
import fourward.sim.v1.SimulatorProto.ForkBranch
import fourward.sim.v1.SimulatorProto.ForkReason
import fourward.sim.v1.SimulatorProto.PacketIngressEvent
import fourward.sim.v1.SimulatorProto.PacketOutcome
import fourward.sim.v1.SimulatorProto.PipelineStageEvent
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree
import java.math.BigInteger

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

  /** Pipeline-invariant state derived from the [BehavioralConfig]. Computed once per packet. */
  private class PipelineConfig(
    val config: BehavioralConfig,
    val tableStore: TableStore,
    val stages: List<PipelineStage>,
    val blockParams: Map<String, List<BlockParam>>,
    val typesByName: Map<String, TypeDecl>,
    val externInstances: Map<String, ExternInstanceDecl>,
  )

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    val pipeline =
      PipelineConfig(
        config,
        tableStore,
        stages = config.architecture.stagesList,
        blockParams = buildBlockParamsMap(config),
        typesByName = config.typesList.associateBy { it.name },
        externInstances = buildExternInstancesMap(config),
      )

    // === Ingress pipeline ===
    val ingress = runIngressPipeline(pipeline, payload, ingressPort)
    if (ingress.dropped) return buildDropResult(ingress.events)

    // === Traffic Manager: multicast vs. unicast ===
    val egressState = EgressState(pipeline, ingress.output)
    val multicastGroup =
      (ingress.output?.fields?.get("multicast_group") as? BitVal)?.bits?.value?.toInt() ?: 0

    if (multicastGroup != 0) {
      return multicastFork(egressState, ingress, multicastGroup)
    }

    val egressPort =
      (ingress.output?.fields?.get("egress_port") as? BitVal)?.bits?.value?.toInt() ?: 0

    // === Unicast egress ===
    val egressTrace =
      runEgressPipeline(egressState, ingress.deparsedBytes, egressPort, 0, "NORMAL_UNICAST")

    // Flatten ingress + egress events into a single trace.
    val tree =
      TraceTree.newBuilder().addAllEvents(ingress.events).addAllEvents(egressTrace.eventsList)
    if (egressTrace.hasPacketOutcome()) tree.setPacketOutcome(egressTrace.packetOutcome)
    return PipelineResult(tree.build())
  }

  // ---------------------------------------------------------------------------
  // Ingress pipeline
  // ---------------------------------------------------------------------------

  /** Result of running the ingress pipeline (parser → control → drop check → deparser). */
  private class IngressResult(
    val events: List<TraceEvent>,
    val output: StructVal?,
    val deparsedBytes: ByteArray,
    val dropped: Boolean,
  )

  private fun runIngressPipeline(
    pipeline: PipelineConfig,
    payload: ByteArray,
    ingressPort: UInt,
  ): IngressResult {
    val ctx = PacketContext(payload)
    val env = Environment()
    val values = createDefaultValues(pipeline.config, pipeline.typesByName)

    initIngressMetadata(values, ingressPort)
    val output = values["psa_ingress_output_metadata_t"] as? StructVal

    val interpreter =
      Interpreter(
        pipeline.config,
        pipeline.tableStore,
        ctx,
        emptyMap(),
        createPsaExternHandler(pipeline),
      )

    ctx.addTraceEvent(packetIngressEvent(ingressPort))

    // --- Ingress Parser ---
    val parserStage = pipeline.stages.first { it.name == "ingress_parser" }
    bindStageParams(env, parserStage.blockName, pipeline.blockParams, values)
    runParserStage(interpreter, ctx, env, parserStage) { e ->
      (values["psa_ingress_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // --- Ingress Control ---
    val controlStage = pipeline.stages.first { it.name == "ingress" }
    bindStageParams(env, controlStage.blockName, pipeline.blockParams, values)
    runControlStage(interpreter, ctx, env, controlStage)

    // --- Ingress drop check (PSA: drop=true by default) ---
    val dropped = (output?.fields?.get("drop") as? BoolVal)?.value != false
    if (dropped) {
      return IngressResult(ctx.getEvents(), output, byteArrayOf(), dropped = true)
    }

    // --- Ingress Deparser ---
    val deparserStage = pipeline.stages.first { it.name == "ingress_deparser" }
    bindStageParams(env, deparserStage.blockName, pipeline.blockParams, values)
    runControlStage(interpreter, ctx, env, deparserStage)

    val deparsedBytes = ctx.outputPayload() + ctx.drainRemainingInput()
    return IngressResult(ctx.getEvents(), output, deparsedBytes, dropped = false)
  }

  // ---------------------------------------------------------------------------
  // Traffic Manager: multicast
  // ---------------------------------------------------------------------------

  private fun multicastFork(
    egressState: EgressState,
    ingress: IngressResult,
    multicastGroup: Int,
  ): PipelineResult {
    val group =
      egressState.pipeline.tableStore.getMulticastGroup(multicastGroup)
        ?: // BMv2 psa_switch: unknown multicast group → silently drop.
        return buildDropResult(ingress.events)

    val branches =
      group.replicasList.map { replica ->
        val subtree =
          runEgressPipeline(
            egressState,
            ingress.deparsedBytes,
            replica.egressPort,
            replica.instance,
            "NORMAL_MULTICAST",
          )
        ForkBranch.newBuilder()
          .setLabel("replica_${replica.instance}_port_${replica.egressPort}")
          .setSubtree(subtree)
          .build()
      }
    val tree =
      TraceTree.newBuilder()
        .addAllEvents(ingress.events)
        .setForkOutcome(Fork.newBuilder().setReason(ForkReason.MULTICAST).addAllBranches(branches))
        .build()
    return PipelineResult(tree)
  }

  // ---------------------------------------------------------------------------
  // Egress pipeline (runs once per unicast, once per multicast replica)
  // ---------------------------------------------------------------------------

  /** Shared state needed to run the egress pipeline. */
  private class EgressState(val pipeline: PipelineConfig, val ingressOutput: StructVal?)

  /**
   * Runs the full egress pipeline (parser → control → deparser) and returns its TraceTree.
   *
   * For multicast, this is called once per replica. For unicast, called once. The returned trace
   * contains only egress events — the caller composes the full trace (unicast: flat merge,
   * multicast: fork branches).
   */
  private fun runEgressPipeline(
    state: EgressState,
    deparsedBytes: ByteArray,
    egressPort: Int,
    instance: Int,
    packetPath: String,
  ): TraceTree {
    val p = state.pipeline
    val egressCtx = PacketContext(deparsedBytes)
    val egressEnv = Environment()
    val egressValues = createDefaultValues(p.config, p.typesByName)

    initEgressMetadata(egressValues, egressPort, instance, packetPath, state.ingressOutput)
    val egressOutput = egressValues["psa_egress_output_metadata_t"] as? StructVal

    val egressInterpreter =
      Interpreter(p.config, p.tableStore, egressCtx, emptyMap(), createPsaExternHandler(p))

    // --- Egress Parser ---
    val egressParserStage = p.stages.first { it.name == "egress_parser" }
    bindStageParams(egressEnv, egressParserStage.blockName, p.blockParams, egressValues)
    runParserStage(egressInterpreter, egressCtx, egressEnv, egressParserStage) { e ->
      (egressValues["psa_egress_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // --- Egress Control ---
    val egressStage = p.stages.first { it.name == "egress" }
    bindStageParams(egressEnv, egressStage.blockName, p.blockParams, egressValues)
    runControlStage(egressInterpreter, egressCtx, egressEnv, egressStage)

    // --- Egress drop check (PSA: egress does NOT drop by default) ---
    if ((egressOutput?.fields?.get("drop") as? BoolVal)?.value == true) {
      return buildDropTrace(egressCtx.getEvents())
    }

    // --- Egress Deparser ---
    val egressDeparserStage = p.stages.first { it.name == "egress_deparser" }
    bindStageParams(egressEnv, egressDeparserStage.blockName, p.blockParams, egressValues)
    runControlStage(egressInterpreter, egressCtx, egressEnv, egressDeparserStage)

    val outputBytes = egressCtx.outputPayload() + egressCtx.drainRemainingInput()
    return buildOutputTrace(egressCtx.getEvents(), egressPort, outputBytes)
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
    instance: Int,
    packetPath: String,
    ingressOutput: StructVal?,
  ) {
    val cos =
      (ingressOutput?.fields?.get("class_of_service") as? BitVal)?.bits?.value?.toLong() ?: 0
    (values["psa_egress_parser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("egress_port", egressPort.toLong())
      it.fields["packet_path"] = EnumVal(packetPath)
    }
    (values["psa_egress_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("class_of_service", cos)
      it.setBitField("egress_port", egressPort.toLong())
      it.fields["packet_path"] = EnumVal(packetPath)
      // PSA spec §6.5: instance identifies the replica within a multicast group.
      // Only set if the program declares the field (not all PSA programs use it).
      if (it.fields.containsKey("instance")) it.setBitField("instance", instance.toLong())
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

  /** PSA extern handler: free functions and extern object methods. */
  private fun createPsaExternHandler(pipeline: PipelineConfig): ExternHandler =
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
            // multicast(inout ostd, in MulticastGroup_t group)
            // PSA spec §6.2: marks the packet for multicast replication.
            "multicast" -> {
              val ostd = eval.evalArg(0) as StructVal
              val group = eval.evalArg(1) as BitVal
              ostd.fields["drop"] = BoolVal(false)
              ostd.fields["multicast_group"] = group
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
              pipeline.tableStore.registerRead(call.instanceName, index)
                ?: eval.defaultValue(eval.returnType())
            }
            "write" -> {
              val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
              pipeline.tableStore.registerWrite(call.instanceName, index, eval.evalArg(1))
              UnitVal
            }
            "count" -> UnitVal
            // PSA Hash.get_hash: 1-arg form returns hash(data), 3-arg form returns
            // (base + hash(data)) mod max. Algorithm comes from constructor args.
            "get_hash" -> evalGetHash(call, eval, pipeline.externInstances)
            // PSA Meter.execute(index): returns PSA_MeterColor_t. Always GREEN — no real
            // packet rates in simulator (same as v1model).
            "execute" -> EnumVal("GREEN")
            else ->
              error(
                "unhandled PSA extern method: ${call.externType}.${call.method}" +
                  " on ${call.instanceName}"
              )
          }
      }
    }

  /** Evaluates PSA Hash.get_hash (1-arg or 3-arg form). */
  private fun evalGetHash(
    call: ExternCall.Method,
    eval: ExternEvaluator,
    externInstances: Map<String, ExternInstanceDecl>,
  ): Value {
    val instance =
      externInstances[call.instanceName] ?: error("unknown Hash instance: ${call.instanceName}")
    val psaAlgorithm =
      instance.constructorArgsList.firstOrNull()?.literal?.enumMember
        ?: error("Hash instance ${call.instanceName} missing algorithm constructor arg")
    val algorithm =
      PSA_HASH_ALGORITHMS[psaAlgorithm] ?: error("unsupported PSA hash algorithm: $psaAlgorithm")
    val resultWidth = eval.returnType().bit.width

    return if (eval.argCount() == 1) {
      // 1-arg form: get_hash(data) → hash(data) truncated to result width
      val data = eval.evalArg(0) as StructVal
      val hash = computeHash(algorithm, data)
      BitVal(BitVector(hash, resultWidth))
    } else {
      // 3-arg form: get_hash(base, data, max) → (base + hash(data)) mod max
      val base = (eval.evalArg(0) as BitVal).bits.value
      val data = eval.evalArg(1) as StructVal
      val max = (eval.evalArg(2) as BitVal).bits.value
      val hash = computeHash(algorithm, data)
      val result = if (max > BigInteger.ZERO) base + hash.mod(max) else base
      BitVal(BitVector(result, resultWidth))
    }
  }

  /** Builds a flat map from extern instance name → declaration across all controls. */
  private fun buildExternInstancesMap(config: BehavioralConfig): Map<String, ExternInstanceDecl> =
    config.controlsList.flatMap { it.externInstancesList }.associateBy { it.name }

  // ---------------------------------------------------------------------------
  // Trace helpers
  // ---------------------------------------------------------------------------

  private fun buildDropResult(events: List<TraceEvent>): PipelineResult =
    PipelineResult(buildDropTrace(events))

  private fun buildDropTrace(events: List<TraceEvent>): TraceTree {
    val outcome =
      PacketOutcome.newBuilder()
        .setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
        .build()
    return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
  }

  private fun buildOutputTrace(events: List<TraceEvent>, port: Int, payload: ByteArray): TraceTree {
    val output =
      fourward.sim.v1.SimulatorProto.OutputPacket.newBuilder()
        .setEgressPort(port)
        .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
        .build()
    val outcome = PacketOutcome.newBuilder().setOutput(output).build()
    return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
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

    /** Maps PSA_HashAlgorithm_t enum members to the internal algorithm names used by Hash.kt. */
    val PSA_HASH_ALGORITHMS =
      mapOf(
        "IDENTITY" to "identity",
        "CRC16" to "crc16",
        "CRC32" to "crc32",
        "ONES_COMPLEMENT16" to "csum16",
      )
  }
}
