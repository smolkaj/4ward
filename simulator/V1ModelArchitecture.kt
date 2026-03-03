package fourward.simulator

import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.StageKind
import fourward.ir.v1.TypeDecl

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

  override fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: P4BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult {
    val interpreter = Interpreter(config, tableStore)
    val env = Environment(payload)

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

    // Initialise standard_metadata_t. The struct is defined in v1model.p4;
    // we hard-code the fields we care about for simulation purposes.
    val standardMetadata =
      StructVal(
        typeName = standardMetaTypeName,
        fields =
          mutableMapOf(
            "ingress_port" to BitVal(ingressPort.toLong(), PORT_BITS),
            "egress_spec" to BitVal(0L, PORT_BITS),
            "egress_port" to BitVal(0L, PORT_BITS),
            "packet_length" to BitVal(payload.size.toLong(), INT32_BITS),
            "instance_type" to BitVal(0L, INT32_BITS),
            "drop" to BitVal(0L, FLAG_BITS),
          ),
      )

    // Map each shared type name to its initialised object so we can bind whatever
    // local parameter names each stage uses (e.g. "smeta" vs "standard_metadata").
    val sharedByType =
      mapOf(
        headersTypeName to createDefaultValue(headersTypeName, typesByName),
        metaTypeName to createDefaultValue(metaTypeName, typesByName),
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
        return PipelineResult(emptyList(), env.buildTrace())
      }
    }

    // --- Controls (verify checksum, ingress, egress, compute checksum) ---
    for (stage in controlStages) {
      try {
        interpreter.runControl(stage.blockName, env)
      } catch (e: ExitException) {
        return PipelineResult(emptyList(), env.buildTrace())
      }
    }

    val egressSpec = (standardMetadata.fields["egress_spec"] as? BitVal)?.bits?.value?.toInt() ?: 0

    // Port 511 is the v1model drop port (mark_to_drop sets egress_spec = 511).
    if (egressSpec == DROP_PORT) {
      return PipelineResult(emptyList(), env.buildTrace())
    }

    // --- Deparser ---
    if (deparserStage != null) {
      interpreter.runControl(deparserStage.blockName, env)
    }

    // Append any bytes the parser did not extract (the un-parsed packet body).
    // In P4, the deparser emits re-serialised headers; the remaining payload
    // is transparently forwarded after them.
    val outputBytes = env.outputPayload() + env.drainRemainingInput()
    val output = OutputPacket(egressSpec.toUInt(), outputBytes)
    return PipelineResult(listOf(output), env.buildTrace())
  }

  companion object {
    /** Port value used by mark_to_drop() to signal packet drop in v1model. */
    const val DROP_PORT = 511

    // Number of user-visible params in the v1model parser after removing packet_in/packet_out:
    // (hdr, meta, standard_metadata).
    private const val V1MODEL_USER_PARAM_COUNT = 3

    // Bit widths for standard_metadata_t fields, as defined in v1model.p4.
    private const val PORT_BITS = 9
    private const val INT32_BITS = 32
    private const val FLAG_BITS = 1
  }

  /**
   * Creates a default-initialised [Value] for the named type.
   *
   * Headers are initially invalid with zeroed fields; structs are recursively initialised. Unknown
   * types (e.g. packet_in externs) return [UnitVal].
   */
  private fun createDefaultValue(typeName: String, typesByName: Map<String, TypeDecl>): Value {
    val typeDecl = typesByName[typeName] ?: return UnitVal
    return when {
      typeDecl.hasHeader() ->
        HeaderVal(
          typeName = typeName,
          fields =
            typeDecl.header.fieldsList
              .associate { f -> f.name to BitVal(0L, f.type.bit.width) }
              .toMutableMap(),
          valid = false,
        )
      typeDecl.hasStruct() ->
        StructVal(
          typeName = typeName,
          fields =
            typeDecl.struct.fieldsList
              .associate { f -> f.name to createDefaultValue(f.type.named, typesByName) }
              .toMutableMap(),
        )
      else -> UnitVal
    }
  }
}
