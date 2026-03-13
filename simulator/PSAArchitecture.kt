package fourward.simulator

import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.ExternInstanceDecl
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.TypeDecl
import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.Fork
import fourward.sim.v1.SimulatorProto.ForkBranch
import fourward.sim.v1.SimulatorProto.ForkReason
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

    val tree = processPacketRecursive(pipeline, payload, ingressPort, PACKET_PATH_NORMAL, 0)
    return PipelineResult(tree)
  }

  /**
   * Runs one full ingress→egress pass and handles I2E/E2E clone forks and recirculation.
   *
   * For recirculate, calls itself recursively with the deparsed egress bytes looped back to
   * ingress. [depth] guards against infinite recirculation loops.
   */
  private fun processPacketRecursive(
    pipeline: PipelineConfig,
    payload: ByteArray,
    ingressPort: UInt,
    packetPath: String,
    depth: Int,
  ): TraceTree {
    check(depth < MAX_RECIRCULATIONS) { "PSA recirculation depth exceeded ($MAX_RECIRCULATIONS)" }

    // === Ingress pipeline ===
    val ingress = runIngressPipeline(pipeline, payload, ingressPort, packetPath)

    // === End-of-ingress decisions (PSA spec §6.2, Table 4) ===
    // Priority: drop > resubmit > {I2E clone, multicast/unicast}.
    // I2E clone is independent of drop but suppressed by resubmit.

    if (ingress.dropped) {
      val i2eCloneBranches = buildI2ECloneBranches(pipeline, payload, ingress.output)
      if (i2eCloneBranches.isNotEmpty()) {
        return buildForkTree(ingress.events, ForkReason.CLONE, i2eCloneBranches)
      }
      return buildDropTrace(ingress.events, ingress.dropReason)
    }

    // Resubmit: loop original bytes back to ingress. Suppresses I2E clone.
    val resubmit = (ingress.output?.fields?.get("resubmit") as? BoolVal)?.value == true
    if (resubmit) {
      val resubmitTree =
        processPacketRecursive(pipeline, payload, ingressPort, PACKET_PATH_RESUBMIT, depth + 1)
      return buildForkTree(
        ingress.events,
        ForkReason.RESUBMIT,
        listOf(ForkBranch.newBuilder().setLabel("resubmit").setSubtree(resubmitTree).build()),
      )
    }

    // === Traffic Manager: I2E clone + multicast/unicast ===
    val i2eCloneBranches = buildI2ECloneBranches(pipeline, payload, ingress.output)
    val originalTree = buildOriginalEgressPath(pipeline, ingress, depth)

    if (i2eCloneBranches.isNotEmpty()) {
      val originalBranch =
        ForkBranch.newBuilder().setLabel("original").setSubtree(originalTree).build()
      return buildForkTree(
        ingress.events,
        ForkReason.CLONE,
        listOf(originalBranch) + i2eCloneBranches,
      )
    }

    // No I2E clone: flatten ingress + original egress into a single trace.
    return TraceTree.newBuilder()
      .addAllEvents(ingress.events)
      .addAllEvents(originalTree.eventsList)
      .also { if (originalTree.hasPacketOutcome()) it.setPacketOutcome(originalTree.packetOutcome) }
      .also { if (originalTree.hasForkOutcome()) it.setForkOutcome(originalTree.forkOutcome) }
      .build()
  }

  /** Builds the egress path for the original (non-cloned) packet: multicast or unicast. */
  private fun buildOriginalEgressPath(
    pipeline: PipelineConfig,
    ingress: IngressResult,
    depth: Int,
  ): TraceTree {
    val egressState = EgressState(pipeline, ingress.output)
    val multicastGroup =
      (ingress.output?.fields?.get("multicast_group") as? BitVal)?.bits?.value?.toInt() ?: 0

    if (multicastGroup != 0) {
      return multicastEgressTree(egressState, ingress, multicastGroup, depth)
    }

    val egressPort =
      (ingress.output?.fields?.get("egress_port") as? BitVal)?.bits?.value?.toInt() ?: 0

    return runEgressWithPostProcessing(
      egressState,
      ingress.deparsedBytes,
      egressPort,
      0,
      PACKET_PATH_NORMAL_UNICAST,
      depth,
    )
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
    val dropReason: DropReason = DropReason.MARK_TO_DROP,
  )

  private fun runIngressPipeline(
    pipeline: PipelineConfig,
    payload: ByteArray,
    ingressPort: UInt,
    packetPath: String,
  ): IngressResult {
    val ctx = PacketContext(payload)
    val env = Environment()
    val values = createDefaultValues(pipeline.config, pipeline.typesByName)

    initIngressMetadata(values, ingressPort, packetPath)
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

    // An assert()/assume() failure anywhere in the pipeline drops the packet.
    try {
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
    } catch (_: AssertionFailureException) {
      return IngressResult(
        ctx.getEvents(),
        output,
        byteArrayOf(),
        dropped = true,
        dropReason = DropReason.ASSERTION_FAILURE,
      )
    }

    val deparsedBytes = ctx.outputPayload() + ctx.drainRemainingInput()
    return IngressResult(ctx.getEvents(), output, deparsedBytes, dropped = false)
  }

  // ---------------------------------------------------------------------------
  // Traffic Manager: multicast
  // ---------------------------------------------------------------------------

  /** Builds a multicast fork TraceTree (without ingress events — caller prepends those). */
  private fun multicastEgressTree(
    egressState: EgressState,
    ingress: IngressResult,
    multicastGroup: Int,
    depth: Int,
  ): TraceTree {
    val group =
      egressState.pipeline.tableStore.getMulticastGroup(multicastGroup)
        ?: // BMv2 psa_switch: unknown multicast group → silently drop.
        return buildDropTrace(emptyList())

    val branches =
      group.replicasList.map { replica ->
        val subtree =
          runEgressWithPostProcessing(
            egressState,
            ingress.deparsedBytes,
            replica.egressPort,
            replica.instance,
            PACKET_PATH_NORMAL_MULTICAST,
            depth,
          )
        ForkBranch.newBuilder()
          .setLabel("replica_${replica.instance}_port_${replica.egressPort}")
          .setSubtree(subtree)
          .build()
      }
    return TraceTree.newBuilder()
      .setForkOutcome(Fork.newBuilder().setReason(ForkReason.MULTICAST).addAllBranches(branches))
      .build()
  }

  // ---------------------------------------------------------------------------
  // Egress pipeline (runs once per unicast, once per multicast replica)
  // ---------------------------------------------------------------------------

  /** Shared state needed to run the egress pipeline. */
  private class EgressState(val pipeline: PipelineConfig, val ingressOutput: StructVal?)

  /**
   * Raw result from running the egress pipeline stages (before post-processing for clone/recirc).
   */
  private class EgressCoreResult(
    val events: List<TraceEvent>,
    val dropped: Boolean,
    val deparsedBytes: ByteArray,
    val output: StructVal?,
    val dropReason: DropReason = DropReason.MARK_TO_DROP,
  )

  /**
   * Runs the full egress pipeline (parser → control → drop check → deparser), then handles E2E
   * clone forks and recirculation.
   */
  private fun runEgressWithPostProcessing(
    state: EgressState,
    deparsedBytes: ByteArray,
    egressPort: Int,
    instance: Int,
    packetPath: String,
    depth: Int,
  ): TraceTree {
    val core = runEgressCore(state, deparsedBytes, egressPort, instance, packetPath)

    // E2E clone: uses deparsed bytes from this egress pass. Independent of drop decision.
    val e2eCloneBranches = buildE2ECloneBranches(state.pipeline, core)

    if (core.dropped) {
      if (e2eCloneBranches.isNotEmpty()) {
        return buildForkTree(core.events, ForkReason.CLONE, e2eCloneBranches)
      }
      return buildDropTrace(core.events, core.dropReason)
    }

    // Recirculate: loop deparsed bytes back to ingress.
    val isRecirculate = egressPort.toUInt().toLong() == PSA_PORT_RECIRCULATE_LONG
    val outputTree =
      if (isRecirculate) {
        val recircTree =
          processPacketRecursive(
            state.pipeline,
            core.deparsedBytes,
            PSA_PORT_RECIRCULATE_UINT,
            PACKET_PATH_RECIRCULATE,
            depth + 1,
          )
        TraceTree.newBuilder()
          .addAllEvents(core.events)
          .setForkOutcome(
            Fork.newBuilder()
              .setReason(ForkReason.RECIRCULATE)
              .addBranches(ForkBranch.newBuilder().setLabel("recirculate").setSubtree(recircTree))
          )
          .build()
      } else {
        buildOutputTrace(core.events, egressPort, core.deparsedBytes)
      }

    if (e2eCloneBranches.isNotEmpty()) {
      val originalBranch =
        ForkBranch.newBuilder().setLabel("original").setSubtree(outputTree).build()
      return buildForkTree(emptyList(), ForkReason.CLONE, listOf(originalBranch) + e2eCloneBranches)
    }

    return outputTree
  }

  /**
   * Runs the egress pipeline stages (parser → control → drop check → deparser) and returns raw
   * results for post-processing by the caller.
   */
  private fun runEgressCore(
    state: EgressState,
    deparsedBytes: ByteArray,
    egressPort: Int,
    instance: Int,
    packetPath: String,
  ): EgressCoreResult {
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

    // An assert()/assume() failure anywhere in egress drops the packet.
    try {
      // --- Egress Control ---
      val egressStage = p.stages.first { it.name == "egress" }
      bindStageParams(egressEnv, egressStage.blockName, p.blockParams, egressValues)
      runControlStage(egressInterpreter, egressCtx, egressEnv, egressStage)
    } catch (_: AssertionFailureException) {
      return EgressCoreResult(
        egressCtx.getEvents(),
        dropped = true,
        byteArrayOf(),
        egressOutput,
        dropReason = DropReason.ASSERTION_FAILURE,
      )
    }

    // --- Egress drop check (PSA: egress does NOT drop by default) ---
    val dropped = (egressOutput?.fields?.get("drop") as? BoolVal)?.value == true

    // --- Egress Deparser (always runs — E2E clone uses deparsed bytes even for dropped pkts) ---
    val egressDeparserStage = p.stages.first { it.name == "egress_deparser" }
    bindStageParams(egressEnv, egressDeparserStage.blockName, p.blockParams, egressValues)
    runControlStage(egressInterpreter, egressCtx, egressEnv, egressDeparserStage)

    val outputBytes = egressCtx.outputPayload() + egressCtx.drainRemainingInput()
    return EgressCoreResult(egressCtx.getEvents(), dropped, outputBytes, egressOutput)
  }

  // ---------------------------------------------------------------------------
  // Clone branch construction
  // ---------------------------------------------------------------------------

  /**
   * Builds I2E clone branches if the ingress output metadata requests cloning.
   *
   * I2E clones use the ORIGINAL input bytes (pre-ingress) — not the ingress-deparsed output. Each
   * replica in the clone session's multicast group produces a separate egress execution with
   * `packet_path = CLONE_I2E`.
   */
  private fun buildI2ECloneBranches(
    pipeline: PipelineConfig,
    originalPayload: ByteArray,
    ingressOutput: StructVal?,
  ): List<ForkBranch> =
    // I2E clones don't carry ingress output metadata to egress — egress gets a fresh EgressState
    // with no ingressOutput. Clones do not produce further clones (no chained cloning in PSA).
    buildCloneBranches(pipeline, ingressOutput, originalPayload, PACKET_PATH_CLONE_I2E)

  /**
   * Builds E2E clone branches if the egress output metadata requests cloning.
   *
   * E2E clones use the deparsed bytes from the current egress pass. Each replica produces a fresh
   * egress execution with `packet_path = CLONE_E2E`. Chained E2E cloning is suppressed (PSA spec:
   * behavior is implementation-specific; BMv2 suppresses it).
   */
  private fun buildE2ECloneBranches(
    pipeline: PipelineConfig,
    core: EgressCoreResult,
  ): List<ForkBranch> =
    buildCloneBranches(pipeline, core.output, core.deparsedBytes, PACKET_PATH_CLONE_E2E)

  /**
   * Shared clone branch builder for both I2E and E2E cloning.
   *
   * Checks the clone flag in [cloneMetadata], looks up the clone session, and runs a fresh egress
   * for each replica. Clone egress runs via [runEgressCore] directly (no post-processing), which
   * prevents chained cloning.
   */
  private fun buildCloneBranches(
    pipeline: PipelineConfig,
    cloneMetadata: StructVal?,
    clonePayload: ByteArray,
    packetPath: String,
  ): List<ForkBranch> {
    if ((cloneMetadata?.fields?.get("clone") as? BoolVal)?.value != true) return emptyList()
    val sessionId =
      (cloneMetadata.fields["clone_session_id"] as? BitVal)?.bits?.value?.toInt()
        ?: return emptyList()
    val session = pipeline.tableStore.getCloneSession(sessionId) ?: return emptyList()
    val cloneState = EgressState(pipeline, ingressOutput = null)
    return session.replicasList.map { replica ->
      val result =
        runEgressCore(cloneState, clonePayload, replica.egressPort, replica.instance, packetPath)
      val subtree =
        if (result.dropped) buildDropTrace(result.events, result.dropReason)
        else buildOutputTrace(result.events, replica.egressPort, result.deparsedBytes)
      ForkBranch.newBuilder()
        .setLabel("clone_port_${replica.egressPort}")
        .setSubtree(subtree)
        .build()
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata initialisation
  // ---------------------------------------------------------------------------

  private fun initIngressMetadata(
    values: Map<String, Value>,
    ingressPort: UInt,
    packetPath: String,
  ) {
    (values["psa_ingress_parser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("ingress_port", ingressPort.toLong())
      it.fields["packet_path"] = EnumVal(packetPath)
    }
    (values["psa_ingress_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("ingress_port", ingressPort.toLong())
      it.fields["packet_path"] = EnumVal(packetPath)
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
    // PSA ports are bit<32>; convert signed Int to unsigned Long for BitVector compatibility.
    val unsignedPort = egressPort.toUInt().toLong()
    (values["psa_egress_parser_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("egress_port", unsignedPort)
      it.fields["packet_path"] = EnumVal(packetPath)
    }
    (values["psa_egress_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("class_of_service", cos)
      it.setBitField("egress_port", unsignedPort)
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
      it.setBitField("egress_port", unsignedPort)
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
  private fun createPsaExternHandler(pipeline: PipelineConfig): ExternHandler {
    // Per-pipeline InternetChecksum state: instance name → running ones' complement sum.
    // Fresh for each pipeline execution (ingress or egress).
    val checksumState = mutableMapOf<String, BigInteger>()
    return ExternHandler { call, eval ->
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
            // Register.read(index) returns T directly (unlike v1model's void + out param).
            "read" ->
              when (call.externType) {
                "Register" -> {
                  val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
                  pipeline.tableStore.registerRead(call.instanceName, index)
                    ?: eval.defaultValue(eval.returnType())
                }
                // PSA Random.read() — 0 args, returns a random value in [min, max] (PSA spec §7.5).
                "Random" -> {
                  val instance = pipeline.externInstances[call.instanceName]
                  val lo = instance?.constructorArgsList?.getOrNull(0)?.literal?.integer ?: 0L
                  val hi = instance?.constructorArgsList?.getOrNull(1)?.literal?.integer ?: 0L
                  val value = if (hi > lo) kotlin.random.Random.nextLong(lo, hi + 1) else lo
                  BitVal(BitVector(BigInteger.valueOf(value), eval.returnType().bit.width))
                }
                else -> error("unhandled PSA extern read: ${call.externType}.read")
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
            // --- InternetChecksum extern (PSA spec §7.7) ---
            "clear" -> {
              checksumState[call.instanceName] = BigInteger.ZERO
              UnitVal
            }
            "add" -> {
              val data = eval.evalArg(0).asStructVal()
              val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
              checksumState[call.instanceName] = onesComplementAdd(sum, sumWords(data))
              UnitVal
            }
            "subtract" -> {
              // RFC 1624: subtract by adding the ones' complement of the data's word sum.
              val data = eval.evalArg(0).asStructVal()
              val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
              val dataSumComplement = CSUM_MASK.subtract(sumWords(data))
              checksumState[call.instanceName] = onesComplementAdd(sum, dataSumComplement)
              UnitVal
            }
            "get" -> {
              val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
              BitVal(BitVector(CSUM_MASK.subtract(sum), CSUM_WORD_BITS))
            }
            "get_state" -> {
              val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
              BitVal(BitVector(sum, CSUM_WORD_BITS))
            }
            "set_state" -> {
              checksumState[call.instanceName] = (eval.evalArg(0) as BitVal).bits.value
              UnitVal
            }
            // Digest.pack() queues a digest message for the control plane. No-op in STF
            // testing since there's no control-plane receiver.
            // TODO(PSA): implement digest delivery via P4Runtime StreamChannel.
            "pack" -> UnitVal
            else ->
              error(
                "unhandled PSA extern method: ${call.externType}.${call.method}" +
                  " on ${call.instanceName}"
              )
          }
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

    val resultMask = BigInteger.TWO.pow(resultWidth) - BigInteger.ONE

    return if (eval.argCount() == 1) {
      // 1-arg form: get_hash(data) → hash(data) truncated to result width
      val data = hashDataArg(eval.evalArg(0))
      val hash = computeHash(algorithm, data).and(resultMask)
      BitVal(BitVector(hash, resultWidth))
    } else {
      // 3-arg form: get_hash(base, data, max) → (base + hash(data)) mod max
      val base = (eval.evalArg(0) as BitVal).bits.value
      val data = hashDataArg(eval.evalArg(1))
      val max = (eval.evalArg(2) as BitVal).bits.value
      val hash = computeHash(algorithm, data)
      val result = if (max > BigInteger.ZERO) (base + hash.mod(max)).and(resultMask) else base
      BitVal(BitVector(result, resultWidth))
    }
  }

  /**
   * Coerces a hash data argument to [StructVal]. The p4c midend usually wraps hash inputs in a
   * StructExpression, but single-field inputs (e.g. `get_hash(hdr.ethernet.srcAddr)`) arrive as
   * bare [BitVal]. Headers arrive as [HeaderVal].
   */
  private fun hashDataArg(value: Value): StructVal =
    when (value) {
      is BitVal -> StructVal("", mutableMapOf("_0" to value))
      else -> value.asStructVal()
    }

  /**
   * Sums all 16-bit words in [data]'s concatenated fields. Returns the raw sum (not complemented).
   */
  private fun sumWords(data: StructVal): BigInteger =
    concatFields(data)?.let { sumBitWords(it) } ?: BigInteger.ZERO

  /** Builds a flat map from extern instance name → declaration across all parsers and controls. */
  private fun buildExternInstancesMap(config: BehavioralConfig): Map<String, ExternInstanceDecl> =
    (config.parsersList.flatMap { it.externInstancesList } +
        config.controlsList.flatMap { it.externInstancesList })
      .associateBy { it.name }

  // ---------------------------------------------------------------------------
  // Trace helpers
  // ---------------------------------------------------------------------------

  private fun buildForkTree(
    events: List<TraceEvent>,
    reason: ForkReason,
    branches: List<ForkBranch>,
  ): TraceTree =
    TraceTree.newBuilder()
      .addAllEvents(events)
      .setForkOutcome(Fork.newBuilder().setReason(reason).addAllBranches(branches))
      .build()

  // Shared trace helpers (buildDropTrace, buildOutputTrace, packetIngressEvent, stageEvent)
  // live in TraceHelpers.kt.

  private companion object {
    // PSA_PacketPath_t enum values (PSA spec §6.1).
    const val PACKET_PATH_NORMAL = "NORMAL"
    const val PACKET_PATH_NORMAL_UNICAST = "NORMAL_UNICAST"
    const val PACKET_PATH_NORMAL_MULTICAST = "NORMAL_MULTICAST"
    const val PACKET_PATH_CLONE_I2E = "CLONE_I2E"
    const val PACKET_PATH_CLONE_E2E = "CLONE_E2E"
    const val PACKET_PATH_RESUBMIT = "RESUBMIT"
    const val PACKET_PATH_RECIRCULATE = "RECIRCULATE"

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

    /** PSA_PORT_RECIRCULATE (psa.p4: `const PortId_t PSA_PORT_RECIRCULATE = 32w0xfffffffa`). */
    const val PSA_PORT_RECIRCULATE_LONG = 0xFFFFFFFAL
    val PSA_PORT_RECIRCULATE_UINT = PSA_PORT_RECIRCULATE_LONG.toUInt()

    /** Guard against infinite recirculation loops (BMv2 psa_switch uses a similar limit). */
    const val MAX_RECIRCULATIONS = 16
  }
}
