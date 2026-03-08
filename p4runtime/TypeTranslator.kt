package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.TranslationEntry
import fourward.ir.v1.TypeTranslation
import java.util.concurrent.ConcurrentHashMap
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.config.v1.P4Types
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.PacketIn
import p4.v1.P4RuntimeOuterClass.PacketOut
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
 *
 * Translates action parameters, match fields (exact/optional), and PacketIO metadata.
 */
class TypeTranslator
private constructor(
  private val tables: ConcurrentHashMap<String, TranslationTable>,
  private val paramUris: Map<Long, String>,
  private val matchFieldUris: Map<Long, String>,
  // Separate maps per direction: packet_out and packet_in metadata IDs can
  // overlap (both use @id(1), @id(2), …) but refer to different fields.
  private val packetOutMetadataUris: Map<Int, String>,
  private val packetInMetadataUris: Map<Int, String>,
) {

  /** True if this translator has any translated types to handle. */
  val hasTranslations: Boolean =
    tables.isNotEmpty() ||
      paramUris.isNotEmpty() ||
      matchFieldUris.isNotEmpty() ||
      packetOutMetadataUris.isNotEmpty() ||
      packetInMetadataUris.isNotEmpty()

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
  // P4Runtime Write/Read translation
  // ---------------------------------------------------------------------------

  /** Translates a Write update from SDN to data-plane representation. */
  fun translateForWrite(update: Update): Update {
    if (!update.entity.hasTableEntry()) return update
    val translated =
      translateTableEntry(update.entity.tableEntry, toDataplane = true) ?: return update
    return update.toBuilder().setEntity(update.entity.toBuilder().setTableEntry(translated)).build()
  }

  /** Translates a Read entity from data-plane to SDN representation. */
  fun translateForRead(entity: Entity): Entity {
    if (!entity.hasTableEntry()) return entity
    val translated = translateTableEntry(entity.tableEntry, toDataplane = false) ?: return entity
    return entity.toBuilder().setTableEntry(translated).build()
  }

  /**
   * Translates match field values and action parameter values in a table entry. Returns null if
   * nothing was translated.
   */
  private fun translateTableEntry(
    entry: p4.v1.P4RuntimeOuterClass.TableEntry,
    toDataplane: Boolean,
  ): p4.v1.P4RuntimeOuterClass.TableEntry? {
    val translatedMatches = translateMatchFields(entry.tableId, entry.matchList, toDataplane)
    val translatedAction = translateTableAction(entry.action, toDataplane)
    if (translatedMatches == null && translatedAction == null) return null
    val builder = entry.toBuilder()
    if (translatedMatches != null) {
      builder.clearMatch().addAllMatch(translatedMatches)
    }
    if (translatedAction != null) {
      builder.setAction(translatedAction)
    }
    return builder.build()
  }

  // ---------------------------------------------------------------------------
  // PacketIO metadata translation
  // ---------------------------------------------------------------------------

  /** Translates PacketOut metadata from SDN to data-plane representation. */
  fun translatePacketOut(packetOut: PacketOut): PacketOut {
    if (packetOutMetadataUris.isEmpty()) return packetOut
    val translated =
      translateMetadata(packetOutMetadataUris, packetOut.metadataList, toDataplane = true)
        ?: return packetOut
    return packetOut.toBuilder().clearMetadata().addAllMetadata(translated).build()
  }

  /**
   * Translates PacketIn metadata from data-plane to SDN representation.
   *
   * Lenient: metadata values without a reverse mapping (e.g. the CPU port, which the controller
   * never forward-allocated) are passed through unchanged.
   */
  fun translatePacketIn(packetIn: PacketIn): PacketIn {
    if (packetInMetadataUris.isEmpty()) return packetIn
    val translated =
      translateMetadata(
        packetInMetadataUris,
        packetIn.metadataList,
        toDataplane = false,
        lenient = true,
      ) ?: return packetIn
    return packetIn.toBuilder().clearMetadata().addAllMetadata(translated).build()
  }

  /**
   * Translates action params within a [TableAction], handling direct actions and one-shot action
   * profile action sets.
   */
  private fun translateTableAction(
    tableAction: p4.v1.P4RuntimeOuterClass.TableAction,
    toDataplane: Boolean,
  ): p4.v1.P4RuntimeOuterClass.TableAction? {
    if (tableAction.hasAction()) {
      val translated = translateAction(tableAction.action, toDataplane) ?: return null
      return tableAction.toBuilder().setAction(translated).build()
    }
    if (tableAction.hasActionProfileActionSet()) {
      var changed = false
      val translatedActions =
        tableAction.actionProfileActionSet.actionProfileActionsList.map { profileAction ->
          val translated = translateAction(profileAction.action, toDataplane)
          if (translated != null) {
            changed = true
            profileAction.toBuilder().setAction(translated).build()
          } else {
            profileAction
          }
        }
      if (!changed) return null
      return tableAction
        .toBuilder()
        .setActionProfileActionSet(
          tableAction.actionProfileActionSet
            .toBuilder()
            .clearActionProfileActions()
            .addAllActionProfileActions(translatedActions)
        )
        .build()
    }
    return null
  }

  /** Translates params of a single [Action], returning null if no translation was needed. */
  private fun translateAction(
    action: p4.v1.P4RuntimeOuterClass.Action,
    toDataplane: Boolean,
  ): p4.v1.P4RuntimeOuterClass.Action? {
    val translated = translateParams(action.actionId, action.paramsList, toDataplane) ?: return null
    return action.toBuilder().clearParams().addAllParams(translated).build()
  }

  // ---------------------------------------------------------------------------
  // Internal translation helpers
  // ---------------------------------------------------------------------------

  private fun translateParams(
    actionId: Int,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param>,
    toDataplane: Boolean,
  ): List<p4.v1.P4RuntimeOuterClass.Action.Param>? {
    var changed = false
    val result =
      params.map { param ->
        val uri = paramUris[packKey(actionId, param.paramId)]
        if (uri != null) {
          changed = true
          val translated = translateValue(getOrCreateTable(uri), param.value, toDataplane)
          param.toBuilder().setValue(translated).build()
        } else {
          param
        }
      }
    return if (changed) result else null
  }

  private fun translateMatchFields(
    tableId: Int,
    matches: List<p4.v1.P4RuntimeOuterClass.FieldMatch>,
    toDataplane: Boolean,
  ): List<p4.v1.P4RuntimeOuterClass.FieldMatch>? {
    var changed = false
    val result =
      matches.map { match ->
        val uri = matchFieldUris[packKey(tableId, match.fieldId)]
        if (uri != null) {
          changed = true
          val table = getOrCreateTable(uri)
          when {
            match.hasExact() -> {
              val translated = translateValue(table, match.exact.value, toDataplane)
              match
                .toBuilder()
                .setExact(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(translated)
                )
                .build()
            }
            match.hasOptional() -> {
              val translated = translateValue(table, match.optional.value, toDataplane)
              match
                .toBuilder()
                .setOptional(
                  p4.v1.P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(translated)
                )
                .build()
            }
            // Ternary/LPM/Range on translated types is nonsensical — pass through.
            else -> match
          }
        } else {
          match
        }
      }
    return if (changed) result else null
  }

  private fun translateMetadata(
    uriMap: Map<Int, String>,
    metadata: List<p4.v1.P4RuntimeOuterClass.PacketMetadata>,
    toDataplane: Boolean,
    lenient: Boolean = false,
  ): List<p4.v1.P4RuntimeOuterClass.PacketMetadata>? {
    var changed = false
    val result =
      metadata.map { meta ->
        val uri = uriMap[meta.metadataId]
        if (uri != null) {
          val translated =
            if (lenient) {
              try {
                translateValue(getOrCreateTable(uri), meta.value, toDataplane)
              } catch (_: TranslationException) {
                null
              }
            } else {
              translateValue(getOrCreateTable(uri), meta.value, toDataplane)
            }
          if (translated != null) {
            changed = true
            meta.toBuilder().setValue(translated).build()
          } else {
            meta
          }
        } else {
          meta
        }
      }
    return if (changed) result else null
  }

  /**
   * Translates a single ByteString value forward (SDN→DP) or reverse (DP→SDN).
   *
   * For `sdn_string` tables, the SDN value is a UTF-8 string encoded in the proto `bytes` field
   * (per P4Runtime spec §8.3 — there is no separate string field).
   */
  private fun translateValue(
    table: TranslationTable,
    value: ByteString,
    toDataplane: Boolean,
  ): ByteString =
    if (toDataplane) {
      if (table.isStringType) {
        table.lookupOrAllocateString(value.toStringUtf8())
      } else {
        table.lookupOrAllocateBitstring(value)
      }
    } else {
      when (val sdnValue = table.reverseLookup(value)) {
        is SdnValue.Bitstring -> sdnValue.value
        is SdnValue.Str -> ByteString.copyFromUtf8(sdnValue.value)
      }
    }

  companion object {
    /**
     * Creates a TypeTranslator from translation configurations.
     *
     * For use without p4info — the translator supports direct URI-based lookups via
     * [sdnToDataplane] and [dataplaneToSdn], but not message-level translation methods (which
     * require p4info to map field IDs to URIs).
     */
    fun create(translations: List<TypeTranslation> = emptyList()): TypeTranslator =
      TypeTranslator(
        buildTables(translations),
        paramUris = emptyMap(),
        matchFieldUris = emptyMap(),
        packetOutMetadataUris = emptyMap(),
        packetInMetadataUris = emptyMap(),
      )

    /**
     * Creates a TypeTranslator from p4info and translation configurations.
     *
     * Discovers translated types from p4info and maps field IDs to URIs, enabling translation of
     * action parameters, match fields, and PacketIO metadata in P4Runtime messages.
     */
    fun create(p4info: P4Info, translations: List<TypeTranslation> = emptyList()): TypeTranslator {
      val translatedTypes =
        p4info.typeInfo.newTypesMap.filter { (_, spec) -> spec.hasTranslatedType() }

      val paramUris = buildParamUris(p4info, translatedTypes)
      val matchFieldUris = buildMatchFieldUris(p4info, translatedTypes)
      val (packetOutMetadataUris, packetInMetadataUris) =
        buildPacketMetadataUris(p4info, translatedTypes)

      val stringUris =
        translatedTypes.values
          .filter { it.translatedType.hasSdnString() }
          .mapTo(mutableSetOf()) { it.translatedType.uri }

      val tables = buildTables(translations, stringUris)
      // Pre-create tables for string URIs that have no translation config,
      // so getOrCreateTable finds them with the correct isStringType.
      for (uri in stringUris) {
        tables.computeIfAbsent(uri) { TranslationTable(autoAllocate = true, isStringType = true) }
      }

      return TypeTranslator(
        tables,
        paramUris,
        matchFieldUris,
        packetOutMetadataUris,
        packetInMetadataUris,
      )
    }

    private fun buildParamUris(
      p4info: P4Info,
      translatedTypes: Map<String, P4Types.P4NewTypeSpec>,
    ): Map<Long, String> {
      val uris = mutableMapOf<Long, String>()
      for (action in p4info.actionsList) {
        for (param in action.paramsList) {
          if (!param.hasTypeName()) continue
          val typeSpec = translatedTypes[param.typeName.name] ?: continue
          uris[packKey(action.preamble.id, param.id)] = typeSpec.translatedType.uri
        }
      }
      return uris
    }

    private fun buildMatchFieldUris(
      p4info: P4Info,
      translatedTypes: Map<String, P4Types.P4NewTypeSpec>,
    ): Map<Long, String> {
      val uris = mutableMapOf<Long, String>()
      for (table in p4info.tablesList) {
        for (matchField in table.matchFieldsList) {
          if (!matchField.hasTypeName()) continue
          val typeSpec = translatedTypes[matchField.typeName.name] ?: continue
          uris[packKey(table.preamble.id, matchField.id)] = typeSpec.translatedType.uri
        }
      }
      return uris
    }

    /**
     * Builds per-direction metadata URI maps. IDs can overlap between packet_out and packet_in
     * (both start @id(1)), so a flat map would cause untranslated fields (like submit_to_ingress)
     * to be incorrectly translated.
     */
    private fun buildPacketMetadataUris(
      p4info: P4Info,
      translatedTypes: Map<String, P4Types.P4NewTypeSpec>,
    ): Pair<Map<Int, String>, Map<Int, String>> {
      val packetOut = mutableMapOf<Int, String>()
      val packetIn = mutableMapOf<Int, String>()
      for (controllerMeta in p4info.controllerPacketMetadataList) {
        val target =
          when (controllerMeta.preamble.name) {
            "packet_out" -> packetOut
            "packet_in" -> packetIn
            else -> continue
          }
        for (metadata in controllerMeta.metadataList) {
          if (!metadata.hasTypeName()) continue
          val typeSpec = translatedTypes[metadata.typeName.name] ?: continue
          target[metadata.id] = typeSpec.translatedType.uri
        }
      }
      return packetOut to packetIn
    }

    private fun buildTables(
      translations: List<TypeTranslation>,
      stringUris: Set<String> = emptySet(),
    ): ConcurrentHashMap<String, TranslationTable> {
      val tables = ConcurrentHashMap<String, TranslationTable>()
      for (translation in translations) {
        tables[translation.uri] =
          TranslationTable.fromProto(translation, isStringType = translation.uri in stringUris)
      }
      return tables
    }

    /** Packs two IDs into a single Long for fast compound-key lookup. */
    private fun packKey(high: Int, low: Int): Long =
      (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
  }
}

/**
 * Bidirectional mapping table for a single translated type (identified by URI).
 *
 * Thread-safe: all mutating operations are synchronized.
 */
internal class TranslationTable(
  private val autoAllocate: Boolean,
  /** True if this table's SDN values are strings (UTF-8 encoded in proto bytes fields). */
  val isStringType: Boolean = false,
) {

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
    fun fromProto(proto: TypeTranslation, isStringType: Boolean = false): TranslationTable {
      val table = TranslationTable(autoAllocate = proto.autoAllocate, isStringType = isStringType)
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
