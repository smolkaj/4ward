package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.P4BehavioralConfig
import java.math.BigInteger
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Translates action parameter values between P4Runtime (SDN) and data-plane representations.
 *
 * P4 programs can annotate types with `@p4runtime_translation(uri, sdn_bitwidth)` to declare that
 * the controller-facing representation differs from the data-plane representation. For example:
 *
 *     @p4runtime_translation("test.port_id", 32)
 *     type bit<9> port_id_t;
 *
 * Here the controller uses 32-bit values while the data plane uses 9-bit values. This class handles
 * the bidirectional conversion so the P4RuntimeService can pass correct values to the simulator and
 * return correct values to the controller.
 *
 * Built from both the p4info (which declares translated types and SDN bitwidths) and the behavioral
 * IR (which has the original data-plane bitwidths).
 */
class TypeTranslator
private constructor(private val paramTranslations: Map<Long, ParamTranslation>) {

  /** Returns true if this translator has any translated types to handle. */
  val hasTranslations: Boolean
    get() = paramTranslations.isNotEmpty()

  /**
   * Translates a Write update from SDN to data-plane representation.
   *
   * For each action parameter that uses a translated type, the value is narrowed from the SDN
   * bitwidth to the data-plane bitwidth.
   */
  fun translateForWrite(update: Update): Update {
    if (!update.entity.hasTableEntry()) return update
    val entry = update.entity.tableEntry
    if (!entry.hasAction() || !entry.action.hasAction()) return update
    val action = entry.action.action
    val translatedParams = canonicalizeParams(action.actionId, action.paramsList) ?: return update
    return update
      .toBuilder()
      .setEntity(
        update.entity
          .toBuilder()
          .setTableEntry(
            entry
              .toBuilder()
              .setAction(
                entry.action
                  .toBuilder()
                  .setAction(action.toBuilder().clearParams().addAllParams(translatedParams))
              )
          )
      )
      .build()
  }

  /**
   * Translates a Read entity from data-plane to SDN representation.
   *
   * For each action parameter that uses a translated type, the value is widened from the data-plane
   * bitwidth to the SDN bitwidth.
   */
  fun translateForRead(entity: Entity): Entity {
    if (!entity.hasTableEntry()) return entity
    val entry = entity.tableEntry
    if (!entry.hasAction() || !entry.action.hasAction()) return entity
    val action = entry.action.action
    val translatedParams = canonicalizeParams(action.actionId, action.paramsList)
    translatedParams ?: return entity
    return entity
      .toBuilder()
      .setTableEntry(
        entry
          .toBuilder()
          .setAction(
            entry.action
              .toBuilder()
              .setAction(action.toBuilder().clearParams().addAllParams(translatedParams))
          )
      )
      .build()
  }

  /** Canonicalizes translated params to minimum-width encoding; returns null if nothing changed. */
  private fun canonicalizeParams(
    actionId: Int,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param>,
  ): List<p4.v1.P4RuntimeOuterClass.Action.Param>? {
    var changed = false
    val result =
      params.map { param ->
        val key = paramKey(actionId, param.paramId)
        if (paramTranslations.containsKey(key)) {
          changed = true
          param.toBuilder().setValue(canonicalizeValue(param.value)).build()
        } else {
          param
        }
      }
    return if (changed) result else null
  }

  companion object {
    /**
     * Builds a TypeTranslator from a pipeline's p4info and behavioral config.
     *
     * Returns a translator with no translations if the p4info has no translated types.
     */
    fun create(p4info: P4Info, behavioral: P4BehavioralConfig): TypeTranslator {
      val translatedTypes =
        p4info.typeInfo.newTypesMap.filter { (_, spec) -> spec.hasTranslatedType() }
      if (translatedTypes.isEmpty()) return TypeTranslator(emptyMap())

      // Build a lookup from behavioral action name → param name → dataplane bitwidth.
      val behavioralParamWidths = buildBehavioralParamWidths(behavioral)

      // Resolve p4info action names → behavioral action names (same logic as Simulator).
      val behavioralActionNames =
        (behavioral.actionsList + behavioral.controlsList.flatMap { it.localActionsList })
          .flatMap { action -> listOfNotNull(action.name, action.currentName.ifEmpty { null }) }

      val paramTranslations = mutableMapOf<Long, ParamTranslation>()

      for (action in p4info.actionsList) {
        val alias = action.preamble.alias.ifEmpty { action.preamble.name }
        val behavioralName = resolveName(alias, behavioralActionNames)
        val behavioralParams = behavioralParamWidths[behavioralName] ?: continue

        for (param in action.paramsList) {
          if (!param.hasTypeName()) continue
          val typeName = param.typeName.name
          val typeSpec = translatedTypes[typeName] ?: continue
          val translatedType = typeSpec.translatedType

          val sdnBitwidth = translatedType.sdnBitwidth
          val dataplaneBitwidth = behavioralParams[param.name] ?: continue

          // For Write: narrow SDN → dataplane. For Read: widen dataplane → SDN.
          paramTranslations[paramKey(action.preamble.id, param.id)] =
            ParamTranslation(sdnBitwidth = sdnBitwidth, dataplaneBitwidth = dataplaneBitwidth)
        }
      }

      return TypeTranslator(paramTranslations)
    }

    private fun buildBehavioralParamWidths(
      behavioral: P4BehavioralConfig
    ): Map<String, Map<String, Int>> {
      val result = mutableMapOf<String, Map<String, Int>>()
      val allActions =
        behavioral.actionsList + behavioral.controlsList.flatMap { it.localActionsList }
      for (action in allActions) {
        val paramWidths = mutableMapOf<String, Int>()
        for (param in action.paramsList) {
          if (param.type.hasBit()) {
            paramWidths[param.name] = param.type.bit.width
          }
        }
        if (paramWidths.isNotEmpty()) {
          result[action.name] = paramWidths
        }
      }
      return result
    }

    private fun resolveName(alias: String, candidates: List<String>): String =
      candidates.find { it == alias } ?: candidates.find { it.endsWith("_$alias") } ?: alias

    /** Encodes (actionId, paramId) as a single Long for fast lookup. */
    private fun paramKey(actionId: Int, paramId: Int): Long =
      (actionId.toLong() shl 32) or (paramId.toLong() and 0xFFFFFFFFL)

    /**
     * Re-encodes a value as minimum-width unsigned big-endian bytes (P4Runtime canonical form).
     *
     * For `sdn_bitwidth` translation the numeric value is identical in both representations — only
     * the byte encoding changes. The actual bitwidth constraint is enforced by the simulator's
     * interpreter (BitVector truncation), not here.
     */
    internal fun canonicalizeValue(value: ByteString): ByteString {
      val bytes = value.toByteArray()
      // Already canonical: single byte, or first byte is non-zero (no leading padding).
      if (bytes.size <= 1 || bytes[0] != 0.toByte()) return value
      val numeric = BigInteger(1, bytes)
      if (numeric == BigInteger.ZERO) return ByteString.copyFrom(byteArrayOf(0))
      val fullBytes = numeric.toByteArray()
      // BigInteger.toByteArray() is signed — strip the leading zero byte if present.
      val start = if (fullBytes[0] == 0.toByte() && fullBytes.size > 1) 1 else 0
      return ByteString.copyFrom(fullBytes, start, fullBytes.size - start)
    }
  }

  private data class ParamTranslation(val sdnBitwidth: Int, val dataplaneBitwidth: Int)
}
