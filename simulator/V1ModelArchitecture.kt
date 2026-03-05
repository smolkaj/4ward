package fourward.simulator

import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.StageKind
import fourward.sim.v1.ForkBranch
import fourward.sim.v1.ForkNode
import fourward.sim.v1.TraceTree

/**
 * v1model pipeline implementation.
 *
 * v1model is the original BMv2 architecture, defined in v1model.p4. The pipeline runs six stages in
 * fixed order:
 *
 * MyParser → MyVerifyChecksum → MyIngress → MyEgress → MyComputeChecksum → MyDeparser
 *
 * Architecture-specific behaviour implemented here:
 * - standard_metadata_t initialisation and egress_spec routing.
 * - mark_to_drop() (sets egress_spec to DROP_PORT = 511).
 * - clone/resubmit/recirculate stubs (not yet fully implemented).
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

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: P4BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    val ctx = PipelineContext(ingressPort, payload, config, tableStore)
    return buildTraceTree(ctx, emptyMap(), prefixLength = 0)
  }

  /**
   * Recursively builds a trace tree by re-executing the pipeline for each fork branch.
   *
   * When a [ForkException] is thrown (e.g. action selector group hit), this method re-executes the
   * full pipeline once per group member with that member "forced", and assembles the results into a
   * [TraceTree] with a [ForkNode]. Shared prefix events are stripped from branches.
   */
  private fun buildTraceTree(
    ctx: PipelineContext,
    forcedSelections: Map<String, Int>,
    prefixLength: Int,
  ): PipelineResult {
    try {
      val (outputs, trace) = runPipeline(ctx, forcedSelections)
      val stripped =
        TraceTree.newBuilder().addAllEvents(trace.eventsList.drop(prefixLength)).build()
      return PipelineResult(outputs, stripped)
    } catch (fork: ForkException) {
      val levelEvents = fork.eventsBeforeFork.drop(prefixLength)
      val branches =
        fork.members.map { member ->
          val newForced = forcedSelections + (fork.tableName to member.memberId)
          val branchResult = buildTraceTree(ctx, newForced, fork.eventsBeforeFork.size)
          ForkBranch.newBuilder()
            .setLabel("member_${member.memberId}")
            .setSubtree(branchResult.trace)
            .build()
        }
      val tree =
        TraceTree.newBuilder()
          .addAllEvents(levelEvents)
          .setFork(ForkNode.newBuilder().setReason(fork.reason).addAllBranches(branches))
          .build()
      return PipelineResult(emptyList(), tree)
    }
  }

  /** Executes the full v1model pipeline once, returning output packets and flat trace. */
  private fun runPipeline(
    ctx: PipelineContext,
    forcedSelections: Map<String, Int>,
  ): PipelineResult {
    val packetCtx = PacketContext(ctx.payload)
    val interpreter = Interpreter(ctx.config, ctx.tableStore, packetCtx, forcedSelections)
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
    val headersTypeName = parserUserParams[0].type.named // e.g. "headers_t"
    val metaTypeName = parserUserParams[1].type.named // e.g. "metadata_t"
    val standardMetaTypeName = parserUserParams[2].type.named // e.g. "standard_metadata_t"

    // Build standard_metadata from the IR type so all declared fields are present with zero
    // defaults, then set the fields that have non-zero initial values.
    val standardMetadata =
      (defaultValue(standardMetaTypeName, typesByName) as? StructVal)
        ?: error("$standardMetaTypeName not found in IR types; is v1model.p4 included?")
    // These fields are guaranteed by the v1model.p4 spec; the checks catch non-standard setups.
    check("ingress_port" in standardMetadata.fields) { "$standardMetaTypeName has no ingress_port" }
    check("packet_length" in standardMetadata.fields) {
      "$standardMetaTypeName has no packet_length"
    }
    standardMetadata.fields["ingress_port"] = BitVal(ctx.ingressPort.toLong(), PORT_BITS)
    standardMetadata.fields["packet_length"] = BitVal(ctx.payload.size.toLong(), INT32_BITS)
    standardMetadata.fields["parser_error"] = ErrorVal("NoError")

    // Map each shared type name to its initialised object so we can bind whatever
    // local parameter names each stage uses (e.g. "smeta" vs "standard_metadata").
    val sharedByType =
      mapOf(
        headersTypeName to defaultValue(headersTypeName, typesByName),
        metaTypeName to defaultValue(metaTypeName, typesByName),
        standardMetaTypeName to standardMetadata,
      )

    // Bind every stage's parameter names upfront. All stages share the same
    // underlying objects; different stages may just use different local names.
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

    val stages = config.architecture.stagesList
    val parserStage = stages.find { it.kind == StageKind.PARSER }
    val controlStages = stages.filter { it.kind == StageKind.CONTROL }
    val deparserStage = stages.find { it.kind == StageKind.DEPARSER }

    // --- Parser ---
    if (parserStage != null) {
      try {
        interpreter.runParser(parserStage.blockName, env)
      } catch (e: ExitException) {
        return PipelineResult(emptyList(), packetCtx.buildTrace())
      } catch (e: ParserErrorException) {
        // BMv2 v1model: parser errors don't drop the packet. Set parser_error and
        // continue to the ingress pipeline, letting the P4 program decide the fate.
        standardMetadata.fields["parser_error"] = ErrorVal(e.errorName)
      }
    }

    // --- Controls (verify checksum, ingress, egress, compute checksum) ---
    // P4 exit terminates all active control blocks up to the pipeline level but
    // does NOT drop the packet — forwarding is still governed by egress_spec at
    // the time of the exit (P4 spec §12.4, v1model semantics).
    for (stage in controlStages) {
      try {
        interpreter.runControl(stage.blockName, env)
      } catch (e: ExitException) {
        break // skip remaining control stages; still run deparser below
      }
    }

    val egressSpec = (standardMetadata.fields["egress_spec"] as? BitVal)?.bits?.value?.toInt() ?: 0

    // Port 511 is the v1model drop port (mark_to_drop sets egress_spec = 511).
    if (egressSpec == DROP_PORT) {
      return PipelineResult(emptyList(), packetCtx.buildTrace())
    }

    // --- Deparser ---
    if (deparserStage != null) {
      interpreter.runControl(deparserStage.blockName, env)
    }

    // Append any bytes the parser did not extract (the un-parsed packet body).
    // In P4, the deparser emits re-serialised headers; the remaining payload
    // is transparently forwarded after them.
    val outputBytes = packetCtx.outputPayload() + packetCtx.drainRemainingInput()
    val output = OutputPacket(egressSpec.toUInt(), outputBytes)
    return PipelineResult(listOf(output), packetCtx.buildTrace())
  }

  companion object {
    /** Port value used by mark_to_drop() to signal packet drop in v1model. */
    const val DROP_PORT = 511

    // Number of user-visible params in the v1model parser after removing packet_in/packet_out:
    // (hdr, meta, standard_metadata).
    private const val V1MODEL_USER_PARAM_COUNT = 3

    // Bit widths for standard_metadata_t fields, as defined in v1model.p4.
    const val PORT_BITS = 9
    private const val INT32_BITS = 32
  }
}
