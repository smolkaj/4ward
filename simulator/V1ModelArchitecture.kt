package fourward.simulator

import fourward.ir.BehavioralConfig
import fourward.ir.PipelineStage
import fourward.ir.StageKind
import fourward.ir.StructDecl
import fourward.sim.CloneEvent
import fourward.sim.CloneSessionLookupEvent
import fourward.sim.DropReason
import fourward.sim.Fork
import fourward.sim.ForkBranch
import fourward.sim.ForkReason
import fourward.sim.LogMessageEvent
import fourward.sim.MarkToDropEvent
import fourward.sim.PipelineStageEvent
import fourward.sim.TraceEvent
import fourward.sim.TraceTree
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

/**
 * Benchmark-only knob for measuring the scaling impact of intra-packet parallelism. Not a public
 * API — no user documentation, no CLI flag. Normal users should leave this at the default (`true`).
 * See `designs/parallel_packet_scaling.md`.
 */
private val INTRA_PACKET_PARALLELISM_ENABLED =
  System.getProperty("fourward.simulator.intraPacketParallelism", "true").toBoolean()

class V1ModelArchitecture(
  private val config: BehavioralConfig,
  /**
   * Override for the drop port. When null (default), derived from `standard_metadata` port width:
   * `2^N - 1` (511 for 9-bit ports). Applied in [PipelineState] and in the `mark_to_drop` extern.
   */
  private val dropPortOverride: Int? = null,
) : Architecture {

  // Pipeline-invariant state derived from [config], built once per loaded pipeline. Immutable
  // after publication, so safe to read concurrently from any number of [processPacket] threads
  // without synchronization.
  private val interpreter: Interpreter = Interpreter(config)

  /** Invariant inputs to the pipeline, shared across fork re-executions. */
  private data class PipelineContext(
    val ingressPort: UInt,
    val payload: ByteArray,
    val config: BehavioralConfig,
    val tableStore: TableStore,
    val interpreter: Interpreter,
  )

  /** Per-execution state created fresh for each pipeline run. */
  @Suppress("LongParameterList")
  private class PipelineState(
    val packetCtx: PacketContext,
    val pendingOps: V1ModelPendingOps,
    val interpreter: Interpreter.Execution,
    val env: Environment,
    val standardMetadata: StructVal,
    val metaParamName: String,
    val metaStructDecl: StructDecl?,
    config: BehavioralConfig,
    val dropPort: Long,
  ) {
    private val stages = config.architecture.stagesList
    val parserStage: PipelineStage? = stages.find { it.kind == StageKind.PARSER }
    val deparserStage: PipelineStage? = stages.find { it.kind == StageKind.DEPARSER }

    // v1model: first 2 controls are ingress-side (verify checksum, ingress),
    // last 2 are egress-side (egress, compute checksum).
    private val controlStages = stages.filter { it.kind == StageKind.CONTROL }
    val ingressControls: List<PipelineStage> = controlStages.take(INGRESS_CONTROL_COUNT)
    val egressControls: List<PipelineStage> = controlStages.drop(INGRESS_CONTROL_COUNT)

    // Derived from the IR's standard_metadata struct definition, not hardcoded.
    val portBits: Int = standardMetadata.bitWidth("ingress_port")

    /** Post-parser env deep copy, for I2E clone branches (BMv2: clone gets pre-ingress state). */
    var postParserEnv: Environment? = null
  }

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    tableStore: TableStore,
  ): PipelineResult {
    val ctx = PipelineContext(ingressPort, payload, config, tableStore, interpreter)
    return buildTraceTree(ctx, V1ModelDecisions())
  }

  /**
   * Builds a trace tree by executing the pipeline and handling forks via fork-on-write. Each fork
   * type has a dedicated handler that continues branches from the fork point. Only
   * resubmit/recirculate call back into this method (they restart the pipeline).
   */
  private fun buildTraceTree(ctx: PipelineContext, decisions: V1ModelDecisions): PipelineResult {
    try {
      return PipelineResult(runPipeline(ctx, decisions))
    } catch (fork: V1ModelSelectorFork) {
      return buildSelectorForkTree(ctx, decisions, fork)
    } catch (fork: MulticastFork) {
      return buildMulticastForkTree(ctx, fork)
    } catch (fork: CloneFork) {
      return buildCloneForkTree(ctx, fork)
    } catch (fork: EgressCloneFork) {
      return buildEgressCloneForkTree(ctx, fork)
    } catch (fork: ResubmitFork) {
      return buildResubmitForkTree(ctx, fork)
    } catch (fork: RecirculateFork) {
      return buildRecirculateForkTree(ctx, fork)
    }
  }

  /**
   * Handles an action selector fork via fork-on-write: each branch continues from the fork point
   * instead of replaying the entire pipeline. If a branch hits a nested fork (clone, multicast), it
   * falls back to the replay approach for that branch.
   */
  private fun buildSelectorForkTree(
    ctx: PipelineContext,
    decisions: V1ModelDecisions,
    selectorFork: V1ModelSelectorFork,
  ): PipelineResult {
    val fork = selectorFork.fork
    val levelEvents = fork.eventsBeforeFork

    val buildBranch = { member: TableStore.MemberAction ->
      val branchDecisions =
        decisions.copy(
          selectorMembers = decisions.selectorMembers + (fork.tableName to member.memberId)
        )
      try {
        val trace = continueFromForkPoint(ctx, branchDecisions, selectorFork, member)
        PipelineResult(trace)
      } catch (nestedFork: ForkException) {
        PipelineResult(handleNestedFork(ctx, nestedFork))
      }
    }

    val results =
      if (INTRA_PACKET_PARALLELISM_ENABLED) {
        fork.members.parallelStream().map(buildBranch).toList()
      } else {
        fork.members.map(buildBranch)
      }

    val branches =
      fork.members.zip(results).map { (member, result) ->
        ForkBranch.newBuilder()
          .setLabel("member_${member.memberId}")
          .setSubtree(result.trace)
          .build()
      }
    val tree =
      TraceTree.newBuilder()
        .addAllEvents(levelEvents)
        .setForkOutcome(
          Fork.newBuilder().setReason(ForkReason.ACTION_SELECTOR).addAllBranches(branches)
        )
        .build()
    return PipelineResult(tree)
  }

  /**
   * Continues pipeline execution from an action selector fork point. Restores the interpreter state
   * captured at the fork, executes the selected member's action and continuation, then runs the
   * remaining pipeline (boundary, egress, deparser).
   */
  @Suppress("ThrowsCount")
  private fun continueFromForkPoint(
    ctx: PipelineContext,
    decisions: V1ModelDecisions,
    selectorFork: V1ModelSelectorFork,
    member: TableStore.MemberAction,
  ): TraceTree {
    val fork = selectorFork.fork

    // Fresh packet context at the parser's buffer position — trace events start empty.
    val packetCtx = PacketContext(ctx.payload, fork.bytesConsumed)
    val env = fork.forkPointEnv.deepCopy()
    val pendingOps = selectorFork.pendingOps.copy()

    val standardMetadata = resolveStandardMetadata(ctx, env)

    val s = finishPipelineState(ctx, decisions, packetCtx, env, standardMetadata, pendingOps)
    s.postParserEnv = selectorFork.postParserEnv

    try {
      // Resume: execute the selected action + continuation stmts.
      // An exit statement terminates the current control — catch it, pop scope, skip
      // remaining stages (same as runControlStages's ExitException handler).
      var exitCalled = false
      try {
        s.interpreter.resumeFromFork(fork, member, s.env)
      } catch (_: ExitException) {
        exitCalled = true
      } catch (nestedSelector: ActionSelectorFork) {
        // Nested selector in the continuation — wrap with V1Model pipeline context.
        throw V1ModelSelectorFork(
          nestedSelector,
          s.pendingOps.copy(),
          selectorFork.stage,
          selectorFork.remainingStages,
          selectorFork.duringIngress,
          s.postParserEnv,
        )
      } finally {
        s.env.popScope()
        s.packetCtx.addTraceEvent(stageEvent(selectorFork.stage, PipelineStageEvent.Direction.EXIT))
      }

      // Remaining stages in the same control group (skipped on exit).
      if (!exitCalled) {
        runControlStages(s, selectorFork.remainingStages)
      }

      if (selectorFork.duringIngress) {
        // Fork was in ingress — run boundary + egress + deparser.
        ingressEgressBoundary(ctx, s)
        if (egressPortIsDropPort(s)) {
          return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
        }
        return runEgressAndDeparser(ctx, s)
      }
      // Fork was in egress — remaining egress stages already ran, just finish.
      return runPostEgressAndDeparser(ctx, s)
    } catch (_: AssertionFailureException) {
      return buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
    }
  }

  /**
   * Handles a multicast fork via fork-on-write: each replica gets a deep copy of the fork-point
   * state with its metadata (instance_type, egress_port, egress_rid) set, then runs egress +
   * deparser independently.
   */
  private fun buildMulticastForkTree(
    ctx: PipelineContext,
    fork: MulticastFork,
    prefixLength: Int = 0,
  ): PipelineResult {
    val buildBranch = { replica: Replica ->
      val packetCtx = PacketContext(ctx.payload, fork.bytesConsumed)
      val env = fork.forkPointEnv.deepCopy()
      val pendingOps = fork.forkPointPendingOps.copy()

      val standardMetadata = resolveStandardMetadata(ctx, env)
      standardMetadata.setBitField("instance_type", REPLICATION_INSTANCE_TYPE)
      standardMetadata.setBitField("egress_port", replica.port.toLong())
      standardMetadata.setBitField("egress_rid", replica.rid.toLong())

      val s =
        finishPipelineState(ctx, V1ModelDecisions(), packetCtx, env, standardMetadata, pendingOps)
      try {
        runEgressAndDeparser(ctx, s)
      } catch (nestedFork: ForkException) {
        handleNestedFork(ctx, nestedFork)
      } catch (_: AssertionFailureException) {
        buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
      }
    }

    val results =
      if (INTRA_PACKET_PARALLELISM_ENABLED) {
        fork.replicas.parallelStream().map(buildBranch).toList()
      } else {
        fork.replicas.map(buildBranch)
      }

    val branches =
      fork.replicas.zip(results).map { (replica, trace) ->
        ForkBranch.newBuilder()
          .setLabel("replica_${replica.rid}_port_${replica.port}")
          .setSubtree(trace)
          .build()
      }
    val levelEvents = fork.eventsBeforeFork.drop(prefixLength)
    return PipelineResult(buildForkTree(levelEvents, ForkReason.MULTICAST, branches))
  }

  /** Resolves standard_metadata from the environment using the config's parser parameter names. */
  private fun resolveStandardMetadata(ctx: PipelineContext, env: Environment): StructVal {
    val parserUserParams =
      ctx.config.parsersList.first().paramsList.filter {
        it.type.hasNamed() && it.type.named !in IO_TYPES
      }
    return env.lookup(parserUserParams[2].name) as? StructVal
      ?: error("standard_metadata not found in environment")
  }

  /**
   * Handles an I2E clone fork via fork-on-write.
   *
   * "Original" branch: uses the post-ingress fork-point state, suppresses the clone, runs the
   * remaining boundary checks (resubmit, multicast, unicast) then egress + deparser.
   *
   * "Clone" branch: uses the post-parser state (BMv2: clone gets parsed-but-not-ingress-modified
   * headers), applies preserved metadata, sets clone metadata, runs egress + deparser.
   */
  private fun buildCloneForkTree(ctx: PipelineContext, fork: CloneFork): PipelineResult {
    val levelEvents = fork.eventsBeforeFork

    val buildOriginal = {
      val packetCtx = PacketContext(ctx.payload, fork.bytesConsumed)
      val env = fork.forkPointEnv.deepCopy()
      val pendingOps = fork.forkPointPendingOps.copy()
      pendingOps.cloneSessionId = null
      val standardMetadata = resolveStandardMetadata(ctx, env)
      val s =
        finishPipelineState(ctx, V1ModelDecisions(), packetCtx, env, standardMetadata, pendingOps)

      try {
        // Resume boundary: check resubmit, multicast, unicast (clone already handled).
        // pendingOps.cloneSessionId already cleared — boundary won't re-clone.
        ingressEgressBoundary(ctx, s)
        if (egressPortIsDropPort(s)) {
          buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
        } else {
          runEgressAndDeparser(ctx, s)
        }
      } catch (nestedFork: ForkException) {
        handleNestedFork(ctx, nestedFork)
      } catch (_: AssertionFailureException) {
        buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
      }
    }

    val buildClone = {
      // BMv2: I2E clone gets the post-parser, pre-ingress state.
      val packetCtx = PacketContext(ctx.payload, fork.bytesConsumed)
      val env = fork.postParserEnv.deepCopy()
      val standardMetadata = resolveStandardMetadata(ctx, env)

      standardMetadata.setBitField("instance_type", CLONE_I2E_INSTANCE_TYPE)
      standardMetadata.setBitField("egress_port", fork.clonePort)

      // Apply preserved metadata (fields from the post-ingress state).
      if (fork.preservedMetadata != null) {
        val parserUserParams =
          ctx.config.parsersList.first().paramsList.filter {
            it.type.hasNamed() && it.type.named !in IO_TYPES
          }
        val metaVal = env.lookup(parserUserParams[1].name) as? StructVal
        if (metaVal != null) {
          for ((name, value) in fork.preservedMetadata) metaVal.fields[name] = value
        }
      }

      val s = finishPipelineState(ctx, V1ModelDecisions(), packetCtx, env, standardMetadata)
      try {
        runEgressAndDeparser(ctx, s)
      } catch (nestedFork: ForkException) {
        handleNestedFork(ctx, nestedFork)
      } catch (_: AssertionFailureException) {
        buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
      }
    }

    val branches =
      listOf(
        ForkBranch.newBuilder().setLabel("original").setSubtree(buildOriginal()).build(),
        ForkBranch.newBuilder().setLabel("clone").setSubtree(buildClone()).build(),
      )
    return PipelineResult(buildForkTree(levelEvents, ForkReason.CLONE, branches))
  }

  /**
   * Handles an E2E clone fork via fork-on-write.
   *
   * "Original" branch: suppresses E2E clone, runs deparser. "Clone" branch: sets CLONE_E2E
   * metadata, applies preserved metadata, runs second egress + deparser.
   */
  private fun buildEgressCloneForkTree(
    ctx: PipelineContext,
    fork: EgressCloneFork,
  ): PipelineResult {
    val levelEvents = fork.eventsBeforeFork

    val buildOriginal = {
      val packetCtx = PacketContext(ctx.payload, fork.bytesConsumed)
      val env = fork.forkPointEnv.deepCopy()
      val pendingOps = fork.forkPointPendingOps.copy()
      pendingOps.egressCloneSessionId = null
      val standardMetadata = resolveStandardMetadata(ctx, env)
      val s =
        finishPipelineState(ctx, V1ModelDecisions(), packetCtx, env, standardMetadata, pendingOps)

      try {
        if (egressSpecIsDropPort(s)) {
          buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
        } else {
          runDeparser(s)
        }
      } catch (_: AssertionFailureException) {
        buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
      }
    }

    val buildClone = {
      val packetCtx = PacketContext(ctx.payload, fork.bytesConsumed)
      val env = fork.forkPointEnv.deepCopy()
      val pendingOps = fork.forkPointPendingOps.copy()
      pendingOps.egressCloneSessionId = null
      val standardMetadata = resolveStandardMetadata(ctx, env)

      standardMetadata.setBitField("instance_type", CLONE_E2E_INSTANCE_TYPE)
      standardMetadata.setBitField("egress_port", fork.clonePort)

      // Apply preserved metadata.
      if (fork.preservedMetadata != null) {
        val parserUserParams =
          ctx.config.parsersList.first().paramsList.filter {
            it.type.hasNamed() && it.type.named !in IO_TYPES
          }
        val metaVal = env.lookup(parserUserParams[1].name) as? StructVal
        if (metaVal != null) {
          for ((name, value) in fork.preservedMetadata) metaVal.fields[name] = value
        }
      }

      val s =
        finishPipelineState(ctx, V1ModelDecisions(), packetCtx, env, standardMetadata, pendingOps)
      try {
        // E2E clone runs egress again with clone metadata.
        resetEgressSpec(s)
        runControlStages(s, s.egressControls)
        if (egressSpecIsDropPort(s)) {
          buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
        } else {
          runDeparser(s)
        }
      } catch (nestedFork: ForkException) {
        handleNestedFork(ctx, nestedFork)
      } catch (_: AssertionFailureException) {
        buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
      }
    }

    val branches =
      listOf(
        ForkBranch.newBuilder().setLabel("original").setSubtree(buildOriginal()).build(),
        ForkBranch.newBuilder().setLabel("clone").setSubtree(buildClone()).build(),
      )
    return PipelineResult(buildForkTree(levelEvents, ForkReason.CLONE, branches))
  }

  /** Handles a resubmit fork: restarts the pipeline with preserved metadata. */
  private fun buildResubmitForkTree(ctx: PipelineContext, fork: ResubmitFork): PipelineResult {
    val levelEvents = fork.eventsBeforeFork
    val decisions =
      V1ModelDecisions(
        pipelineDepth = 1,
        instanceTypeOverride = RESUBMIT_INSTANCE_TYPE,
        preservedMetadata = fork.preservedMetadata,
      )
    val result = buildTraceTree(ctx, decisions)
    val branch = ForkBranch.newBuilder().setLabel("resubmit").setSubtree(result.trace).build()
    return PipelineResult(buildForkTree(levelEvents, ForkReason.RESUBMIT, listOf(branch)))
  }

  /** Handles a recirculate fork: restarts the pipeline with deparsed bytes. */
  private fun buildRecirculateForkTree(
    ctx: PipelineContext,
    fork: RecirculateFork,
  ): PipelineResult {
    val levelEvents = fork.eventsBeforeFork
    val decisions =
      V1ModelDecisions(
        pipelineDepth = 1,
        instanceTypeOverride = RECIRC_INSTANCE_TYPE,
        preservedMetadata = fork.preservedMetadata,
      )
    val recircCtx = ctx.copy(payload = fork.deparsedBytes)
    val result = buildTraceTree(recircCtx, decisions)
    val branch = ForkBranch.newBuilder().setLabel("recirculate").setSubtree(result.trace).build()
    return PipelineResult(buildForkTree(levelEvents, ForkReason.RECIRCULATE, listOf(branch)))
  }

  /**
   * Dispatches a nested fork exception to the appropriate fork-on-write handler. Called from within
   * branch builders when a branch's execution throws a fork.
   */
  private fun handleNestedFork(ctx: PipelineContext, fork: ForkException): TraceTree =
    when (fork) {
      is V1ModelSelectorFork -> buildSelectorForkTree(ctx, V1ModelDecisions(), fork).trace
      is MulticastFork -> buildMulticastForkTree(ctx, fork).trace
      is CloneFork -> buildCloneForkTree(ctx, fork).trace
      is EgressCloneFork -> buildEgressCloneForkTree(ctx, fork).trace
      is ResubmitFork -> buildResubmitForkTree(ctx, fork).trace
      is RecirculateFork -> buildRecirculateForkTree(ctx, fork).trace
      is ActionSelectorFork -> error("unwrapped ActionSelectorFork — should be V1ModelSelectorFork")
    }

  /**
   * Creates a fresh [PipelineState] for one pipeline execution.
   *
   * Resolves type names, initialises standard_metadata, and binds parameter names across all
   * stages. Stage topology (ingress/egress split) is derived by [PipelineState] itself.
   */
  private fun initPipelineState(ctx: PipelineContext, decisions: V1ModelDecisions): PipelineState {
    require(decisions.pipelineDepth <= MAX_PIPELINE_DEPTH) {
      "max pipeline depth exceeded ($MAX_PIPELINE_DEPTH) — " +
        "possible infinite resubmit/recirculate loop"
    }
    val config = ctx.config
    val typesByName = config.typesList.associateBy { it.name }

    // Derive the type names for hdr/meta/standard_metadata from the parser's
    // parameter list, filtering out the architecture-level packet I/O params.
    // v1model always declares: (packet_in, hdr, meta, standard_metadata) in that order.
    val parserUserParams =
      config.parsersList.first().paramsList.filter {
        it.type.hasNamed() && it.type.named !in IO_TYPES
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
    standardMetadata.fields["parser_error"] = ErrorVal.NO_ERROR
    if (decisions.instanceTypeOverride != null) {
      standardMetadata.setBitField("instance_type", decisions.instanceTypeOverride)
    }

    val metaValue = defaultValue(metaTypeName, typesByName)
    if (decisions.preservedMetadata != null && metaValue is StructVal) {
      for ((name, value) in decisions.preservedMetadata) {
        metaValue.fields[name] = value
      }
    }

    val env = Environment()
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

    return finishPipelineState(ctx, decisions, PacketContext(ctx.payload), env, standardMetadata)
  }

  /**
   * Shared tail of pipeline state construction — creates the [Interpreter], resolves drop port, and
   * assembles the [PipelineState]. Called by both the fresh-init and snapshot-restore paths.
   */
  private fun finishPipelineState(
    ctx: PipelineContext,
    decisions: V1ModelDecisions,
    packetCtx: PacketContext,
    env: Environment,
    standardMetadata: StructVal,
    existingPendingOps: V1ModelPendingOps? = null,
  ): PipelineState {
    val portBits = standardMetadata.bitWidth("ingress_port")
    val dropPort = dropPortOverride?.toLong() ?: ((1L shl portBits) - 1)

    val pendingOps = existingPendingOps ?: V1ModelPendingOps()
    val interpreter =
      ctx.interpreter.execution(
        ctx.tableStore,
        packetCtx,
        decisions.selectorMembers,
        createExternHandler(standardMetadata, pendingOps, ctx.tableStore, dropPort),
      )

    val config = ctx.config
    val typesByName = config.typesList.associateBy { it.name }
    val parserUserParams =
      config.parsersList.first().paramsList.filter {
        it.type.hasNamed() && it.type.named !in IO_TYPES
      }
    val metaParamName = parserUserParams[1].name
    val metaStructDecl = typesByName[parserUserParams[1].type.named]?.struct
    return PipelineState(
      packetCtx,
      pendingOps,
      interpreter,
      env,
      standardMetadata,
      metaParamName,
      metaStructDecl,
      config,
      dropPort,
    )
  }

  /** Executes the full v1model pipeline once, returning a flat trace tree with a leaf outcome. */
  @Suppress("CyclomaticComplexMethod", "ThrowsCount")
  private fun runPipeline(ctx: PipelineContext, decisions: V1ModelDecisions): TraceTree {
    require(decisions.pipelineDepth <= MAX_PIPELINE_DEPTH) {
      "max pipeline depth exceeded ($MAX_PIPELINE_DEPTH) — " +
        "possible infinite resubmit/recirculate loop"
    }

    // --- Init + Parser ---
    val s = initPipelineState(ctx, decisions)
    s.packetCtx.addTraceEvent(packetIngressEvent(ctx.ingressPort))
    val parserExitDrop = runParser(s)
    s.postParserEnv = s.env.deepCopy()
    if (parserExitDrop) return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)

    // An assert()/assume() failure anywhere in the pipeline drops the packet.
    try {
      // --- Ingress controls (verify checksum, ingress) ---
      runControlStages(s, s.ingressControls, duringIngress = true)

      // --- Ingress→egress boundary (traffic manager) ---
      ingressEgressBoundary(ctx, s)
      if (egressPortIsDropPort(s)) {
        return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
      }

      // --- Egress + deparser ---
      return runEgressAndDeparser(ctx, s)
    } catch (_: AssertionFailureException) {
      return buildDropTrace(s.packetCtx.getEvents(), DropReason.ASSERTION_FAILURE)
    }
  }

  /**
   * Runs egress controls, post-egress boundary, deparser, and output. Called after the
   * ingress-egress boundary has set egress_port.
   */
  private fun runEgressAndDeparser(ctx: PipelineContext, s: PipelineState): TraceTree {
    resetEgressSpec(s)
    runControlStages(s, s.egressControls)
    postEgressBoundary(ctx, s)

    if (egressSpecIsDropPort(s)) {
      return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
    }

    return runDeparser(s)
  }

  /**
   * Post-egress boundary + deparser + output. Used by [continueFromForkPoint] after resuming from a
   * fork inside the egress controls (remaining egress stages already ran).
   */
  private fun runPostEgressAndDeparser(ctx: PipelineContext, s: PipelineState): TraceTree {
    postEgressBoundary(ctx, s)

    if (egressSpecIsDropPort(s)) {
      return buildDropTrace(s.packetCtx.getEvents(), DropReason.MARK_TO_DROP)
    }

    return runDeparser(s)
  }

  /** Deparser + output (or recirculate). The innermost pipeline tail. */
  @Suppress("ThrowsCount")
  private fun runDeparser(s: PipelineState): TraceTree {
    if (s.deparserStage != null) {
      s.packetCtx.addTraceEvent(stageEvent(s.deparserStage, PipelineStageEvent.Direction.ENTER))
      try {
        s.interpreter.runControl(s.deparserStage.blockName, s.env)
      } finally {
        s.packetCtx.addTraceEvent(stageEvent(s.deparserStage, PipelineStageEvent.Direction.EXIT))
      }
    }

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

  /** Runs the parser stage, returning true if the parser called exit (drop). */
  private fun runParser(s: PipelineState): Boolean {
    if (s.parserStage == null) return false
    s.packetCtx.addTraceEvent(stageEvent(s.parserStage, PipelineStageEvent.Direction.ENTER))
    var exitDrop = false
    try {
      s.interpreter.runParser(s.parserStage.blockName, s.env)
    } catch (_: ExitException) {
      exitDrop = true
    } catch (e: ParserErrorException) {
      s.standardMetadata.fields["parser_error"] = ErrorVal(e.errorName)
    } finally {
      s.packetCtx.addTraceEvent(stageEvent(s.parserStage, PipelineStageEvent.Direction.EXIT))
    }
    return exitDrop
  }

  /** Runs a list of control stages, emitting enter/exit events for each. */
  private fun runControlStages(
    s: PipelineState,
    stages: List<PipelineStage>,
    duringIngress: Boolean = false,
  ) {
    for ((i, stage) in stages.withIndex()) {
      s.packetCtx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.ENTER))
      try {
        s.interpreter.runControl(stage.blockName, s.env)
      } catch (_: ExitException) {
        break
      } catch (fork: ActionSelectorFork) {
        // Wrap with V1Model-specific fork-point state for fork-on-write resume.
        // Stage EXIT still fires (via finally) — the fork-point events include it.
        throw V1ModelSelectorFork(
          fork,
          s.pendingOps.copy(),
          stage,
          stages.subList(i + 1, stages.size),
          duringIngress,
          s.postParserEnv,
        )
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
  private fun ingressEgressBoundary(ctx: PipelineContext, s: PipelineState) {
    // BMv2 priority order: I2E clone > resubmit > multicast > unicast/drop.
    val pendingClone = s.pendingOps.cloneSessionId
    if (pendingClone != null) {
      resolveCloneSession(ctx, s, pendingClone)?.let { clonePort ->
        throw CloneFork(
          pendingClone,
          clonePort,
          s.packetCtx.getEvents(),
          snapshotPreservedMetadata(s, s.pendingOps.cloneFieldListId),
          s.env.deepCopy(),
          s.packetCtx.bytesConsumed,
          s.pendingOps.copy(),
          s.postParserEnv ?: error("no post-parser env for I2E clone"),
        )
      }
    }
    if (s.pendingOps.resubmit) {
      throw ResubmitFork(
        s.packetCtx.getEvents(),
        snapshotPreservedMetadata(s, s.pendingOps.resubmitFieldListId),
      )
    }
    val mcastGrp = (s.standardMetadata.fields["mcast_grp"] as? BitVal)?.bits?.value?.toInt() ?: 0
    if (mcastGrp != 0) {
      val group = ctx.tableStore.getMulticastGroup(mcastGrp)
      if (group != null) {
        val replicas = group.replicasList.map { r -> Replica(r.instance, replicaPort(r)) }
        throw MulticastFork(
          replicas,
          s.packetCtx.getEvents(),
          s.env.deepCopy(),
          s.packetCtx.bytesConsumed,
          s.pendingOps.copy(),
        )
      }
    }
    // Normal unicast: copy egress_spec → egress_port.
    s.standardMetadata.fields["egress_port"] =
      s.standardMetadata.fields["egress_spec"] ?: BitVal(0, s.portBits)
  }

  /**
   * Handles the post-egress boundary.
   *
   * BMv2 priority order after egress: E2E clone > recirculate > output/drop.
   */
  private fun postEgressBoundary(ctx: PipelineContext, s: PipelineState) {
    val pendingE2EClone = s.pendingOps.egressCloneSessionId
    if (pendingE2EClone != null) {
      resolveCloneSession(ctx, s, pendingE2EClone)?.let { clonePort ->
        throw EgressCloneFork(
          pendingE2EClone,
          clonePort,
          s.packetCtx.getEvents(),
          snapshotPreservedMetadata(s, s.pendingOps.egressCloneFieldListId),
          s.env.deepCopy(),
          s.packetCtx.bytesConsumed,
          s.pendingOps.copy(),
        )
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
      val egressPort = replicaPort(session.replicasList.first())
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
          .setDataplaneEgressPort(egressPort)
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
    dropPort: Long,
  ): ExternHandler = ExternHandler { call, eval ->
    when (call) {
      is ExternCall.FreeFunction ->
        v1modelExternCall(call.name, eval, standardMetadata, pendingOps, dropPort)
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
    dropPort: Long,
  ): Value =
    when (name) {
      // mark_to_drop(standard_metadata): sets egress_spec to the drop port.
      "mark_to_drop" -> {
        eval.addTraceEvent(
          eval
            .traceEventBuilder()
            .setMarkToDrop(MarkToDropEvent.newBuilder().setReason(DropReason.MARK_TO_DROP))
            .build()
        )
        val smeta = eval.evalArg(0) as StructVal
        smeta.fields["egress_spec"] = BitVal(dropPort, smeta.bitWidth("egress_spec"))
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
      else -> error("v1model extern '$name' is not implemented")
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
          else ->
            error(
              "v1model register method '${call.method}' " +
                "is not implemented (on ${call.instanceName})"
            )
        }
      "counter",
      "direct_counter" ->
        when (call.method) {
          // fire-and-forget side-effect, invisible to data plane.
          "count" -> UnitVal
          else ->
            error(
              "v1model counter method '${call.method}' is not implemented (on ${call.instanceName})"
            )
        }
      "meter" ->
        when (call.method) {
          // execute_meter(in index, out color): always GREEN (no real rates in simulator).
          "execute_meter" -> {
            eval.writeOutArg(1, eval.defaultValue(eval.argType(1)))
            UnitVal
          }
          else ->
            error(
              "v1model meter method '${call.method}' is not implemented (on ${call.instanceName})"
            )
        }
      "direct_meter" ->
        when (call.method) {
          // read(out color): always GREEN (no real rates in simulator).
          "read" -> {
            eval.writeOutArg(0, eval.defaultValue(eval.argType(0)))
            UnitVal
          }
          else ->
            error(
              "v1model direct_meter method '${call.method}' " +
                "is not implemented (on ${call.instanceName})"
            )
        }
      else ->
        error(
          "v1model extern method '${call.externType}.${call.method}' is not implemented" +
            " (on ${call.instanceName})"
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

/** Multicast replica descriptor: egress port and replication ID. */
internal data class Replica(val rid: Int, val port: Int)

/**
 * v1model-specific policies for pipeline execution.
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

  fun copy(): V1ModelPendingOps {
    val c = V1ModelPendingOps()
    c.cloneSessionId = cloneSessionId
    c.cloneFieldListId = cloneFieldListId
    c.egressCloneSessionId = egressCloneSessionId
    c.egressCloneFieldListId = egressCloneFieldListId
    c.resubmit = resubmit
    c.resubmitFieldListId = resubmitFieldListId
    c.recirculate = recirculate
    c.recirculateFieldListId = recirculateFieldListId
    return c
  }
}

// -------------------------------------------------------------------------
// v1model-specific fork exceptions
// -------------------------------------------------------------------------

/**
 * Wraps an [ActionSelectorFork] with v1model-specific pipeline state captured at the fork point.
 *
 * Created by [runControlStages] when it catches the interpreter's fork exception. Carries
 * everything needed to resume each branch from the fork point (fork-on-write) instead of replaying
 * the pipeline.
 */
internal class V1ModelSelectorFork(
  val fork: ActionSelectorFork,
  val pendingOps: V1ModelPendingOps,
  val stage: PipelineStage,
  val remainingStages: List<PipelineStage>,
  /** True if the fork happened during ingress controls (boundary + egress + deparser remain). */
  val duringIngress: Boolean,
  /** Post-parser env for I2E clone fork-on-write (may be null for egress forks). */
  val postParserEnv: Environment? = null,
) : ForkException(fork.eventsBeforeFork)

/**
 * Fork at the ingress→egress boundary when an I2E clone was requested — "original" and "clone".
 * Carries fork-point state so both branches can continue via fork-on-write.
 */
internal class CloneFork(
  val sessionId: Int,
  val clonePort: Long,
  eventsBeforeFork: List<TraceEvent>,
  val preservedMetadata: Map<String, Value>? = null,
  /** Deep copy of the post-ingress environment (for the "original" branch). */
  val forkPointEnv: Environment,
  /** Parser buffer position at the fork point. */
  val bytesConsumed: Int,
  /** Pending operations at the fork point. */
  val forkPointPendingOps: V1ModelPendingOps,
  /** Post-parser env (for the "clone" branch — BMv2: clone gets pre-ingress state). */
  val postParserEnv: Environment,
) : ForkException(eventsBeforeFork)

/**
 * Fork after egress controls when an E2E clone was requested — "original" and "clone". Carries
 * fork-point state so both branches can continue via fork-on-write.
 */
internal class EgressCloneFork(
  val sessionId: Int,
  val clonePort: Long,
  eventsBeforeFork: List<TraceEvent>,
  val preservedMetadata: Map<String, Value>? = null,
  /** Deep copy of the post-egress environment (for both branches). */
  val forkPointEnv: Environment,
  /** Parser buffer position at the fork point. */
  val bytesConsumed: Int,
  /** Pending operations at the fork point. */
  val forkPointPendingOps: V1ModelPendingOps,
) : ForkException(eventsBeforeFork)

/** Fork at the ingress→egress boundary when mcast_grp is set — one branch per replica. */
internal class MulticastFork(
  val replicas: List<Replica>,
  eventsBeforeFork: List<TraceEvent>,
  /** Deep copy of the environment at the fork point (post-ingress). */
  val forkPointEnv: Environment,
  /** Parser buffer position at the fork point. */
  val bytesConsumed: Int,
  /** Pending operations at the fork point. */
  val forkPointPendingOps: V1ModelPendingOps,
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
