package fourward.simulator

import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.StageKind
import fourward.sim.v1.ForkBranch
import fourward.sim.v1.ForkNode
import fourward.sim.v1.ForkReason
import fourward.sim.v1.TraceTree

/**
 * v1model pipeline implementation.
 *
 * v1model is the original BMv2 architecture, defined in v1model.p4. The pipeline runs six stages in
 * fixed order:
 *
 * MyParser -> MyVerifyChecksum -> MyIngress -> MyEgress -> MyComputeChecksum -> MyDeparser
 *
 * Architecture-specific behaviour implemented here:
 * - standard_metadata_t initialisation and egress_spec routing.
 * - mark_to_drop() (sets egress_spec to DROP_PORT = 511).
 * - clone (I2E) via ForkException / re-execution.
 * - multicast group replication via ForkException / re-execution.
 *
 * Reference: https://github.com/p4lang/p4c/blob/main/p4include/v1model.p4
 */
class V1ModelArchitecture : Architecture {

  /** Invariant inputs to the pipeline, shared across fork re-executions. */
  private data class PipelineContext(
    val ingressPort: UInt,
    val payload: ByteArray,
    val config: P4BehavioralConfig,
    val tableStore: TableStore,
  )

  /** Per-execution state created fresh for each pipeline run. */
  private class PipelineState(
    val packetCtx: PacketContext,
    val interpreter: Interpreter,
    val env: Environment,
    val standardMetadata: StructVal,
    config: P4BehavioralConfig,
  ) {
    private val stages = config.architecture.stagesList
    val parserStage: PipelineStage? = stages.find { it.kind == StageKind.PARSER }
    val deparserStage: PipelineStage? = stages.find { it.kind == StageKind.DEPARSER }

    // v1model: first 2 controls are ingress-side (verify checksum, ingress),
    // last 2 are egress-side (egress, compute checksum).
    private val controlStages = stages.filter { it.kind == StageKind.CONTROL }
    val ingressControls: List<PipelineStage> = controlStages.take(INGRESS_CONTROL_COUNT)
    val egressControls: List<PipelineStage> = controlStages.drop(INGRESS_CONTROL_COUNT)
  }

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: P4BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    val ctx = PipelineContext(ingressPort, payload, config, tableStore)
    return buildTraceTree(ctx, ForkDecisions(), prefixLength = 0)
  }

  /**
   * Recursively builds a trace tree by re-executing the pipeline for each fork branch.
   *
   * When a [ForkException] is thrown (action selector, clone, or multicast), this method
   * re-executes the full pipeline once per branch with appropriate [ForkDecisions], and assembles
   * the results into a [TraceTree] with a [ForkNode]. Shared prefix events are stripped from
   * branches.
   */
  private fun buildTraceTree(
    ctx: PipelineContext,
    decisions: ForkDecisions,
    prefixLength: Int,
  ): PipelineResult {
    try {
      val (outputs, trace) = runPipeline(ctx, decisions)
      val stripped =
        TraceTree.newBuilder().addAllEvents(trace.eventsList.drop(prefixLength)).build()
      return PipelineResult(outputs, stripped)
    } catch (fork: ForkException) {
      val levelEvents = fork.eventsBeforeFork.drop(prefixLength)
      val (reason, branches) = buildForkBranches(ctx, decisions, fork)
      val tree =
        TraceTree.newBuilder()
          .addAllEvents(levelEvents)
          .setFork(ForkNode.newBuilder().setReason(reason).addAllBranches(branches))
          .build()
      return PipelineResult(emptyList(), tree)
    }
  }

  /** Dispatches fork handling to the appropriate branch builder. */
  private fun buildForkBranches(
    ctx: PipelineContext,
    decisions: ForkDecisions,
    fork: ForkException,
  ): Pair<ForkReason, List<ForkBranch>> =
    when (fork) {
      is ActionSelectorFork -> {
        val branches =
          fork.members.map { member ->
            val newDecisions =
              decisions.copy(
                selectorMembers = decisions.selectorMembers + (fork.tableName to member.memberId)
              )
            forkBranch("member_${member.memberId}", ctx, newDecisions, fork.eventsBeforeFork.size)
          }
        ForkReason.ACTION_SELECTOR to branches
      }
      is CloneFork -> {
        val originalDecisions = decisions.copy(cloneMode = CloneMode.SUPPRESS)
        val cloneDecisions =
          decisions.copy(cloneMode = CloneMode.EXECUTE_CLONE, cloneSessionId = fork.sessionId)
        val branches =
          listOf(
            forkBranch("original", ctx, originalDecisions, fork.eventsBeforeFork.size),
            forkBranch("clone", ctx, cloneDecisions, fork.eventsBeforeFork.size),
          )
        ForkReason.CLONE to branches
      }
      is MulticastFork -> {
        val branches =
          fork.replicas.map { replica ->
            val replicaDecisions = decisions.copy(multicastReplica = replica)
            forkBranch(
              "replica_${replica.rid}_port_${replica.port}",
              ctx,
              replicaDecisions,
              fork.eventsBeforeFork.size,
            )
          }
        ForkReason.MULTICAST to branches
      }
    }

  /** Re-executes the pipeline for one branch and wraps the result in a [ForkBranch]. */
  private fun forkBranch(
    label: String,
    ctx: PipelineContext,
    decisions: ForkDecisions,
    prefixLength: Int,
  ): ForkBranch {
    val result = buildTraceTree(ctx, decisions, prefixLength)
    return ForkBranch.newBuilder().setLabel(label).setSubtree(result.trace).build()
  }

  /**
   * Creates a fresh [PipelineState] for one pipeline execution.
   *
   * Resolves type names, initialises standard_metadata, and binds parameter names across all
   * stages. Stage topology (ingress/egress split) is derived by [PipelineState] itself.
   */
  private fun initPipelineState(ctx: PipelineContext, decisions: ForkDecisions): PipelineState {
    val packetCtx = PacketContext(ctx.payload)
    val interpreter = Interpreter(ctx.config, ctx.tableStore, packetCtx, decisions)
    val env = Environment()
    val config = ctx.config
    val typesByName = config.typesList.associateBy { it.name }

    // Derive the type names for hdr/meta/standard_metadata from the parser's
    // parameter list, filtering out the architecture-level packet I/O params.
    // v1model always declares: (packet_in, hdr, meta, standard_metadata) in that order.
    val ioTypes = setOf("packet_in", "packet_out")
    val parserUserParams =
      config.parsersList.first().paramsList.filter {
        it.type.hasNamed() && it.type.named !in ioTypes
      }
    require(parserUserParams.size == V1MODEL_USER_PARAM_COUNT) {
      "Expected $V1MODEL_USER_PARAM_COUNT non-IO parser params, got ${parserUserParams.size}"
    }
    val headersTypeName = parserUserParams[0].type.named
    val metaTypeName = parserUserParams[1].type.named
    val standardMetaTypeName = parserUserParams[2].type.named

    val standardMetadata =
      (defaultValue(standardMetaTypeName, typesByName) as? StructVal)
        ?: error("$standardMetaTypeName not found in IR types; is v1model.p4 included?")
    check("ingress_port" in standardMetadata.fields) { "$standardMetaTypeName has no ingress_port" }
    check("packet_length" in standardMetadata.fields) {
      "$standardMetaTypeName has no packet_length"
    }
    standardMetadata.fields["ingress_port"] = BitVal(ctx.ingressPort.toLong(), PORT_BITS)
    standardMetadata.fields["packet_length"] = BitVal(ctx.payload.size.toLong(), INT32_BITS)
    standardMetadata.fields["parser_error"] = ErrorVal("NoError")

    val sharedByType =
      mapOf(
        headersTypeName to defaultValue(headersTypeName, typesByName),
        metaTypeName to defaultValue(metaTypeName, typesByName),
        standardMetaTypeName to standardMetadata,
      )
    for (parser in config.parsersList) {
      for (param in parser.paramsList) {
        sharedByType[param.type.named]?.let { env.define(param.name, it) }
      }
    }
    for (control in config.controlsList) {
      for (param in control.paramsList) {
        sharedByType[param.type.named]?.let { env.define(param.name, it) }
      }
    }

    return PipelineState(packetCtx, interpreter, env, standardMetadata, config)
  }

  /** Executes the full v1model pipeline once, returning output packets and flat trace. */
  @Suppress("LoopWithTooManyJumpStatements")
  private fun runPipeline(ctx: PipelineContext, decisions: ForkDecisions): PipelineResult {
    val s = initPipelineState(ctx, decisions)

    // --- Parser ---
    if (s.parserStage != null) {
      try {
        s.interpreter.runParser(s.parserStage.blockName, s.env)
      } catch (e: ExitException) {
        return PipelineResult(emptyList(), s.packetCtx.buildTrace())
      } catch (e: ParserErrorException) {
        // BMv2 v1model: parser errors don't drop the packet. Set parser_error and
        // continue to the ingress pipeline, letting the P4 program decide the fate.
        s.standardMetadata.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // --- Ingress controls (verify checksum, ingress) ---
    // JumpToEgressException signals clone-branch re-execution: stop ingress, run egress only.
    var jumpToEgress = false
    for (stage in s.ingressControls) {
      try {
        s.interpreter.runControl(stage.blockName, s.env)
      } catch (_: ExitException) {
        break
      } catch (_: JumpToEgressException) {
        jumpToEgress = true
        break
      }
    }

    // --- Ingress→egress boundary: clone / multicast metadata setup ---
    // All paths write egress_port into standardMetadata so the egress read is uniform.
    if (jumpToEgress) {
      // Clone branch: set instance_type and egress_port from clone session config.
      val session = ctx.tableStore.getCloneSession(decisions.cloneSessionId)
      val clonePort = session?.replicasList?.firstOrNull()?.egressPort ?: 0
      s.standardMetadata.fields["instance_type"] = BitVal(CLONE_I2E_INSTANCE_TYPE, INT32_BITS)
      s.standardMetadata.fields["egress_port"] = BitVal(clonePort.toLong(), PORT_BITS)
    } else {
      val mcastGrp = (s.standardMetadata.fields["mcast_grp"] as? BitVal)?.bits?.value?.toInt() ?: 0
      if (mcastGrp != 0 && decisions.multicastReplica == null) {
        val group =
          ctx.tableStore.getMulticastGroup(mcastGrp) ?: error("unknown multicast group: $mcastGrp")
        val replicas = group.replicasList.map { r -> MulticastReplica(r.instance, r.egressPort) }
        throw MulticastFork(replicas, s.packetCtx.getEvents())
      }
      if (decisions.multicastReplica != null) {
        s.standardMetadata.fields["egress_port"] =
          BitVal(decisions.multicastReplica.port.toLong(), PORT_BITS)
        s.standardMetadata.fields["egress_rid"] =
          BitVal(decisions.multicastReplica.rid.toLong(), REPLICA_ID_BITS)
      } else {
        // Normal unicast: copy egress_spec → egress_port for uniform read below.
        s.standardMetadata.fields["egress_port"] =
          s.standardMetadata.fields["egress_spec"] ?: BitVal(0, PORT_BITS)
      }
    }

    // --- Egress controls (egress, compute checksum) ---
    for (stage in s.egressControls) {
      try {
        s.interpreter.runControl(stage.blockName, s.env)
      } catch (_: ExitException) {
        break // skip remaining control stages; still run deparser below
      }
    }

    val egressPort =
      (s.standardMetadata.fields["egress_port"] as? BitVal)?.bits?.value?.toInt() ?: 0

    // Port 511 is the v1model drop port (mark_to_drop sets egress_spec = 511).
    if (egressPort == DROP_PORT) {
      return PipelineResult(emptyList(), s.packetCtx.buildTrace())
    }

    // --- Deparser ---
    if (s.deparserStage != null) {
      s.interpreter.runControl(s.deparserStage.blockName, s.env)
    }

    // Append any bytes the parser did not extract (the un-parsed packet body).
    // In P4, the deparser emits re-serialised headers; the remaining payload
    // is transparently forwarded after them.
    val outputBytes = s.packetCtx.outputPayload() + s.packetCtx.drainRemainingInput()
    val output = OutputPacket(egressPort.toUInt(), outputBytes)
    return PipelineResult(listOf(output), s.packetCtx.buildTrace())
  }

  companion object {
    /** Port value used by mark_to_drop() to signal packet drop in v1model. */
    const val DROP_PORT = 511

    // Number of user-visible params in the v1model parser after removing packet_in/packet_out:
    // (hdr, meta, standard_metadata).
    private const val V1MODEL_USER_PARAM_COUNT = 3

    // v1model: first 2 control stages are ingress-side (verify checksum, ingress).
    private const val INGRESS_CONTROL_COUNT = 2

    // Bit widths for standard_metadata_t fields, as defined in v1model.p4.
    const val PORT_BITS = 9
    private const val INT32_BITS = 32
    private const val REPLICA_ID_BITS = 16

    // v1model instance_type values (BMv2 convention).
    private const val CLONE_I2E_INSTANCE_TYPE = 1L
  }
}
