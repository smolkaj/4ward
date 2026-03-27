package fourward.simulator

import fourward.ir.BehavioralConfig
import fourward.ir.ExternInstanceDecl
import fourward.ir.PipelineStage
import fourward.ir.TypeDecl
import fourward.sim.SimulatorProto.DropReason
import fourward.sim.SimulatorProto.Fork
import fourward.sim.SimulatorProto.ForkBranch
import fourward.sim.SimulatorProto.ForkReason
import fourward.sim.SimulatorProto.PipelineStageEvent
import fourward.sim.SimulatorProto.TraceEvent
import fourward.sim.SimulatorProto.TraceTree
import java.math.BigInteger

/**
 * PNA (Portable NIC Architecture) pipeline implementation.
 *
 * PNA is a single-pipeline architecture for network interface cards. Unlike PSA's two-pipeline
 * (ingress + egress) structure, PNA runs a single main pipeline: PreControl → MainParser →
 * MainControl → MainDeparser.
 *
 * Key differences from PSA:
 * - Single-pass: no separate egress pipeline.
 * - Drop/forward via free functions: `drop_packet()` and `send_to_port()` (not ostd fields).
 * - Direction-aware: packets have a direction (NET_TO_HOST or HOST_TO_NET).
 * - Recirculation loops back to main parser (not to a separate ingress).
 *
 * References:
 * - PNA spec: https://p4.org/p4-spec/docs/PNA.html
 * - p4c pna.p4: https://github.com/p4lang/p4c/blob/main/p4include/pna.p4
 */
class PNAArchitecture : Architecture {

  /** Pipeline-invariant state derived from the [BehavioralConfig]. Computed once per packet. */
  @Suppress("LongParameterList")
  private class PipelineConfig(
    val config: BehavioralConfig,
    val tableStore: TableStore,
    val blockParams: Map<String, List<BlockParam>>,
    val typesByName: Map<String, TypeDecl>,
    val externInstances: Map<String, ExternInstanceDecl>,
    val mainParser: PipelineStage,
    val preControl: PipelineStage,
    val mainControl: PipelineStage,
    val mainDeparser: PipelineStage,
  )

  /**
   * Mutable forwarding state shared between extern handler and pipeline logic.
   *
   * PNA uses "last writer wins" semantics for `drop_packet()` / `send_to_port()`. The default
   * behavior is DROP — if neither function is called, the packet is dropped.
   */
  private class ForwardingState {
    var dropped: Boolean = true
    var destPort: Int = 0
    var recirculate: Boolean = false
  }

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    val stages = config.architecture.stagesList
    fun stage(name: String) =
      stages
        .first { it.name == name }
        .also { require(it.blockName.isNotEmpty()) { "PNA stage '$name' has no block name" } }
    val pipeline =
      PipelineConfig(
        config,
        tableStore,
        blockParams = buildBlockParamsMap(config),
        typesByName = config.typesList.associateBy { it.name },
        externInstances = buildExternInstancesMap(config),
        mainParser = stage("main_parser"),
        preControl = stage("pre_control"),
        mainControl = stage("main_control"),
        mainDeparser = stage("main_deparser"),
      )

    val tree =
      processPacketRecursive(
        pipeline,
        payload,
        ingressPort,
        PACKET_PATH_FROM_NET_PORT,
        passNumber = 0,
        depth = 0,
      )
    return PipelineResult(tree)
  }

  /**
   * Runs one full PNA pass: PreControl → MainParser → MainControl → MainDeparser.
   *
   * For recirculate, calls itself recursively with the deparsed bytes looped back to main_parser.
   * [depth] guards against infinite recirculation loops. [selectorMembers] carries forced action
   * selector member choices from prior fork re-executions.
   */
  @Suppress("LongParameterList")
  private fun processPacketRecursive(
    pipeline: PipelineConfig,
    payload: ByteArray,
    ingressPort: UInt,
    packetPath: String,
    passNumber: Int,
    depth: Int,
    selectorMembers: Map<String, Int> = emptyMap(),
  ): TraceTree {
    check(depth < MAX_RECIRCULATIONS) { "PNA recirculation depth exceeded ($MAX_RECIRCULATIONS)" }

    val ctx = PacketContext(payload)
    val env = Environment()
    val values = createDefaultValues(pipeline.config, pipeline.typesByName)
    val forwardingState = ForwardingState()

    initMetadata(values, ingressPort, passNumber)

    val checksumState = mutableMapOf<String, BigInteger>()
    val interpreter =
      Interpreter(
        pipeline.config,
        pipeline.tableStore,
        ctx,
        selectorMembers,
        createPnaExternHandler(pipeline, forwardingState, checksumState),
      )

    ctx.addTraceEvent(packetIngressEvent(ingressPort))

    // --- PreControl ---
    // The PRE stage is minimally specified in PNA (decrypt is TBD). Run it as a normal control.
    bindStageParams(env, pipeline.preControl.blockName, pipeline.blockParams, values)
    runControlStage(interpreter, ctx, env, pipeline.preControl)

    // --- Main Parser ---
    bindStageParams(env, pipeline.mainParser.blockName, pipeline.blockParams, values)
    runParserStage(interpreter, ctx, env, pipeline.mainParser) { e ->
      (values["pna_main_input_metadata_t"] as? StructVal)?.let {
        it.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    try {
      // --- Main Control ---
      bindStageParams(env, pipeline.mainControl.blockName, pipeline.blockParams, values)
      runControlStage(interpreter, ctx, env, pipeline.mainControl)
    } catch (_: AssertionFailureException) {
      return buildDropTrace(ctx.getEvents(), DropReason.ASSERTION_FAILURE)
    } catch (fork: ActionSelectorFork) {
      return handleActionSelectorFork(fork, selectorMembers) { newSelectors ->
        processPacketRecursive(
          pipeline,
          payload,
          ingressPort,
          packetPath,
          passNumber,
          depth,
          newSelectors,
        )
      }
    }

    // --- Post-control forwarding decision ---
    if (forwardingState.dropped && !forwardingState.recirculate) {
      return buildDropTrace(ctx.getEvents())
    }

    // --- Main Deparser ---
    bindStageParams(env, pipeline.mainDeparser.blockName, pipeline.blockParams, values)
    runControlStage(interpreter, ctx, env, pipeline.mainDeparser)
    val deparsedBytes = ctx.outputPayload() + ctx.drainRemainingInput()

    if (forwardingState.recirculate) {
      val recircTree =
        processPacketRecursive(
          pipeline,
          deparsedBytes,
          ingressPort,
          PACKET_PATH_FROM_NET_RECIRCULATED,
          passNumber + 1,
          depth + 1,
        )
      return TraceTree.newBuilder()
        .addAllEvents(ctx.getEvents())
        .setForkOutcome(
          Fork.newBuilder()
            .setReason(ForkReason.RECIRCULATE)
            .addBranches(ForkBranch.newBuilder().setLabel("recirculate").setSubtree(recircTree))
        )
        .build()
    }

    return buildOutputTrace(ctx.getEvents(), forwardingState.destPort, deparsedBytes)
  }

  // ---------------------------------------------------------------------------
  // Metadata initialisation
  // ---------------------------------------------------------------------------

  private fun initMetadata(values: Map<String, Value>, ingressPort: UInt, passNumber: Int) {
    val loopedback = passNumber > 0
    val direction = DIRECTION_NET_TO_HOST
    val portLong = ingressPort.toLong()
    // PassNumber_t is bit<3> (pna.p4), so truncate to 3 bits to avoid overflow.
    val passTruncated = (passNumber and 0x7).toLong()

    (values["pna_pre_input_metadata_t"] as? StructVal)?.let {
      it.setBitField("input_port", portLong)
      it.fields["parser_error"] = ErrorVal("NoError")
      it.fields["direction"] = EnumVal(direction)
      if (it.fields.containsKey("pass")) it.setBitField("pass", passTruncated)
      it.fields["loopedback"] = BoolVal(loopedback)
    }
    (values["pna_pre_output_metadata_t"] as? StructVal)?.let {
      it.fields["decrypt"] = BoolVal(false)
    }
    (values["pna_main_parser_input_metadata_t"] as? StructVal)?.let {
      it.fields["direction"] = EnumVal(direction)
      if (it.fields.containsKey("pass")) it.setBitField("pass", passTruncated)
      it.fields["loopedback"] = BoolVal(loopedback)
      it.setBitField("input_port", portLong)
    }
    (values["pna_main_input_metadata_t"] as? StructVal)?.let {
      it.fields["direction"] = EnumVal(direction)
      if (it.fields.containsKey("pass")) it.setBitField("pass", passTruncated)
      it.fields["loopedback"] = BoolVal(loopedback)
      if (it.fields.containsKey("timestamp")) it.setBitField("timestamp", 0L)
      it.fields["parser_error"] = ErrorVal("NoError")
      if (it.fields.containsKey("class_of_service")) it.setBitField("class_of_service", 0L)
      it.setBitField("input_port", portLong)
    }
    (values["pna_main_output_metadata_t"] as? StructVal)?.let {
      if (it.fields.containsKey("class_of_service")) it.setBitField("class_of_service", 0L)
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
      // PNA: exit terminates the current control, but the pipeline continues.
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
   * PNA stages reuse parameter names (e.g. `istd`) with different types, so parameters must be
   * re-bound before each stage.
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

  /** Builds a flat map from extern instance name → declaration across all parsers and controls. */
  private fun buildExternInstancesMap(config: BehavioralConfig): Map<String, ExternInstanceDecl> =
    (config.parsersList.flatMap { it.externInstancesList } +
        config.controlsList.flatMap { it.externInstancesList })
      .associateBy { it.name }

  // ---------------------------------------------------------------------------
  // PNA extern handler
  // ---------------------------------------------------------------------------

  /**
   * PNA extern handler: free functions and extern object methods.
   *
   * PNA's forwarding externs (`drop_packet`, `send_to_port`, `recirculate`) are free functions that
   * mutate [forwardingState] directly, unlike PSA which uses output metadata fields.
   */
  private fun createPnaExternHandler(
    pipeline: PipelineConfig,
    forwardingState: ForwardingState,
    checksumState: MutableMap<String, BigInteger>,
  ): ExternHandler = ExternHandler { call, eval ->
    when (call) {
      is ExternCall.FreeFunction -> handlePnaFreeFunction(call, eval, forwardingState)
      is ExternCall.Method -> handlePnaMethod(call, eval, pipeline, checksumState)
    }
  }

  /** Handles PNA free-function externs (forwarding, mirroring, add-on-miss). */
  private fun handlePnaFreeFunction(
    call: ExternCall.FreeFunction,
    eval: ExternEvaluator,
    forwardingState: ForwardingState,
  ): Value =
    when (call.name) {
      // PNA spec (pna.p4 §562-570): drop_packet and send_to_port are the "forwarding"
      // set — calling one overwrites the other (last writer wins). recirculate is
      // independent and NOT part of this set.
      "drop_packet" -> {
        forwardingState.dropped = true
        UnitVal
      }
      "send_to_port" -> {
        val port = eval.evalArg(0) as BitVal
        forwardingState.dropped = false
        forwardingState.destPort = port.bits.value.toInt()
        UnitVal
      }
      "mirror_packet" -> error("PNA mirror_packet() is not yet implemented")
      "recirculate" -> {
        forwardingState.recirculate = true
        UnitVal
      }
      "SelectByDirection" -> {
        val direction = (eval.evalArg(0) as EnumVal).member
        if (direction == DIRECTION_NET_TO_HOST) eval.evalArg(1) else eval.evalArg(2)
      }
      // TODO(PNA): implement actual add-on-miss table entry insertion.
      "add_entry" -> BoolVal(true)
      // TODO(PNA): implement flow ID allocation.
      "allocate_flow_id" -> BitVal(BitVector(BigInteger.ZERO, 32))
      // TODO(PNA): implement entry expiration tracking.
      "set_entry_expire_time" -> UnitVal
      "restart_expire_timer" -> UnitVal
      else -> error("unhandled PNA extern: ${call.name}")
    }

  /** Handles PNA extern method calls (Register, Hash, Counter, Meter, Checksum, Digest). */
  @Suppress("LongMethod")
  private fun handlePnaMethod(
    call: ExternCall.Method,
    eval: ExternEvaluator,
    pipeline: PipelineConfig,
    checksumState: MutableMap<String, BigInteger>,
  ): Value =
    when (call.method) {
      // Register.read(index) returns T directly.
      "read" ->
        when (call.externType) {
          "Register" -> {
            val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
            pipeline.tableStore.registerRead(call.instanceName, index)
              ?: eval.defaultValue(eval.returnType())
          }
          // Random.read() — 0 args, returns a random value in [min, max].
          "Random" -> {
            val instance = pipeline.externInstances[call.instanceName]
            val lo = instance?.constructorArgsList?.getOrNull(0)?.literal?.integer ?: 0L
            val hi = instance?.constructorArgsList?.getOrNull(1)?.literal?.integer ?: 0L
            val value = if (hi > lo) kotlin.random.Random.nextLong(lo, hi + 1) else lo
            BitVal(BitVector(BigInteger.valueOf(value), eval.returnType().bit.width))
          }
          else -> error("unhandled PNA extern read: ${call.externType}.read")
        }
      "write" -> {
        val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
        pipeline.tableStore.registerWrite(call.instanceName, index, eval.evalArg(1))
        UnitVal
      }
      "count" -> UnitVal
      // Hash.get_hash: 1-arg or 3-arg form. Algorithm from constructor args.
      "get_hash" -> evalGetHash(call, eval, pipeline.externInstances)
      // Meter.execute(index): returns PNA_MeterColor_t. Always GREEN — no real
      // packet rates in simulator.
      "execute" -> EnumVal("GREEN")
      // --- InternetChecksum extern ---
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
      // Digest.pack() queues a digest message for the control plane. No-op in STF testing.
      // TODO(PNA): implement digest delivery via P4Runtime StreamChannel.
      "pack" -> UnitVal
      // Checksum extern (PNA uses Checksum<W> in addition to InternetChecksum).
      "update" -> {
        val data = eval.evalArg(0).asStructVal()
        val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
        checksumState[call.instanceName] = onesComplementAdd(sum, sumWords(data))
        UnitVal
      }
      else ->
        error(
          "unhandled PNA extern method: ${call.externType}.${call.method}" +
            " on ${call.instanceName}"
        )
    }

  /** Evaluates PNA Hash.get_hash (1-arg or 3-arg form). */
  private fun evalGetHash(
    call: ExternCall.Method,
    eval: ExternEvaluator,
    externInstances: Map<String, ExternInstanceDecl>,
  ): Value {
    val instance =
      externInstances[call.instanceName] ?: error("unknown Hash instance: ${call.instanceName}")
    val pnaAlgorithm =
      instance.constructorArgsList.firstOrNull()?.literal?.enumMember
        ?: error("Hash instance ${call.instanceName} missing algorithm constructor arg")
    val algorithm =
      PNA_HASH_ALGORITHMS[pnaAlgorithm] ?: error("unsupported PNA hash algorithm: $pnaAlgorithm")
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

  // ---------------------------------------------------------------------------
  // Action selector fork handling
  // ---------------------------------------------------------------------------

  /**
   * Handles an [ActionSelectorFork] by re-executing the pipeline segment for each group member.
   *
   * On the first encounter with an action selector group hit, the [Interpreter] throws
   * [ActionSelectorFork] with the list of group members and the trace events accumulated before the
   * fork. This method builds a fork [TraceTree] with one branch per member, re-executing via
   * [reExecute] with the forced member selection added to [currentSelectors].
   *
   * The re-execution produces identical events up to the fork point (deterministic replay), so the
   * prefix is safely stripped from each branch's trace.
   */
  private fun handleActionSelectorFork(
    fork: ActionSelectorFork,
    currentSelectors: Map<String, Int>,
    reExecute: (Map<String, Int>) -> TraceTree,
  ): TraceTree {
    val prefixLength = fork.eventsBeforeFork.size
    val branches =
      fork.members.map { member ->
        val newSelectors = currentSelectors + (fork.tableName to member.memberId)
        val subtree = reExecute(newSelectors)
        val stripped = TraceTree.newBuilder().addAllEvents(subtree.eventsList.drop(prefixLength))
        if (subtree.hasPacketOutcome()) stripped.setPacketOutcome(subtree.packetOutcome)
        if (subtree.hasForkOutcome()) stripped.setForkOutcome(subtree.forkOutcome)
        ForkBranch.newBuilder()
          .setLabel("member_${member.memberId}")
          .setSubtree(stripped.build())
          .build()
      }
    return buildForkTree(fork.eventsBeforeFork, ForkReason.ACTION_SELECTOR, branches)
  }

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
    // PNA_Direction_t enum values (pna.p4).
    const val DIRECTION_NET_TO_HOST = "NET_TO_HOST"

    // PNA_PacketPath_t enum values (pna.p4).
    const val PACKET_PATH_FROM_NET_PORT = "FROM_NET_PORT"
    const val PACKET_PATH_FROM_NET_RECIRCULATED = "FROM_NET_RECIRCULATED"

    /** Architecture-level packet I/O types that are not user-visible parameters. */
    val IO_TYPES = setOf("packet_in", "packet_out")

    /**
     * Maps PNA_HashAlgorithm_t enum members to the internal algorithm names used by Hash.kt.
     *
     * PNA's hash algorithm enum has the same values as PSA's, plus TARGET_DEFAULT which we map to
     * identity as a safe fallback.
     */
    val PNA_HASH_ALGORITHMS =
      mapOf(
        "IDENTITY" to "identity",
        "CRC16" to "crc16",
        "CRC32" to "crc32",
        "ONES_COMPLEMENT16" to "csum16",
        "TARGET_DEFAULT" to "identity",
      )

    /** Guard against infinite recirculation loops. */
    const val MAX_RECIRCULATIONS = 16
  }
}
