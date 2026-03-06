package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.TranslationEntry
import fourward.ir.v1.TypeTranslation
import java.util.concurrent.ConcurrentHashMap
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Minimum-width unsigned big-endian encoding of a non-negative integer (P4Runtime canonical form).
 */
internal fun encodeMinWidth(value: Int): ByteString {
  if (value == 0) return ByteString.copyFrom(byteArrayOf(0))
  val bytes = mutableListOf<Byte>()
  var v = value
  while (v > 0) {
    bytes.add(0, (v and 0xFF).toByte())
    v = v shr 8
  }
  return ByteString.copyFrom(bytes.toByteArray())
}

/** The SDN (controller-facing) value for a translated type. */
sealed class SdnValue {
  data class Bitstring(val value: ByteString) : SdnValue()

  data class Str(val value: String) : SdnValue()
}

/** Thrown when a value cannot be translated (no mapping and auto-allocate is off). */
class TranslationException(message: String) : RuntimeException(message)

/**
 * Bidirectional mapping between SDN (controller-facing) and data-plane values for
 * `@p4runtime_translation`-annotated types.
 *
 * Supports three modes per URI:
 * - **Explicit**: all mappings provided upfront; unknown values are rejected.
 * - **Auto-allocate**: data-plane values assigned sequentially on first use.
 * - **Hybrid**: explicit pins for known values, auto-allocate for the rest.
 *
 * When no [TypeTranslation] is provided for a URI, auto-allocation is used by default.
 */
class TypeTranslator
private constructor(
  private val tables: ConcurrentHashMap<String, TranslationTable>,
  private val paramUris: Map<Long, String>,
) {

  /** Returns true if this translator has any translated types to handle. */
  val hasTranslations: Boolean
    get() = tables.isNotEmpty() || paramUris.isNotEmpty()

  /**
   * Translates an SDN bitstring value to its data-plane representation.
   *
   * For auto-allocate URIs, creates a new mapping on first use.
   */
  fun sdnToDataplane(uri: String, sdnValue: ByteArray): ByteArray =
    getOrCreateTable(uri).lookupOrAllocateBitstring(ByteString.copyFrom(sdnValue)).toByteArray()

  /**
   * Translates an SDN string value to its data-plane representation.
   *
   * For auto-allocate URIs, creates a new mapping on first use.
   */
  fun sdnToDataplane(uri: String, sdnStr: String): ByteArray =
    getOrCreateTable(uri).lookupOrAllocateString(sdnStr).toByteArray()

  /**
   * Translates a data-plane value back to its SDN representation.
   *
   * @throws TranslationException if no reverse mapping exists.
   */
  fun dataplaneToSdn(uri: String, dataplaneValue: ByteArray): SdnValue =
    getOrCreateTable(uri).reverseLookup(ByteString.copyFrom(dataplaneValue))

  /** Gets an existing table or creates a default auto-allocate table for unknown URIs. */
  private fun getOrCreateTable(uri: String): TranslationTable =
    tables.computeIfAbsent(uri) { TranslationTable(autoAllocate = true) }

  // ---------------------------------------------------------------------------
  // P4Runtime Write/Read translation (delegates to per-param URI lookup)
  // ---------------------------------------------------------------------------

  /**
   * Translates a Write update from SDN to data-plane representation.
   *
   * For each action parameter that uses a translated type, looks up the SDN value in the mapping
   * table and replaces it with the corresponding data-plane value.
   */
  fun translateForWrite(update: Update): Update {
    if (!update.entity.hasTableEntry()) return update
    val entry = update.entity.tableEntry
    if (!entry.hasAction() || !entry.action.hasAction()) return update
    val action = entry.action.action
    val translatedParams = translateParams(action.actionId, action.paramsList, toDataplane = true)
    translatedParams ?: return update
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
   * For each action parameter that uses a translated type, looks up the data-plane value and
   * replaces it with the corresponding SDN value.
   */
  fun translateForRead(entity: Entity): Entity {
    if (!entity.hasTableEntry()) return entity
    val entry = entity.tableEntry
    if (!entry.hasAction() || !entry.action.hasAction()) return entity
    val action = entry.action.action
    val translatedParams = translateParams(action.actionId, action.paramsList, toDataplane = false)
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

  private fun translateParams(
    actionId: Int,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param>,
    toDataplane: Boolean,
  ): List<p4.v1.P4RuntimeOuterClass.Action.Param>? {
    var changed = false
    val result =
      params.map { param ->
        val uri = paramUris[paramKey(actionId, param.paramId)]
        if (uri != null) {
          changed = true
          val table = getOrCreateTable(uri)
          // Operate on ByteString directly — param.value is already ByteString,
          // so we avoid unnecessary ByteArray↔ByteString round-trips.
          val translated =
            if (toDataplane) {
              table.lookupOrAllocateBitstring(param.value)
            } else {
              when (val sdnValue = table.reverseLookup(param.value)) {
                is SdnValue.Bitstring -> sdnValue.value
                // sdn_string values can't be encoded as action param bytes — the P4Runtime
                // spec has no mechanism to return string values inside action parameters.
                is SdnValue.Str ->
                  throw TranslationException(
                    "Cannot encode sdn_string value '${sdnValue.value}' as action param bytes"
                  )
              }
            }
          param.toBuilder().setValue(translated).build()
        } else {
          param
        }
      }
    return if (changed) result else null
  }

  companion object {
    /**
     * Creates a TypeTranslator from translation configurations.
     *
     * For use without p4info — the translator supports direct URI-based lookups via
     * [sdnToDataplane] and [dataplaneToSdn], but not [translateForWrite]/[translateForRead] (which
     * require p4info to map action param IDs to URIs).
     */
    fun create(translations: List<TypeTranslation> = emptyList()): TypeTranslator =
      TypeTranslator(buildTables(translations), paramUris = emptyMap())

    /**
     * Creates a TypeTranslator from p4info and translation configurations.
     *
     * Discovers translated types from p4info and maps (actionId, paramId) pairs to URIs, enabling
     * [translateForWrite] and [translateForRead] on P4Runtime messages.
     */
    fun create(p4info: P4Info, translations: List<TypeTranslation> = emptyList()): TypeTranslator {
      val translatedTypes =
        p4info.typeInfo.newTypesMap.filter { (_, spec) -> spec.hasTranslatedType() }

      val paramUris = mutableMapOf<Long, String>()
      for (action in p4info.actionsList) {
        for (param in action.paramsList) {
          if (!param.hasTypeName()) continue
          val typeSpec = translatedTypes[param.typeName.name] ?: continue
          paramUris[paramKey(action.preamble.id, param.id)] = typeSpec.translatedType.uri
        }
      }

      return TypeTranslator(buildTables(translations), paramUris)
    }

    private fun buildTables(
      translations: List<TypeTranslation>
    ): ConcurrentHashMap<String, TranslationTable> {
      val tables = ConcurrentHashMap<String, TranslationTable>()
      for (translation in translations) {
        tables[translation.uri] = TranslationTable.fromProto(translation)
      }
      return tables
    }

    /** Encodes (actionId, paramId) as a single Long for fast lookup. */
    private fun paramKey(actionId: Int, paramId: Int): Long =
      (actionId.toLong() shl 32) or (paramId.toLong() and 0xFFFFFFFFL)
  }
}

/**
 * Bidirectional mapping table for a single translated type (identified by URI).
 *
 * Thread-safe: all mutating operations are synchronized.
 */
internal class TranslationTable(private val autoAllocate: Boolean) {

  // Forward maps: SDN → data-plane.
  private val bitstringForward = mutableMapOf<ByteString, ByteString>()
  private val stringForward = mutableMapOf<String, ByteString>()

  // Reverse map: data-plane → SDN.
  private val reverse = mutableMapOf<ByteString, SdnValue>()

  // Data-plane values claimed by explicit entries (auto-allocator skips these).
  private val reservedValues = mutableSetOf<ByteString>()

  // Counter for sequential auto-allocation.
  private var nextValue = 0

  /** Looks up or auto-allocates a data-plane value for an SDN bitstring. */
  @Synchronized
  fun lookupOrAllocateBitstring(sdnValue: ByteString): ByteString =
    lookupOrAllocate(
      bitstringForward,
      sdnValue,
      SdnValue::Bitstring,
      "No mapping for SDN bitstring value $sdnValue (auto-allocate off)",
    )

  /** Looks up or auto-allocates a data-plane value for an SDN string. */
  @Synchronized
  fun lookupOrAllocateString(sdnStr: String): ByteString =
    lookupOrAllocate(
      stringForward,
      sdnStr,
      SdnValue::Str,
      "No mapping for SDN string '$sdnStr' (auto-allocate off)",
    )

  /** Reverse-translates a data-plane value to its SDN representation. */
  @Synchronized
  fun reverseLookup(dataplaneValue: ByteString): SdnValue =
    reverse[dataplaneValue]
      ?: throw TranslationException("No reverse mapping for data-plane value $dataplaneValue")

  private fun <K> lookupOrAllocate(
    forward: MutableMap<K, ByteString>,
    key: K,
    wrapSdn: (K) -> SdnValue,
    errorMsg: String,
  ): ByteString {
    forward[key]?.let {
      return it
    }
    if (!autoAllocate) throw TranslationException(errorMsg)
    val dp = allocateNext()
    forward[key] = dp
    reverse[dp] = wrapSdn(key)
    return dp
  }

  /** Allocates the next available data-plane value, skipping reserved ones. */
  private fun allocateNext(): ByteString {
    while (true) {
      val candidate = encodeMinWidth(nextValue++)
      if (candidate !in reservedValues) return candidate
    }
  }

  companion object {
    /** Builds a TranslationTable from a proto config, pre-populating explicit entries. */
    fun fromProto(proto: TypeTranslation): TranslationTable {
      val table = TranslationTable(autoAllocate = proto.autoAllocate)
      for (entry in proto.entriesList) {
        val dp = entry.dataplaneValue
        table.reservedValues.add(dp)
        table.reverse[dp] =
          when (entry.sdnValueCase) {
            TranslationEntry.SdnValueCase.SDN_BITSTRING -> {
              table.bitstringForward[entry.sdnBitstring] = dp
              SdnValue.Bitstring(entry.sdnBitstring)
            }
            TranslationEntry.SdnValueCase.SDN_STR -> {
              table.stringForward[entry.sdnStr] = dp
              SdnValue.Str(entry.sdnStr)
            }
            else -> throw IllegalArgumentException("TranslationEntry must have an sdn_value")
          }
      }
      return table
    }
  }
}
