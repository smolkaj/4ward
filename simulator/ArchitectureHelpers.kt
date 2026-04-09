package fourward.simulator

import fourward.ir.BehavioralConfig
import fourward.ir.ExternInstanceDecl
import fourward.ir.PipelineStage
import fourward.ir.TypeDecl
import fourward.sim.PipelineStageEvent
import fourward.sim.TraceTree
import java.math.BigInteger

/** Simplified parameter descriptor: just name and type. */
internal data class BlockParam(val name: String, val typeName: String)

/** Architecture-level packet I/O types that are not user-visible parameters. */
internal val IO_TYPES = setOf("packet_in", "packet_out")

/** Guard against infinite recirculation loops. */
internal const val MAX_RECIRCULATIONS = 16

/** Builds a map from block name to parameter list across all parsers and controls. */
internal fun buildBlockParamsMap(config: BehavioralConfig): Map<String, List<BlockParam>> {
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
 * Returns a mutable map keyed by type name. The same value instance is shared across all parameters
 * of that type (e.g., `headers` is shared between parser `parsed_hdr` and control `hdr`), so
 * mutations in one stage are visible to subsequent stages.
 */
internal fun createDefaultValues(
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

/** Builds a flat map from extern instance name to declaration across all parsers and controls. */
internal fun buildExternInstancesMap(config: BehavioralConfig): Map<String, ExternInstanceDecl> =
  (config.parsersList.flatMap { it.externInstancesList } +
      config.controlsList.flatMap { it.externInstancesList })
    .associateBy { it.name }

/**
 * Binds a stage's parameters in the environment, mapping each param name to the shared value for
 * its type.
 *
 * PSA/PNA stages reuse parameter names (e.g. `istd`) with different types, so parameters must be
 * re-bound before each stage.
 */
internal fun bindStageParams(
  env: Environment,
  blockName: String,
  blockParams: Map<String, List<BlockParam>>,
  valueByType: Map<String, Value>,
) {
  for (param in blockParams[blockName] ?: emptyList()) {
    valueByType[param.typeName]?.let { env.define(param.name, it) }
  }
}

/** Runs a parser stage, recording trace events and routing parser errors to [onParserError]. */
internal fun runParserStage(
  interpreter: Interpreter.Execution,
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

/** Runs a control stage, recording trace events. An `exit` statement terminates only the stage. */
internal fun runControlStage(
  interpreter: Interpreter.Execution,
  ctx: PacketContext,
  env: Environment,
  stage: PipelineStage,
) {
  ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.ENTER))
  try {
    interpreter.runControl(stage.blockName, env)
  } catch (_: ExitException) {
    // exit terminates the current control, but the pipeline continues.
  } finally {
    ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.EXIT))
  }
}

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
internal fun handleActionSelectorFork(
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
      fourward.sim.ForkBranch.newBuilder()
        .setLabel("member_${member.memberId}")
        .setSubtree(stripped.build())
        .build()
    }
  return buildForkTree(fork.eventsBeforeFork, fourward.sim.ForkReason.ACTION_SELECTOR, branches)
}

/**
 * Handles extern methods shared across PSA and PNA: Register, Random, Counter, Hash, Meter,
 * InternetChecksum, Digest, and Checksum.
 *
 * Returns the result [Value], or `null` if [call] is not a common extern method (the caller should
 * handle it as architecture-specific).
 */
@Suppress("LongParameterList")
internal fun handleCommonExternMethod(
  call: ExternCall.Method,
  eval: ExternEvaluator,
  tableStore: TableStore,
  externInstances: Map<String, ExternInstanceDecl>,
  checksumState: MutableMap<String, BigInteger>,
  hashAlgorithms: Map<String, String>,
): Value? =
  when (call.method) {
    "read" ->
      when (call.externType) {
        "Register" -> {
          val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
          tableStore.registerRead(call.instanceName, index) ?: eval.defaultValue(eval.returnType())
        }
        "Random" -> {
          val instance = externInstances[call.instanceName]
          val lo = instance?.constructorArgsList?.getOrNull(0)?.literal?.integer ?: 0L
          val hi = instance?.constructorArgsList?.getOrNull(1)?.literal?.integer ?: 0L
          val value = if (hi > lo) kotlin.random.Random.nextLong(lo, hi + 1) else lo
          BitVal(BitVector(BigInteger.valueOf(value), eval.returnType().bit.width))
        }
        else -> null
      }
    "write" -> {
      val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
      tableStore.registerWrite(call.instanceName, index, eval.evalArg(1))
      UnitVal
    }
    "count" -> UnitVal
    "get_hash" -> evalGetHash(call, eval, externInstances, hashAlgorithms)
    "execute" -> EnumVal("GREEN")
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
    "pack" -> UnitVal
    // PNA's Checksum<W> extern uses `update` (same semantics as InternetChecksum `add`).
    "update" -> {
      val data = eval.evalArg(0).asStructVal()
      val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
      checksumState[call.instanceName] = onesComplementAdd(sum, sumWords(data))
      UnitVal
    }
    else -> null
  }
