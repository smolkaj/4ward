package fourward.simulator

import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.StageKind
import fourward.sim.v1.Drop
import fourward.sim.v1.DropReason
import fourward.sim.v1.Fork
import fourward.sim.v1.ForkBranch
import fourward.sim.v1.ForkReason
import fourward.sim.v1.PacketOutcome
import fourward.sim.v1.TraceEvent
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
 * - clone I2E/E2E at the ingress→egress / post-egress boundary (last-writer-wins).
 * - resubmit at the ingress→egress boundary.
 * - recirculate after deparser (feeds deparsed output back as new input).
 * - multicast group replication via ForkException / re-execution.
 *
 * References:
 * - v1model.p4: https://github.com/p4lang/p4c/blob/main/p4include/v1model.p4
 * - BMv2 simple_switch semantics:
 *   https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md
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
   * the results into a [TraceTree] with a [Fork]. Shared prefix events are stripped from branches.
   */
  private fun buildTraceTree(
    ctx: PipelineContext,
    decisions: ForkDecisions,
    prefixLength: Int,
  ): PipelineResult {
    try {
      val trace = runPipeline(ctx, decisions)
      val stripped = TraceTree.newBuilder().addAllEvents(trace.eventsList.drop(prefixLength))
      if (trace.hasPacketOutcome()) stripped.setPacketOutcome(trace.packetOutcome)
      return PipelineResult(stripped.build())
    } catch (fork: ForkException) {
      val levelEvents = fork.eventsBeforeFork.drop(prefixLength)
      val (reason, branches) = buildForkBranches(ctx, decisions, fork)
      val tree =
        TraceTree.newBuilder()
          .addAllEvents(levelEvents)
          .setForkOutcome(Fork.newBuilder().setReason(reason).addAllBranches(branches))
          .build()
      return PipelineResult(tree)
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
        val originalDecisions =
          decisions.copy(branchMode = BranchMode.Normal(suppressI2EClone = true))
        val cloneDecisions = decisions.copy(branchMode = BranchMode.I2EClone(fork.sessionId))
        // The clone branch skips ingress, so its prefix is only parser events.
        val branches =
          listOf(
            forkBranch("original", ctx, originalDecisions, fork.eventsBeforeFork.size),
            forkBranch("clone", ctx, cloneDecisions, fork.parserEventCount),
          )
        ForkReason.CLONE to branches
      }
      is EgressCloneFork -> {
        val originalDecisions =
          decisions.copy(branchMode = BranchMode.Normal(suppressE2EClone = true))
        val cloneDecisions = decisions.copy(branchMode = BranchMode.E2EClone(fork.sessionId))
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
            val replicaDecisions = decisions.copy(branchMode = replica)
            forkBranch(
              "replica_${replica.rid}_port_${replica.port}",
              ctx,
              replicaDecisions,
              fork.eventsBeforeFork.size,
            )
          }
        ForkReason.MULTICAST to branches
      }
      is ResubmitFork -> {
        val newDecisions =
          ForkDecisions(
            pipelineDepth = decisions.pipelineDepth + 1,
            instanceTypeOverride = RESUBMIT_INSTANCE_TYPE,
          )
        val branch = forkBranch("resubmit", ctx, newDecisions, fork.eventsBeforeFork.size)
        ForkReason.RESUBMIT to listOf(branch)
      }
      is RecirculateFork -> {
        // Fresh pipeline execution with deparsed bytes as new input — no shared prefix.
        val newCtx = ctx.copy(payload = fork.deparsedBytes)
        val newDecisions =
          ForkDecisions(
            pipelineDepth = decisions.pipelineDepth + 1,
            instanceTypeOverride = RECIRC_INSTANCE_TYPE,
          )
        val result = buildTraceTree(newCtx, newDecisions, prefixLength = 0)
        val branch =
          ForkBranch.newBuilder().setLabel("recirculate").setSubtree(result.trace).build()
        ForkReason.RECIRCULATE to listOf(branch)
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
    require(decisions.pipelineDepth <= MAX_PIPELINE_DEPTH) { "max pipeline depth exceeded" }
    val packetCtx = PacketContext(ctx.payload)
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
    if (decisions.instanceTypeOverride != null) {
      standardMetadata.fields["instance_type"] = BitVal(decisions.instanceTypeOverride, INT32_BITS)
    }

    val interpreter =
      Interpreter(ctx.config, ctx.tableStore, packetCtx, decisions) {
        standardMetadata.fields["checksum_error"] = BitVal(1L, 1)
      }

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

  /** Executes the full v1model pipeline once, returning a flat trace tree with a leaf outcome. */
  @Suppress("CyclomaticComplexMethod", "ThrowsCount")
  private fun runPipeline(ctx: PipelineContext, decisions: ForkDecisions): TraceTree {
    val s = initPipelineState(ctx, decisions)

    // --- Parser ---
    if (s.parserStage != null) {
      try {
        s.interpreter.runParser(s.parserStage.blockName, s.env)
      } catch (e: ExitException) {
        return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
      } catch (e: ParserErrorException) {
        // BMv2 v1model: parser errors don't drop the packet. Set parser_error and
        // continue to the ingress pipeline, letting the P4 program decide the fate.
        s.standardMetadata.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    val parserEventCount = s.packetCtx.getEvents().size

    // --- Ingress controls (verify checksum, ingress) ---
    // I2E clone branch: skip ingress — the clone gets the original parsed packet,
    // not the version modified by ingress (BMv2 simple_switch semantics).
    if (decisions.branchMode !is BranchMode.I2EClone) {
      runControlStages(s, s.ingressControls)
    }

    // --- Ingress→egress boundary ---
    ingressEgressBoundary(ctx, s, decisions, parserEventCount)

    // --- Egress controls (egress, compute checksum) ---
    runControlStages(s, s.egressControls)

    // --- Post-egress boundary ---
    postEgressBoundary(ctx, s, decisions)

    val egressPort =
      (s.standardMetadata.fields["egress_port"] as? BitVal)?.bits?.value?.toInt() ?: 0

    // Port 511 is the v1model drop port (mark_to_drop sets egress_spec = 511).
    if (egressPort == DROP_PORT) {
      return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
    }

    // --- Deparser ---
    if (s.deparserStage != null) {
      s.interpreter.runControl(s.deparserStage.blockName, s.env)
    }

    // Append any bytes the parser did not extract (the un-parsed packet body).
    // In P4, the deparser emits re-serialised headers; the remaining payload
    // is transparently forwarded after them.
    val outputBytes = s.packetCtx.outputPayload() + s.packetCtx.drainRemainingInput()

    if (s.packetCtx.pendingRecirculate) {
      throw RecirculateFork(outputBytes, s.packetCtx.getEvents())
    }

    return buildOutputTrace(s.packetCtx.getEvents(), egressPort, outputBytes)
  }

  /** Runs a list of control stages, breaking on exit. */
  private fun runControlStages(s: PipelineState, stages: List<PipelineStage>) {
    for (stage in stages) {
      try {
        s.interpreter.runControl(stage.blockName, s.env)
      } catch (_: ExitException) {
        break
      }
    }
  }

  /**
   * Handles the ingress→egress boundary.
   *
   * BMv2 priority order: I2E clone > resubmit > multicast > unicast/drop. See
   * https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md
   */
  @Suppress("ThrowsCount") // one throw per fork type at the boundary
  private fun ingressEgressBoundary(
    ctx: PipelineContext,
    s: PipelineState,
    decisions: ForkDecisions,
    parserEventCount: Int,
  ) {
    when (val mode = decisions.branchMode) {
      is BranchMode.I2EClone -> {
        // I2E clone branch: set up clone metadata and continue to egress.
        val session =
          ctx.tableStore.getCloneSession(mode.sessionId)
            ?: error("unknown clone session: ${mode.sessionId}")
        val clonePort = session.replicasList.firstOrNull()?.egressPort ?: 0
        s.standardMetadata.fields["instance_type"] = BitVal(CLONE_I2E_INSTANCE_TYPE, INT32_BITS)
        s.standardMetadata.fields["egress_port"] = BitVal(clonePort.toLong(), PORT_BITS)
      }
      is BranchMode.Replica -> {
        s.standardMetadata.fields["instance_type"] = BitVal(REPLICATION_INSTANCE_TYPE, INT32_BITS)
        s.standardMetadata.fields["egress_port"] = BitVal(mode.port.toLong(), PORT_BITS)
        s.standardMetadata.fields["egress_rid"] = BitVal(mode.rid.toLong(), REPLICA_ID_BITS)
      }
      is BranchMode.Normal,
      is BranchMode.E2EClone -> {
        // Normal path: check for pending I2E clone, resubmit, multicast, unicast.
        val suppressI2E = (mode as? BranchMode.Normal)?.suppressI2EClone == true
        val pendingClone = s.packetCtx.pendingCloneSessionId
        if (pendingClone != null && !suppressI2E) {
          throw CloneFork(pendingClone, parserEventCount, s.packetCtx.getEvents())
        }
        if (s.packetCtx.pendingResubmit) {
          throw ResubmitFork(s.packetCtx.getEvents())
        }
        val mcastGrp =
          (s.standardMetadata.fields["mcast_grp"] as? BitVal)?.bits?.value?.toInt() ?: 0
        if (mcastGrp != 0) {
          val group =
            ctx.tableStore.getMulticastGroup(mcastGrp)
              ?: error("unknown multicast group: $mcastGrp")
          val replicas =
            group.replicasList.map { r -> BranchMode.Replica(r.instance, r.egressPort) }
          throw MulticastFork(replicas, s.packetCtx.getEvents())
        }
        // Normal unicast: copy egress_spec → egress_port for uniform read below.
        s.standardMetadata.fields["egress_port"] =
          s.standardMetadata.fields["egress_spec"] ?: BitVal(0, PORT_BITS)
      }
    }
  }

  /**
   * Handles the post-egress boundary.
   *
   * BMv2 priority order after egress: E2E clone > recirculate > output/drop.
   */
  private fun postEgressBoundary(ctx: PipelineContext, s: PipelineState, decisions: ForkDecisions) {
    when (val mode = decisions.branchMode) {
      is BranchMode.E2EClone -> {
        // E2E clone branch: after the original egress ran (giving us modified headers),
        // re-run egress with CLONE_E2E instance_type and the clone session's egress_port.
        // This matches BMv2 semantics where the E2E clone re-enters egress with the
        // post-egress packet state.
        val session =
          ctx.tableStore.getCloneSession(mode.sessionId)
            ?: error("unknown clone session: ${mode.sessionId}")
        val clonePort = session.replicasList.firstOrNull()?.egressPort ?: 0
        s.standardMetadata.fields["instance_type"] = BitVal(CLONE_E2E_INSTANCE_TYPE, INT32_BITS)
        s.standardMetadata.fields["egress_port"] = BitVal(clonePort.toLong(), PORT_BITS)
        s.packetCtx.pendingEgressCloneSessionId = null
        runControlStages(s, s.egressControls)
      }
      else -> {
        val suppressE2E = (mode as? BranchMode.Normal)?.suppressE2EClone == true
        val pendingE2EClone = s.packetCtx.pendingEgressCloneSessionId
        if (pendingE2EClone != null && !suppressE2E) {
          throw EgressCloneFork(pendingE2EClone, s.packetCtx.getEvents())
        }
      }
    }
  }

  private fun buildDropTrace(events: List<TraceEvent>, reason: DropReason): TraceTree {
    val outcome = PacketOutcome.newBuilder().setDrop(Drop.newBuilder().setReason(reason)).build()
    return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
  }

  private fun buildOutputTrace(events: List<TraceEvent>, port: Int, payload: ByteArray): TraceTree {
    val output =
      fourward.sim.v1.OutputPacket.newBuilder()
        .setEgressPort(port)
        .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
        .build()
    val outcome = PacketOutcome.newBuilder().setOutput(output).build()
    return TraceTree.newBuilder().addAllEvents(events).setPacketOutcome(outcome).build()
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

    private const val MAX_PIPELINE_DEPTH = 10

    // v1model instance_type values (BMv2 PktInstanceType convention).
    private const val CLONE_I2E_INSTANCE_TYPE = 1L
    private const val CLONE_E2E_INSTANCE_TYPE = 2L
    private const val RECIRC_INSTANCE_TYPE = 4L
    private const val REPLICATION_INSTANCE_TYPE = 5L
    private const val RESUBMIT_INSTANCE_TYPE = 6L
  }
}
