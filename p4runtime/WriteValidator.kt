package fourward.p4runtime

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Validates P4Runtime Write updates against the p4info schema.
 *
 * Enforces P4Runtime spec §9.1: action IDs/refs, action parameter count/IDs/byte widths, match
 * field IDs/kinds/byte widths, required exact fields, and priority rules.
 *
 * All lookup maps are pre-computed at construction time so [validate] does zero allocations.
 * Constructed once per pipeline load; call [validate] for each update before type translation so
 * SDN-visible values are checked for valid P4Runtime byte-string encodings (§8.3).
 */
class WriteValidator(p4Info: P4InfoOuterClass.P4Info, private val strict: Boolean = false) {

  private val tableInfoById = p4Info.tablesList.associateBy { it.preamble.id }
  private val actionInfoById = p4Info.actionsList.associateBy { it.preamble.id }

  // Pre-computed per-table: valid action IDs, match field lookup, priority/const.
  private val constTableIds: Set<Int> =
    p4Info.tablesList.filter { it.isConstTable }.map { it.preamble.id }.toSet()

  private val actionRefIdsByTable: Map<Int, Set<Int>> =
    p4Info.tablesList.associate { it.preamble.id to it.actionRefsList.map { r -> r.id }.toSet() }

  private val fieldInfoByTable: Map<Int, Map<Int, P4InfoOuterClass.MatchField>> =
    p4Info.tablesList.associate { it.preamble.id to it.matchFieldsList.associateBy { f -> f.id } }

  private val tableRequiresPriority: Map<Int, Boolean> =
    p4Info.tablesList.associate {
      it.preamble.id to it.matchFieldsList.any { f -> f.matchType in PRIORITY_MATCH_TYPES }
    }

  // Pre-computed per-action: param lookup.
  private val paramInfoByAction: Map<Int, Map<Int, P4InfoOuterClass.Action.Param>> =
    p4Info.actionsList.associate { it.preamble.id to it.paramsList.associateBy { p -> p.id } }

  /** Validates a table entry update. Throws [StatusException] on spec violations. */
  fun validate(update: P4RuntimeOuterClass.Update) {
    val entry = update.entity.tableEntry
    val tableInfo =
      tableInfoById[entry.tableId] ?: throw notFound("unknown table ID: ${entry.tableId}")

    // §9.1: const tables are immutable — no writes allowed.
    if (entry.tableId in constTableIds) {
      throw invalidArg("table '${tableInfo.tableName}' is const; writes are not allowed")
    }

    if (entry.isDefaultAction) {
      validateDefaultEntry(update, entry, tableInfo)
      return
    }

    // P4Runtime spec §9.1: DELETE only needs the match key; skip content validation.
    if (update.type == P4RuntimeOuterClass.Update.Type.DELETE) return

    if (entry.hasAction()) {
      val tableAction = entry.action
      when {
        tableAction.hasAction() -> validateAction(tableAction.action, tableInfo)
        // P4Runtime spec §9.2.3: validate each action in a one-shot action set.
        tableAction.hasActionProfileActionSet() ->
          for (profileAction in tableAction.actionProfileActionSet.actionProfileActionsList) {
            validateAction(profileAction.action, tableInfo)
          }
      }
    }
    validateMatchFields(entry, tableInfo)
    validatePriority(entry, tableInfo)
  }

  // ---------------------------------------------------------------------------
  // Default entry validation (§9.1)
  // ---------------------------------------------------------------------------

  private fun validateDefaultEntry(
    update: P4RuntimeOuterClass.Update,
    entry: P4RuntimeOuterClass.TableEntry,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val name = tableInfo.tableName
    // §9.1: default entries only support MODIFY.
    if (update.type != P4RuntimeOuterClass.Update.Type.MODIFY) {
      throw invalidArg("default entry for table '$name' only supports MODIFY, got ${update.type}")
    }
    // §9.1: default entries must not have match fields.
    if (entry.matchCount > 0) {
      throw invalidArg("default entry for table '$name' must not have match fields")
    }
    if (entry.hasAction() && entry.action.hasAction()) {
      validateAction(entry.action.action, tableInfo)
    }
  }

  // ---------------------------------------------------------------------------
  // Action validation (§9.1.2)
  // ---------------------------------------------------------------------------

  private fun validateAction(
    action: P4RuntimeOuterClass.Action,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val actionInfo =
      actionInfoById[action.actionId] ?: throw invalidArg("unknown action ID: ${action.actionId}")

    if (action.actionId !in (actionRefIdsByTable[tableInfo.preamble.id] ?: emptySet())) {
      throw invalidArg(
        "action ID ${action.actionId} is not valid for table '${tableInfo.tableName}'"
      )
    }

    validateActionParams(action, actionInfo)
  }

  private fun validateActionParams(
    action: P4RuntimeOuterClass.Action,
    actionInfo: P4InfoOuterClass.Action,
  ) {
    val expected = actionInfo.paramsList
    if (action.paramsCount != expected.size) {
      throw invalidArg(
        "action '${actionInfo.preamble.name}' expects ${expected.size} params, " +
          "got ${action.paramsCount}"
      )
    }
    val paramLookup = paramInfoByAction[action.actionId] ?: return
    for (param in action.paramsList) {
      val paramInfo =
        paramLookup[param.paramId]
          ?: throw invalidArg(
            "unknown param ID ${param.paramId} for action '${actionInfo.preamble.name}'"
          )
      checkWidth(paramInfo.bitwidth, param.value, "param '${paramInfo.name}'")
    }
  }

  // ---------------------------------------------------------------------------
  // Match field validation (§9.1.1)
  // ---------------------------------------------------------------------------

  private fun validateMatchFields(
    entry: P4RuntimeOuterClass.TableEntry,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val expectedFields = tableInfo.matchFieldsList
    if (expectedFields.isEmpty()) return

    val fieldLookup = fieldInfoByTable[tableInfo.preamble.id] ?: return
    val presentFieldIds = mutableSetOf<Int>()
    for (fm in entry.matchList) {
      val fieldInfo =
        fieldLookup[fm.fieldId]
          ?: throw invalidArg(
            "unknown match field ID ${fm.fieldId} in table '${tableInfo.tableName}'"
          )
      // §9.1: each match field may appear at most once.
      if (!presentFieldIds.add(fm.fieldId)) {
        throw invalidArg("duplicate match field ID ${fm.fieldId} in table '${tableInfo.tableName}'")
      }
      validateMatchKind(fm, fieldInfo)
      validateMatchWidth(fm, fieldInfo)
    }

    // EXACT fields must be present; omission is not allowed.
    for (fieldInfo in expectedFields) {
      if (
        fieldInfo.matchType == P4InfoOuterClass.MatchField.MatchType.EXACT &&
          fieldInfo.id !in presentFieldIds
      ) {
        throw invalidArg(
          "missing required exact match field '${fieldInfo.name}' in table '${tableInfo.tableName}'"
        )
      }
    }
  }

  private fun validateMatchKind(
    fm: P4RuntimeOuterClass.FieldMatch,
    fieldInfo: P4InfoOuterClass.MatchField,
  ) {
    val actual =
      when {
        fm.hasExact() -> P4InfoOuterClass.MatchField.MatchType.EXACT
        fm.hasTernary() -> P4InfoOuterClass.MatchField.MatchType.TERNARY
        fm.hasLpm() -> P4InfoOuterClass.MatchField.MatchType.LPM
        fm.hasRange() -> P4InfoOuterClass.MatchField.MatchType.RANGE
        fm.hasOptional() -> P4InfoOuterClass.MatchField.MatchType.OPTIONAL
        else -> throw invalidArg("match field '${fieldInfo.name}' has no value set")
      }
    if (actual != fieldInfo.matchType) {
      throw invalidArg(
        "match field '${fieldInfo.name}' expects ${fieldInfo.matchType.name} but got ${actual.name}"
      )
    }
  }

  private fun validateMatchWidth(
    fm: P4RuntimeOuterClass.FieldMatch,
    fieldInfo: P4InfoOuterClass.MatchField,
  ) {
    val w = fieldInfo.bitwidth
    val f = fieldInfo.name
    when {
      fm.hasExact() -> checkWidth(w, fm.exact.value, "match field '$f' value")
      fm.hasTernary() -> {
        checkWidth(w, fm.ternary.value, "match field '$f' value")
        checkWidth(w, fm.ternary.mask, "match field '$f' mask")
        // §8.3: bits where mask is 0 must also be 0 in value.
        checkTernaryMaskedBits(fm.ternary.value, fm.ternary.mask, f)
      }
      fm.hasLpm() -> {
        checkWidth(w, fm.lpm.value, "match field '$f' value")
        // §8.3: bits beyond prefix_len must be zero.
        checkLpmTrailingBits(fm.lpm.value, fm.lpm.prefixLen, f)
      }
      fm.hasRange() -> {
        checkWidth(w, fm.range.low, "match field '$f' low")
        checkWidth(w, fm.range.high, "match field '$f' high")
      }
      fm.hasOptional() -> checkWidth(w, fm.optional.value, "match field '$f' value")
    }
  }

  // ---------------------------------------------------------------------------
  // Priority validation (§9.1.1)
  // ---------------------------------------------------------------------------

  private fun validatePriority(
    entry: P4RuntimeOuterClass.TableEntry,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val hasPriorityFields = tableRequiresPriority[tableInfo.preamble.id] ?: false
    val name = tableInfo.tableName
    if (hasPriorityFields && entry.priority <= 0) {
      throw invalidArg(
        "table '$name' requires priority > 0 (has ternary/range/optional match fields)"
      )
    }
    if (!hasPriorityFields && entry.priority != 0) {
      throw invalidArg("table '$name' must have priority == 0 (exact/LPM match only)")
    }
  }

  /**
   * §8.3: values are big-endian byte strings. Leading zero bytes may be omitted, and strict mode
   * requires that they be omitted.
   */
  private fun checkWidth(bitwidth: Int, value: ByteString, label: String) {
    val expected = (bitwidth + 7) / 8
    if (expected == 0) return
    val actual = value.size()
    if (actual == 0 || actual > expected) {
      throw invalidArg("$label expects 1..$expected bytes, got $actual")
    }
    if (strict && actual > 1 && value.byteAt(0).toInt() == 0) {
      throw invalidArg("$label must use canonical minimal-width encoding in strict mode")
    }
  }

  companion object {
    private val PRIORITY_MATCH_TYPES =
      setOf(
        P4InfoOuterClass.MatchField.MatchType.TERNARY,
        P4InfoOuterClass.MatchField.MatchType.RANGE,
        P4InfoOuterClass.MatchField.MatchType.OPTIONAL,
      )

    /** §8.3: ternary value bits must be zero where the mask is zero. */
    private fun checkTernaryMaskedBits(value: ByteString, mask: ByteString, fieldName: String) {
      for (i in 0 until value.size()) {
        val v = value.byteAt(i).toInt() and 0xFF
        val m = mask.byteAt(i).toInt() and 0xFF
        if (v and m != v) {
          throw invalidArg(
            "match field '$fieldName' has masked-off bits set in value (value & ~mask != 0)"
          )
        }
      }
    }

    /** §8.3: LPM value bits beyond prefix_len must be zero. */
    private fun checkLpmTrailingBits(value: ByteString, prefixLen: Int, fieldName: String) {
      for (i in 0 until value.size()) {
        val bitStart = i * 8
        val b = value.byteAt(i).toInt() and 0xFF
        if (bitStart >= prefixLen) {
          if (b != 0) {
            throw invalidArg(
              "match field '$fieldName' has non-zero bits beyond prefix length $prefixLen"
            )
          }
        } else if (bitStart + 8 > prefixLen) {
          // Partial byte: only the high (prefixLen - bitStart) bits are valid.
          val trailingBits = 8 - (prefixLen - bitStart)
          val trailingMask = (1 shl trailingBits) - 1
          if (b and trailingMask != 0) {
            throw invalidArg(
              "match field '$fieldName' has non-zero bits beyond prefix length $prefixLen"
            )
          }
        }
      }
    }

    private val P4InfoOuterClass.Table.tableName: String
      get() = preamble.alias.ifEmpty { preamble.name }

    private fun invalidArg(msg: String): StatusException =
      Status.INVALID_ARGUMENT.withDescription(msg).asException()

    private fun notFound(msg: String): StatusException =
      Status.NOT_FOUND.withDescription(msg).asException()
  }
}
