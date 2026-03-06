package fourward.p4runtime

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
 * Constructed once per pipeline load; call [validate] for each update before type translation so
 * SDN-visible values are checked at canonical widths (§8.3).
 */
class WriteValidator(p4Info: P4InfoOuterClass.P4Info) {

  private val tableInfoById = p4Info.tablesList.associateBy { it.preamble.id }
  private val actionInfoById = p4Info.actionsList.associateBy { it.preamble.id }

  /** Validates a table entry update. Throws [StatusException] on spec violations. */
  fun validate(update: P4RuntimeOuterClass.Update) {
    val entry = update.entity.tableEntry
    val tableInfo =
      tableInfoById[entry.tableId] ?: throw notFound("unknown table ID: ${entry.tableId}")

    // P4Runtime spec §9.1: DELETE only needs the match key; skip content validation.
    if (update.type == P4RuntimeOuterClass.Update.Type.DELETE) return

    if (entry.hasAction() && entry.action.hasAction()) {
      validateAction(entry.action.action, tableInfo)
    }
    validateMatchFields(entry, tableInfo)
    validatePriority(entry, tableInfo)
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

    if (tableInfo.actionRefsList.none { it.id == action.actionId }) {
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
    val paramInfoById = expected.associateBy { it.id }
    for (param in action.paramsList) {
      val paramInfo =
        paramInfoById[param.paramId]
          ?: throw invalidArg(
            "unknown param ID ${param.paramId} for action '${actionInfo.preamble.name}'"
          )
      // §8.3: canonical byte width. Skip if bitwidth is 0
      // (e.g. @p4runtime_translation with sdn_string).
      checkWidth(paramInfo.bitwidth, param.value.size(), "param '${paramInfo.name}'")
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

    val fieldInfoById = expectedFields.associateBy { it.id }
    for (fm in entry.matchList) {
      val fieldInfo =
        fieldInfoById[fm.fieldId]
          ?: throw invalidArg(
            "unknown match field ID ${fm.fieldId} in table '${tableInfo.tableName}'"
          )
      validateMatchKind(fm, fieldInfo)
      validateMatchWidth(fm, fieldInfo)
    }

    // EXACT fields must be present; omission is not allowed.
    for (fieldInfo in expectedFields) {
      if (
        fieldInfo.matchType == P4InfoOuterClass.MatchField.MatchType.EXACT &&
          entry.matchList.none { it.fieldId == fieldInfo.id }
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
      fm.hasExact() -> checkWidth(w, fm.exact.value.size(), "match field '$f' value")
      fm.hasTernary() -> {
        checkWidth(w, fm.ternary.value.size(), "match field '$f' value")
        checkWidth(w, fm.ternary.mask.size(), "match field '$f' mask")
      }
      fm.hasLpm() -> checkWidth(w, fm.lpm.value.size(), "match field '$f' value")
      fm.hasRange() -> {
        checkWidth(w, fm.range.low.size(), "match field '$f' low")
        checkWidth(w, fm.range.high.size(), "match field '$f' high")
      }
      fm.hasOptional() -> checkWidth(w, fm.optional.value.size(), "match field '$f' value")
    }
  }

  // ---------------------------------------------------------------------------
  // Priority validation (§9.1.1)
  // ---------------------------------------------------------------------------

  private fun validatePriority(
    entry: P4RuntimeOuterClass.TableEntry,
    tableInfo: P4InfoOuterClass.Table,
  ) {
    val hasPriorityFields = tableInfo.matchFieldsList.any { it.matchType in PRIORITY_MATCH_TYPES }
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

  companion object {
    private val PRIORITY_MATCH_TYPES =
      setOf(
        P4InfoOuterClass.MatchField.MatchType.TERNARY,
        P4InfoOuterClass.MatchField.MatchType.RANGE,
        P4InfoOuterClass.MatchField.MatchType.OPTIONAL,
      )

    /** §8.3: values must be exactly ceil(bitwidth/8) bytes. Skips if bitwidth is 0. */
    private fun checkWidth(bitwidth: Int, actual: Int, label: String) {
      val expected = (bitwidth + 7) / 8
      if (expected > 0 && actual != expected) {
        throw invalidArg("$label expects $expected bytes, got $actual")
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
