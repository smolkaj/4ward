package fourward.simulator

import fourward.ir.BehavioralConfig
import fourward.ir.PipelineStage
import fourward.ir.StageKind
import fourward.ir.StructDecl
import fourward.sim.SimulatorProto.CloneEvent
import fourward.sim.SimulatorProto.CloneSessionLookupEvent
import fourward.sim.SimulatorProto.DropReason
import fourward.sim.SimulatorProto.Fork
import fourward.sim.SimulatorProto.ForkBranch
import fourward.sim.SimulatorProto.ForkReason
import fourward.sim.SimulatorProto.LogMessageEvent
import fourward.sim.SimulatorProto.MarkToDropEvent
import fourward.sim.SimulatorProto.PipelineStageEvent
import fourward.sim.SimulatorProto.TraceEvent
import fourward.sim.SimulatorProto.TraceTree
import java.math.BigInteger

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
 * - mark_to_drop() (sets egress_spec to all-ones = drop port).
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
/**
 * Evaluates an extern argument as an Int, handling both sized (BitVal) and unsized (InfIntVal)
 * integer literals. p4c sometimes emits unsized literals for field list IDs.
 */
private fun evalIntArg(eval: ExternEvaluator, index: Int): Int =
  when (val arg = eval.evalArg(index)) {
    is BitVal -> arg.bits.value.toInt()
    is InfIntVal -> arg.value.toInt()
    else -> error("expected integer argument at index $index, got: $arg")
  }

class V1ModelArchitecture : Architecture {

  /** Invariant inputs to the pipeline, shared across fork re-executions. */
  private data class PipelineContext(
    val ingressPort: UInt,
    val payload: ByteArray,
    val config: BehavioralConfig,
    val tableStore: TableStore,
  )

  /** Per-execution state created fresh for each pipeline run. */
  @Suppress("LongParameterList")
  private class PipelineState(
    val packetCtx: PacketContext,
    val pendingOps: V1ModelPendingOps,
    val interpreter: Interpreter,
    val env: Environment,
    val standardMetadata: StructVal,
    val metaParamName: String,
    val metaStructDecl: StructDecl?,
    config: BehavioralConfig,
  ) {
    private val stages = config.architecture.stagesList
    val parserStage: PipelineStage? = stages.find { it.kind == StageKind.PARSER }
    val deparserStage: PipelineStage? = stages.find { it.kind == StageKind.DEPARSER }

    // v1model: first 2 controls are ingress-side (verify checksum, ingress),
    // last 2 are egress-side (egress, compute checksum).
    private val controlStages = stages.filter { it.kind == StageKind.CONTROL }
    val ingressControls: List<PipelineStage> = controlStages.take(INGRESS_CONTROL_COUNT)
    val egressControls: List<PipelineStage> = controlStages.drop(INGRESS_CONTROL_COUNT)

    // Port width and drop port derived from the IR's standard_metadata struct definition,
    // not hardcoded. This allows modified v1model architectures with wider PortId_t.
    val portBits: Int = standardMetadata.bitWidth("ingress_port")
    val dropPort: Long = (1L shl portBits) - 1
  }

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    val ctx = PipelineContext(ingressPort, payload, config, tableStore)
    return buildTraceTree(ctx, V1ModelDecisions(), prefixLength = 0)
  }

  /**
   * Iteratively builds a trace tree by re-executing the pipeline for each fork branch.
   *
   * Uses an explicit work stack instead of recursion so that deeply nested fork chains (clone →
   * recirculate → clone → ...) don't overflow the JVM call stack. Each iteration runs one pipeline
   * execution; forks push new work items instead of recursing.
   */
  private fun buildTraceTree(
    ctx: PipelineContext,
    decisions: V1ModelDecisions,
    prefixLength: Int,
  ): PipelineResult {
    val workStack = ArrayDeque<Frame>()
    val resultStack = ArrayDeque<TraceTree>()
    var executions = 0
    workStack.addFirst(Frame.Run(ctx, decisions, prefixLength))

    while (workStack.isNotEmpty()) {
      when (val frame = workStack.removeFirst()) {
        is Frame.Run -> {
          if (++executions > MAX_PIPELINE_EXECUTIONS) {
            resultStack.addFirst(
              buildDropTrace(emptyList(), DropReason.PIPELINE_EXECUTION_LIMIT_REACHED)
            )
            continue
          }
          try {
            val trace = runPipeline(frame.ctx, frame.decisions)
            val stripped =
              TraceTree.newBuilder().addAllEvents(trace.eventsList.drop(frame.prefixLength))
            if (trace.hasPacketOutcome()) stripped.setPacketOutcome(trace.packetOutcome)
            resultStack.addFirst(stripped.build())
          } catch (fork: ForkException) {
            val levelEvents = fork.eventsBeforeFork.drop(frame.prefixLength)
            val (reason, specs) = forkSpecs(frame.ctx, frame.decisions, fork)
            // Push assemble frame first so it runs after all children complete.
            workStack.addFirst(Frame.Assemble(reason, specs.map { it.label }, levelEvents))
            // Push children in forward order: last child at top → processed first →
            // result pushed first → popped last during assembly → correct order.
            for (spec in specs) {
              workStack.addFirst(Frame.Run(spec.ctx, spec.decisions, spec.prefixLength))
            }
          }
        }
        is Frame.Assemble -> {
          val branches =
            frame.labels.map { label ->
              ForkBranch.newBuilder().setLabel(label).setSubtree(resultStack.removeFirst()).build()
            }
          val tree =
            TraceTree.newBuilder()
              .addAllEvents(frame.events)
              .setForkOutcome(Fork.newBuilder().setReason(frame.reason).addAllBranches(branches))
              .build()
          resultStack.addFirst(tree)
        }
      }
    }

    return PipelineResult(resultStack.removeFirst())
  }

  /** Work stack frames for iterative [buildTraceTree]. */
  private sealed class Frame {
    data class Run(
      val ctx: PipelineContext,
      val decisions: V1ModelDecisions,
      val prefixLength: Int,
    ) : Frame()

    data class Assemble(
      val reason: ForkReason,
      val labels: List<String>,
      val events: List<TraceEvent>,
    ) : Frame()
  }

  /** A branch specification: everything needed to re-execute one fork branch. */
  private data class BranchSpec(
    val label: String,
    val ctx: PipelineContext,
    val decisions: V1ModelDecisions,
    val prefixLength: Int,
  )

  /** Maps a [ForkException] to the fork reason and per-branch execution specs. */
  private fun forkSpecs(
    ctx: PipelineContext,
    decisions: V1ModelDecisions,
    fork: ForkException,
  ): Pair<ForkReason, List<BranchSpec>> =
    when (fork) {
      is ActionSelectorFork -> {
        val specs =
          fork.members.map { member ->
            val d =
              decisions.copy(
                selectorMembers = decisions.selectorMembers + (fork.tableName to member.memberId)
              )
            BranchSpec("member_${member.memberId}", ctx, d, fork.eventsBeforeFork.size)
          }
        ForkReason.ACTION_SELECTOR to specs
      }
      is CloneFork -> {
        // The clone branch skips ingress, so its prefix is only parser events.
        ForkReason.CLONE to
          listOf(
            BranchSpec(
              "original",
              ctx,
              decisions.copy(branchMode = BranchMode.Normal(suppressI2EClone = true)),
              fork.eventsBeforeFork.size,
            ),
            BranchSpec(
              "clone",
              ctx,
              decisions.copy(
                branchMode = BranchMode.I2EClone(fork.sessionId, fork.clonePort),
                preservedMetadata = fork.preservedMetadata,
              ),
              fork.parserEventCount,
            ),
          )
      }
      is EgressCloneFork -> {
        ForkReason.CLONE to
          listOf(
            BranchSpec(
              "original",
              ctx,
              decisions.copy(branchMode = BranchMode.Normal(suppressE2EClone = true)),
              fork.eventsBeforeFork.size,
            ),
            BranchSpec(
              "clone",
              ctx,
              decisions.copy(
                branchMode = BranchMode.E2EClone(fork.sessionId, fork.clonePort),
                preservedMetadata = fork.preservedMetadata,
              ),
              fork.eventsBeforeFork.size,
            ),
          )
      }
      is MulticastFork -> {
        val specs =
          fork.replicas.map { replica ->
            BranchSpec(
              "replica_${replica.rid}_port_${replica.port}",
              ctx,
              decisions.copy(branchMode = replica),
              fork.eventsBeforeFork.size,
            )
          }
        ForkReason.MULTICAST to specs
      }
      is ResubmitFork -> {
        val d =
          V1ModelDecisions(
            pipelineDepth = decisions.pipelineDepth + 1,
            instanceTypeOverride = RESUBMIT_INSTANCE_TYPE,
            preservedMetadata = fork.preservedMetadata,
          )
        ForkReason.RESUBMIT to listOf(BranchSpec("resubmit", ctx, d, fork.eventsBeforeFork.size))
      }
      is RecirculateFork -> {
        val d =
          V1ModelDecisions(
            pipelineDepth = decisions.pipelineDepth + 1,
            instanceTypeOverride = RECIRC_INSTANCE_TYPE,
            preservedMetadata = fork.preservedMetadata,
          )
        ForkReason.RECIRCULATE to
          listOf(BranchSpec("recirculate", ctx.copy(payload = fork.deparsedBytes), d, 0))
      }
    }

  /**
   * Creates a fresh [PipelineState] for one pipeline execution.
   *
   * Resolves type names, initialises standard_metadata, and binds parameter names across all
   * stages. Stage topology (ingress/egress split) is derived by [PipelineState] itself.
   */
  private fun initPipelineState(ctx: PipelineContext, decisions: V1ModelDecisions): PipelineState {
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
    standardMetadata.setBitField("ingress_port", ctx.ingressPort.toLong())
    standardMetadata.setBitField("packet_length", ctx.payload.size.toLong())
    standardMetadata.fields["parser_error"] = ErrorVal("NoError")
    if (decisions.instanceTypeOverride != null) {
      standardMetadata.setBitField("instance_type", decisions.instanceTypeOverride)
    }

    val pendingOps = V1ModelPendingOps()
    val interpreter =
      Interpreter(
        ctx.config,
        ctx.tableStore,
        packetCtx,
        decisions.selectorMembers,
        createExternHandler(standardMetadata, pendingOps, ctx.tableStore),
      )

    val metaValue = defaultValue(metaTypeName, typesByName)

    // Restore pre-filtered preserved metadata from clone/resubmit/recirculate.
    if (decisions.preservedMetadata != null && metaValue is StructVal) {
      for ((name, value) in decisions.preservedMetadata) {
        metaValue.fields[name] = value
      }
    }

    val sharedByType =
      mapOf(
        headersTypeName to defaultValue(headersTypeName, typesByName),
        metaTypeName to metaValue,
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

    val metaParamName = parserUserParams[1].name
    val metaStructDecl = typesByName[metaTypeName]?.struct
    return PipelineState(
      packetCtx,
      pendingOps,
      interpreter,
      env,
      standardMetadata,
      metaParamName,
      metaStructDecl,
      config,
    )
  }

  /** Executes the full v1model pipeline once, returning a flat trace tree with a leaf outcome. */
  @Suppress("CyclomaticComplexMethod", "ThrowsCount")
  private fun runPipeline(ctx: PipelineContext, decisions: V1ModelDecisions): TraceTree {
    val s = initPipelineState(ctx, decisions)

    // --- Packet ingress ---
    s.packetCtx.addTraceEvent(packetIngressEvent(ctx.ingressPort))

    // --- Parser ---
    var parserExitDrop = false
    if (s.parserStage != null) {
      s.packetCtx.addTraceEvent(stageEvent(s.parserStage, PipelineStageEvent.Direction.ENTER))
      try {
        s.interpreter.runParser(s.parserStage.blockName, s.env)
      } catch (_: ExitException) {
        parserExitDrop = true
      } catch (e: ParserErrorException) {
        // BMv2 v1model: parser errors don't drop the packet. Set parser_error and
        // continue to the ingress pipeline, letting the P4 program decide the fate.
        s.standardMetadata.fields["parser_error"] = ErrorVal(e.errorName)
      } finally {
        s.packetCtx.addTraceEvent(stageEvent(s.parserStage, PipelineStageEvent.Direction.EXIT))
      }
    }
    if (parserExitDrop) return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)

    val parserEventCount = s.packetCtx.getEvents().size

    // An assert()/assume() failure anywhere in the pipeline drops the packet.
    try {
      // --- Ingress controls (verify checksum, ingress) ---
      // I2E clone branch: skip ingress — the clone gets the original parsed packet,
      // not the version modified by ingress (BMv2 simple_switch semantics).
      if (decisions.branchMode !is BranchMode.I2EClone) {
        runControlStages(s, s.ingressControls)
      }

      // --- Ingress→egress boundary (traffic manager) ---
      ingressEgressBoundary(ctx, s, decisions, parserEventCount)
      if (egressPortIsDropPort(s)) {
        return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
      }

      // --- Egress controls (egress, compute checksum) ---
      // BMv2: egress_spec starts matching egress_port for each egress run. This
      // ensures mark_to_drop() during egress is the only way to set the drop port,
      // regardless of what ingress or a prior egress run left in egress_spec.
      resetEgressSpec(s)
      runControlStages(s, s.egressControls)

      // --- Post-egress boundary (E2E clone / recirculate) ---
      postEgressBoundary(ctx, s, decisions)

      // E2E clone branch: postEgressBoundary set up clone metadata — run egress again.
      if (decisions.branchMode is BranchMode.E2EClone) {
        resetEgressSpec(s)
        runControlStages(s, s.egressControls)
      }

      // mark_to_drop() called during egress (or the E2E clone's second egress run)
      // sets egress_spec to the drop port.
      if (egressSpecIsDropPort(s)) {
        return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
      }

      // --- Deparser ---
      if (s.deparserStage != null) {
        s.packetCtx.addTraceEvent(stageEvent(s.deparserStage, PipelineStageEvent.Direction.ENTER))
        try {
          s.interpreter.runControl(s.deparserStage.blockName, s.env)
        } finally {
          s.packetCtx.addTraceEvent(stageEvent(s.deparserStage, PipelineStageEvent.Direction.EXIT))
        }
      }
    } catch (_: AssertionFailureException) {
      return buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
    }

    // Append any bytes the parser did not extract (the un-parsed packet body).
    // In P4, the deparser emits re-serialised headers; the remaining payload
    // is transparently forwarded after them.
    val outputBytes = s.packetCtx.outputPayload() + s.packetCtx.drainRemainingInput()

    if (s.pendingOps.recirculate) {
      throw RecirculateFork(
        outputBytes,
        s.packetCtx.getEvents(),
        snapshotPreservedMetadata(s, s.pendingOps.recirculateFieldListId),
      )
    }

    val egressPort =
      (s.standardMetadata.fields["egress_port"] as? BitVal)?.bits?.value?.toInt() ?: 0
    return buildOutputTrace(s.packetCtx.getEvents(), egressPort, outputBytes)
  }

  /** Runs a list of control stages, emitting enter/exit events for each. */
  private fun runControlStages(s: PipelineState, stages: List<PipelineStage>) {
    for (stage in stages) {
      s.packetCtx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.ENTER))
      try {
        s.interpreter.runControl(stage.blockName, s.env)
      } catch (_: ExitException) {
        break
      } finally {
        s.packetCtx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.EXIT))
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
    decisions: V1ModelDecisions,
    parserEventCount: Int,
  ) {
    when (val mode = decisions.branchMode) {
      is BranchMode.I2EClone -> {
        s.standardMetadata.setBitField("instance_type", CLONE_I2E_INSTANCE_TYPE)
        s.standardMetadata.setBitField("egress_port", mode.clonePort)
      }
      is BranchMode.Replica -> {
        s.standardMetadata.setBitField("instance_type", REPLICATION_INSTANCE_TYPE)
        s.standardMetadata.setBitField("egress_port", mode.port.toLong())
        s.standardMetadata.setBitField("egress_rid", mode.rid.toLong())
      }
      is BranchMode.Normal,
      is BranchMode.E2EClone -> {
        // Both Normal and E2EClone run ingress normally and check for I2E clone/resubmit/multicast.
        val suppressI2E = (mode as? BranchMode.Normal)?.suppressI2EClone == true
        val pendingClone = s.pendingOps.cloneSessionId
        if (pendingClone != null && !suppressI2E) {
          resolveCloneSession(ctx, s, pendingClone)?.let { clonePort ->
            throw CloneFork(
              pendingClone,
              clonePort,
              parserEventCount,
              s.packetCtx.getEvents(),
              snapshotPreservedMetadata(s, s.pendingOps.cloneFieldListId),
            )
          }
        }
        if (s.pendingOps.resubmit) {
          throw ResubmitFork(
            s.packetCtx.getEvents(),
            snapshotPreservedMetadata(s, s.pendingOps.resubmitFieldListId),
          )
        }
        val mcastGrp =
          (s.standardMetadata.fields["mcast_grp"] as? BitVal)?.bits?.value?.toInt() ?: 0
        if (mcastGrp != 0) {
          // BMv2 silently ignores unknown multicast groups (no fork, no output).
          val group = ctx.tableStore.getMulticastGroup(mcastGrp)
          if (group != null) {
            val replicas =
              group.replicasList.map { r -> BranchMode.Replica(r.instance, r.egressPort) }
            throw MulticastFork(replicas, s.packetCtx.getEvents())
          }
        }
        // Normal unicast: copy egress_spec → egress_port for uniform read below.
        s.standardMetadata.fields["egress_port"] =
          s.standardMetadata.fields["egress_spec"] ?: BitVal(0, s.portBits)
      }
    }
  }

  /**
   * Handles the post-egress boundary.
   *
   * BMv2 priority order after egress: E2E clone > recirculate > output/drop.
   */
  private fun postEgressBoundary(
    ctx: PipelineContext,
    s: PipelineState,
    decisions: V1ModelDecisions,
  ) {
    when (val mode = decisions.branchMode) {
      is BranchMode.E2EClone -> {
        s.standardMetadata.setBitField("instance_type", CLONE_E2E_INSTANCE_TYPE)
        s.standardMetadata.setBitField("egress_port", mode.clonePort)
        // Suppress chained E2E clones: BMv2 does not re-clone from a clone's egress run.
        s.pendingOps.egressCloneSessionId = null
      }
      else -> {
        val suppressE2E = (mode as? BranchMode.Normal)?.suppressE2EClone == true
        val pendingE2EClone = s.pendingOps.egressCloneSessionId
        if (pendingE2EClone != null && !suppressE2E) {
          resolveCloneSession(ctx, s, pendingE2EClone)?.let { clonePort ->
            throw EgressCloneFork(
              pendingE2EClone,
              clonePort,
              s.packetCtx.getEvents(),
              snapshotPreservedMetadata(s, s.pendingOps.egressCloneFieldListId),
            )
          }
        }
      }
    }
  }

  /**
   * Traffic manager drop check: egress_port == drop port (set by boundary from egress_spec or clone
   * session).
   */
  private fun egressPortIsDropPort(s: PipelineState): Boolean =
    (s.standardMetadata.fields["egress_port"] as? BitVal)?.bits?.value?.toLong() == s.dropPort

  /**
   * Resets egress_spec to match egress_port before each egress run.
   *
   * In BMv2, egress_spec carries the ingress-determined output port for unicast, but for multicast
   * replicas, I2E clones, and E2E clones, egress_port is set independently by the PRE or clone
   * session. Without this reset, a stale mark_to_drop() from ingress (or a prior egress run) would
   * cause the post-egress drop check to incorrectly drop packets.
   */
  private fun resetEgressSpec(s: PipelineState) {
    s.standardMetadata.fields["egress_spec"] =
      s.standardMetadata.fields["egress_port"] ?: BitVal(0, s.portBits)
  }

  /**
   * Snapshots user metadata fields matching [fieldListId] for preservation across a fork.
   *
   * Returns only the fields annotated with `@field_list(fieldListId)` in the IR, or null if there's
   * nothing to preserve.
   */
  private fun snapshotPreservedMetadata(s: PipelineState, fieldListId: Int?): Map<String, Value>? {
    if (fieldListId == null || s.metaStructDecl == null) return null
    val allFields = (s.env.lookup(s.metaParamName) as? StructVal)?.fields ?: return null
    val preserved =
      s.metaStructDecl.fieldsList
        .filter { fieldListId in it.fieldListIdsList }
        .mapNotNull { decl -> allFields[decl.name]?.let { decl.name to it } }
        .toMap()
    return preserved.ifEmpty { null }
  }

  /** Post-egress drop check: mark_to_drop() in egress sets egress_spec to drop port. */
  private fun egressSpecIsDropPort(s: PipelineState): Boolean =
    (s.standardMetadata.fields["egress_spec"] as? BitVal)?.bits?.value?.toLong() == s.dropPort

  /**
   * Resolves a pending clone session: emits a [CloneSessionLookupEvent] and returns the clone port,
   * or emits a miss event and returns null if the session doesn't exist.
   */
  private fun resolveCloneSession(ctx: PipelineContext, s: PipelineState, sessionId: Int): Long? {
    val session = ctx.tableStore.getCloneSession(sessionId)
    if (session != null) {
      val egressPort = session.replicasList.first().egressPort
      s.packetCtx.addTraceEvent(cloneSessionLookupEvent(sessionId, egressPort))
      return egressPort.toLong()
    }
    s.packetCtx.addTraceEvent(cloneSessionMissEvent(sessionId))
    return null
  }

  private fun cloneSessionLookupEvent(sessionId: Int, egressPort: Int): TraceEvent =
    TraceEvent.newBuilder()
      .setCloneSessionLookup(
        CloneSessionLookupEvent.newBuilder()
          .setSessionId(sessionId)
          .setSessionFound(true)
          .setEgressPort(egressPort)
      )
      .build()

  private fun cloneSessionMissEvent(sessionId: Int): TraceEvent =
    TraceEvent.newBuilder()
      .setCloneSessionLookup(
        CloneSessionLookupEvent.newBuilder().setSessionId(sessionId).setSessionFound(false)
      )
      .build()

  // Trace helpers (buildDropTrace, buildOutputTrace, packetIngressEvent, stageEvent)
  // are shared with PSAArchitecture — see TraceHelpers.kt.

  // -------------------------------------------------------------------------
  // v1model extern handler
  // -------------------------------------------------------------------------

  /**
   * Creates the v1model [ExternHandler] for a single pipeline execution.
   *
   * All v1model extern functions (mark_to_drop, clone, resubmit, recirculate, verify_checksum,
   * update_checksum, hash) and extern object methods (register.read/write, counter.count,
   * meter.execute_meter) are implemented here. The handler captures [pendingOps] for setting
   * pending fork flags, [standardMetadata] for checksum error reporting, and [tableStore] for
   * register storage.
   */
  private fun createExternHandler(
    standardMetadata: StructVal,
    pendingOps: V1ModelPendingOps,
    tableStore: TableStore,
  ): ExternHandler = ExternHandler { call, eval ->
    when (call) {
      is ExternCall.FreeFunction -> v1modelExternCall(call.name, eval, standardMetadata, pendingOps)
      is ExternCall.Method -> v1modelExternMethodCall(call, eval, tableStore)
    }
  }

  /**
   * Dispatches a v1model extern function call.
   *
   * References:
   * - v1model.p4: https://github.com/p4lang/p4c/blob/main/p4include/v1model.p4
   * - BMv2 simple_switch:
   *   https://github.com/p4lang/behavioral-model/blob/main/docs/simple_switch.md
   */
  @Suppress("CyclomaticComplexMethod", "ThrowsCount")
  private fun v1modelExternCall(
    name: String,
    eval: ExternEvaluator,
    standardMetadata: StructVal,
    pendingOps: V1ModelPendingOps,
  ): Value =
    when (name) {
      // mark_to_drop(standard_metadata): sets egress_spec to all-ones (the drop port).
      "mark_to_drop" -> {
        eval.addTraceEvent(
          eval
            .traceEventBuilder()
            .setMarkToDrop(MarkToDropEvent.newBuilder().setReason(DropReason.MARK_TO_DROP))
            .build()
        )
        val smeta = eval.evalArg(0) as StructVal
        val portBits = smeta.bitWidth("egress_spec")
        smeta.fields["egress_spec"] = BitVal((1L shl portBits) - 1, portBits)
        UnitVal
      }
      // clone(type, session) / clone3(type, session, data): I2E/E2E clone.
      // Records the clone intent; the architecture checks the appropriate pending field
      // at the boundary and forks there. Multiple calls use last-writer-wins,
      // matching BMv2 simple_switch semantics.
      "clone",
      "clone3",
      "clone_preserving_field_list" -> {
        val cloneType = (eval.evalArg(0) as EnumVal).member
        val sessionId = (eval.evalArg(1) as BitVal).bits.value.toInt()
        val fieldListId =
          if (name == "clone_preserving_field_list") {
            evalIntArg(eval, 2)
          } else {
            null
          }
        eval.addTraceEvent(
          eval.traceEventBuilder().setClone(CloneEvent.newBuilder().setSessionId(sessionId)).build()
        )
        when (cloneType) {
          "I2E" -> {
            pendingOps.cloneSessionId = sessionId
            pendingOps.cloneFieldListId = fieldListId
          }
          "E2E" -> {
            pendingOps.egressCloneSessionId = sessionId
            pendingOps.egressCloneFieldListId = fieldListId
          }
        }
        UnitVal
      }
      // resubmit[_preserving_field_list](data): re-inject into ingress.
      "resubmit",
      "resubmit_preserving_field_list" -> {
        pendingOps.resubmit = true
        if (name == "resubmit_preserving_field_list") {
          pendingOps.resubmitFieldListId = evalIntArg(eval, 0)
        }
        UnitVal
      }
      // recirculate[_preserving_field_list](data): feed deparsed packet back into ingress.
      "recirculate",
      "recirculate_preserving_field_list" -> {
        pendingOps.recirculate = true
        if (name == "recirculate_preserving_field_list") {
          pendingOps.recirculateFieldListId = evalIntArg(eval, 0)
        }
        UnitVal
      }
      // verify_checksum[_with_payload](condition, data, checksum, algo): v1model §14.
      // Computes hash over data fields (and optionally the unparsed packet body) and
      // compares with checksum; sets standard_metadata.checksum_error = 1 on mismatch.
      "verify_checksum",
      "verify_checksum_with_payload" -> {
        val condition = (eval.evalArg(0) as BoolVal).value
        if (condition) {
          val data = eval.evalArg(1) as StructVal
          val expected = eval.evalArg(2) as BitVal
          val algo = (eval.evalArg(3) as EnumVal).member
          val payload =
            if (name.endsWith("_with_payload")) eval.peekRemainingInput() else ByteArray(0)
          val computed = computeHashWithPayload(algo, data, payload)
          if (computed != expected.bits.value) {
            standardMetadata.setBitField("checksum_error", 1L)
          }
        }
        UnitVal
      }
      // update_checksum[_with_payload](condition, data, checksum, algo): v1model §14.
      // Computes hash over data fields (and optionally the unparsed packet body) and
      // writes result into checksum (out param).
      "update_checksum",
      "update_checksum_with_payload" -> {
        val condition = (eval.evalArg(0) as BoolVal).value
        if (condition) {
          val data = eval.evalArg(1) as StructVal
          val algo = (eval.evalArg(3) as EnumVal).member
          val payload =
            if (name.endsWith("_with_payload")) eval.peekRemainingInput() else ByteArray(0)
          val computed = computeHashWithPayload(algo, data, payload)
          val checksumWidth = eval.argType(2).bit.width
          eval.writeOutArg(2, BitVal(BitVector(computed, checksumWidth)))
        }
        UnitVal
      }
      // hash(out result, in algo, in base, in data, in max): v1model hash extern.
      "hash" -> {
        val algo = (eval.evalArg(1) as EnumVal).member
        val base = (eval.evalArg(2) as BitVal).bits.value
        val data = eval.evalArg(3) as StructVal
        val max = (eval.evalArg(4) as BitVal).bits.value
        val hashVal = computeHash(algo, data)
        val result = if (max > BigInteger.ZERO) base + hashVal.mod(max) else base
        val resultWidth = eval.argType(0).bit.width
        eval.writeOutArg(0, BitVal(BitVector(result, resultWidth)))
        UnitVal
      }
      // log_msg(msg) / log_msg(msg, data): debug output with {} placeholders.
      // Defined in v1model.p4 — emits a trace event with the interpolated message.
      "log_msg" -> {
        val format = (eval.evalArg(0) as StringVal).value
        val message = if (eval.argCount() > 1) formatLogMessage(format, eval.evalArg(1)) else format
        eval.addTraceEvent(
          eval
            .traceEventBuilder()
            .setLogMessage(LogMessageEvent.newBuilder().setMessage(message))
            .build()
        )
        UnitVal
      }
      else -> error("unhandled v1model extern: $name")
    }

  // -------------------------------------------------------------------------
  // v1model extern method handler
  // -------------------------------------------------------------------------

  /**
   * Dispatches method calls on v1model extern object instances (registers, counters, meters).
   *
   * These are distinct from free-function externs: they're called as `instance.method(args)` rather
   * than `function(args)`. The extern type and instance name come from [call]; storage is resolved
   * via [tableStore].
   */
  @Suppress("ThrowsCount")
  private fun v1modelExternMethodCall(
    call: ExternCall.Method,
    eval: ExternEvaluator,
    tableStore: TableStore,
  ): Value =
    when (call.externType) {
      "register" ->
        when (call.method) {
          "read" -> {
            val index = (eval.evalArg(1) as BitVal).bits.value.toInt()
            val stored = tableStore.registerRead(call.instanceName, index)
            eval.writeOutArg(0, stored ?: eval.defaultValue(eval.argType(0)))
            UnitVal
          }
          "write" -> {
            val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
            tableStore.registerWrite(call.instanceName, index, eval.evalArg(1))
            UnitVal
          }
          else -> error("unhandled register method: ${call.method} on ${call.instanceName}")
        }
      "counter",
      "direct_counter" ->
        when (call.method) {
          // fire-and-forget side-effect, invisible to data plane.
          "count" -> UnitVal
          else -> error("unhandled counter method: ${call.method} on ${call.instanceName}")
        }
      "meter" ->
        when (call.method) {
          // execute_meter(in index, out color): always GREEN (no real rates in simulator).
          "execute_meter" -> {
            eval.writeOutArg(1, eval.defaultValue(eval.argType(1)))
            UnitVal
          }
          else -> error("unhandled meter method: ${call.method} on ${call.instanceName}")
        }
      "direct_meter" ->
        when (call.method) {
          // read(out color): always GREEN (no real rates in simulator).
          "read" -> {
            eval.writeOutArg(0, eval.defaultValue(eval.argType(0)))
            UnitVal
          }
          else -> error("unhandled direct_meter method: ${call.method} on ${call.instanceName}")
        }
      else ->
        error(
          "unhandled v1model extern method: ${call.externType}.${call.method}" +
            " on ${call.instanceName}"
        )
    }

  companion object {
    // Default v1model port width and drop port. These are the values for standard v1model.p4
    // (bit<9> PortId_t). At runtime, the actual values are derived from the IR — see
    // PipelineState.portBits and PipelineState.dropPort.
    const val DEFAULT_PORT_BITS = 9
    const val DEFAULT_DROP_PORT = (1 shl DEFAULT_PORT_BITS) - 1 // 511

    // Number of user-visible params in the v1model parser after removing packet_in/packet_out:
    // (hdr, meta, standard_metadata).
    private const val V1MODEL_USER_PARAM_COUNT = 3

    // v1model: first 2 control stages are ingress-side (verify checksum, ingress).
    private const val INGRESS_CONTROL_COUNT = 2

    private const val MAX_PIPELINE_DEPTH = 10

    // Cap on total pipeline executions per packet to prevent exponential blowup
    // from nested clone/recirculate chains (e.g. clone → recirculate → clone → ...).
    private const val MAX_PIPELINE_EXECUTIONS = 1000

    // v1model instance_type values (BMv2 PktInstanceType convention).
    private const val CLONE_I2E_INSTANCE_TYPE = 1L
    private const val CLONE_E2E_INSTANCE_TYPE = 2L
    private const val RECIRC_INSTANCE_TYPE = 4L
    private const val REPLICATION_INSTANCE_TYPE = 5L
    private const val RESUBMIT_INSTANCE_TYPE = 6L
  }
}

// =============================================================================
// v1model-specific fork types and pipeline execution decisions
// =============================================================================

/**
 * Which mode this v1model pipeline execution is in.
 *
 * BMv2 priority ordering guarantees at most one mode per boundary crossing: ingress (I2E clone >
 * resubmit > multicast > unicast), egress (E2E clone > recirculate > output).
 */
internal sealed class BranchMode {
  /** Normal pipeline — may fork at any choice point. */
  data class Normal(val suppressI2EClone: Boolean = false, val suppressE2EClone: Boolean = false) :
    BranchMode()

  /** I2E clone branch: skip ingress, set CLONE_I2E at boundary. */
  data class I2EClone(val sessionId: Int, val clonePort: Long) : BranchMode()

  /** E2E clone branch: re-run egress with CLONE_E2E after original egress completes. */
  data class E2EClone(val sessionId: Int, val clonePort: Long) : BranchMode()

  /** Multicast replica: set REPLICATION metadata at boundary. */
  data class Replica(val rid: Int, val port: Int) : BranchMode()
}

/**
 * v1model-specific policies for re-execution of a pipeline branch in the trace tree.
 *
 * @property selectorMembers Forced member selections per table (action selector branches).
 * @property branchMode The execution mode for this branch (normal, clone, or replica).
 * @property instanceTypeOverride If non-null, override instance_type at pipeline init.
 * @property pipelineDepth Tracks resubmit/recirculate nesting to prevent infinite loops.
 * @property preservedMetadata Pre-filtered metadata fields to restore from
 *   clone/resubmit/recirculate.
 */
internal data class V1ModelDecisions(
  val selectorMembers: Map<String, Int> = emptyMap(),
  val branchMode: BranchMode = BranchMode.Normal(),
  val instanceTypeOverride: Long? = null,
  val pipelineDepth: Int = 0,
  val preservedMetadata: Map<String, Value>? = null,
)

/**
 * Mutable v1model-specific pending operations set by extern calls and read at pipeline boundaries.
 *
 * A fresh instance is created per pipeline execution. This keeps v1model state out of the shared
 * [PacketContext], which only carries architecture-agnostic concerns (packet I/O, trace events).
 */
internal class V1ModelPendingOps {
  /** Session ID from the last I2E clone() call, or null if no clone was requested. */
  var cloneSessionId: Int? = null

  /** Field list ID for I2E clone metadata preservation. */
  var cloneFieldListId: Int? = null

  /** Session ID from the last E2E clone() call, checked after egress controls. */
  var egressCloneSessionId: Int? = null

  /** Field list ID for E2E clone metadata preservation. */
  var egressCloneFieldListId: Int? = null

  /** True if resubmit() was called during ingress. */
  var resubmit: Boolean = false

  /** Field list ID for resubmit metadata preservation. */
  var resubmitFieldListId: Int? = null

  /** True if recirculate() was called during egress. */
  var recirculate: Boolean = false

  /** Field list ID for recirculate metadata preservation. */
  var recirculateFieldListId: Int? = null
}

// -------------------------------------------------------------------------
// v1model-specific fork exceptions
// -------------------------------------------------------------------------

/**
 * Fork at the ingress→egress boundary when an I2E clone was requested — "original" and "clone".
 *
 * [parserEventCount] tracks how many trace events came from the parser (before ingress). The clone
 * branch skips ingress, so its prefix length is shorter.
 */
internal class CloneFork(
  val sessionId: Int,
  val clonePort: Long,
  val parserEventCount: Int,
  eventsBeforeFork: List<TraceEvent>,
  val preservedMetadata: Map<String, Value>? = null,
) : ForkException(eventsBeforeFork)

/** Fork after egress controls when an E2E clone was requested — "original" and "clone". */
internal class EgressCloneFork(
  val sessionId: Int,
  val clonePort: Long,
  eventsBeforeFork: List<TraceEvent>,
  val preservedMetadata: Map<String, Value>? = null,
) : ForkException(eventsBeforeFork)

/** Fork at the ingress→egress boundary when mcast_grp is set — one branch per replica. */
internal class MulticastFork(
  val replicas: List<BranchMode.Replica>,
  eventsBeforeFork: List<TraceEvent>,
) : ForkException(eventsBeforeFork)

/** Fork at the ingress→egress boundary when resubmit was requested — single branch re-ingress. */
internal class ResubmitFork(
  eventsBeforeFork: List<TraceEvent>,
  val preservedMetadata: Map<String, Value>? = null,
) : ForkException(eventsBeforeFork)

/** Fork after deparser when recirculate was requested — single branch with deparsed bytes. */
internal class RecirculateFork(
  val deparsedBytes: ByteArray,
  eventsBeforeFork: List<TraceEvent>,
  val preservedMetadata: Map<String, Value>? = null,
) : ForkException(eventsBeforeFork)

// =============================================================================
// log_msg formatting helpers
// =============================================================================

/** Substitutes `{}` placeholders in a log_msg format string with struct field values. */
private fun formatLogMessage(format: String, data: Value): String {
  val values =
    when (data) {
      is StructVal -> data.fields.values.toList()
      else -> listOf(data)
    }
  val sb = StringBuilder()
  var valueIdx = 0
  var i = 0
  while (i < format.length) {
    if (i + 1 < format.length && format[i] == '{' && format[i + 1] == '}') {
      sb.append(if (valueIdx < values.size) formatValue(values[valueIdx++]) else "{}")
      i += 2
    } else {
      sb.append(format[i])
      i++
    }
  }
  return sb.toString()
}

/** Formats a runtime value for log_msg output. */
private fun formatValue(value: Value): String =
  when (value) {
    is BitVal -> value.bits.value.toString()
    is IntVal -> value.bits.value.toString()
    is BoolVal -> value.value.toString()
    is InfIntVal -> value.value.toString()
    is EnumVal -> value.member
    is ErrorVal -> value.member
    is StringVal -> value.value
    else -> value.toString()
  }
