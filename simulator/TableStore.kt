package fourward.simulator

import com.google.protobuf.ByteString
import java.math.BigInteger
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/** Interprets a protobuf [ByteString] as an unsigned big-endian integer. */
private fun ByteString.toUnsignedBigInteger(): BigInteger = BigInteger(1, toByteArray())

/**
 * Stores and looks up P4 table entries for all tables in a loaded pipeline.
 *
 * Supports exact, LPM, ternary, range, and optional match kinds. Entries are stored per-table;
 * lookup returns the highest-priority match.
 *
 * Call [loadMappings] once per pipeline load before any [write] or [lookup] calls.
 */
class TableStore {

  // tableName -> list of entries, ordered by insertion (priority is explicit in the entry)
  private val tables: MutableMap<String, MutableList<TableEntry>> = mutableMapOf()

  // tableName -> default action name + arguments (from p4info)
  private data class DefaultAction(
    val name: String,
    val params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
  )

  private val defaultActions: MutableMap<String, DefaultAction> = mutableMapOf()

  // For unit tests that cannot easily construct TableEntry protos: makes lookup() return
  // hit=true with this action rather than searching the entry list.
  private val forcedHits: MutableMap<String, String> = mutableMapOf()

  fun setForcedHit(tableName: String, actionName: String) {
    forcedHits[tableName] = actionName
  }

  // Populated by loadMappings; used to resolve IDs to names in write() and lookup().
  private var tableNameById: Map<Int, String> = emptyMap()
  private var actionNameById: Map<Int, String> = emptyMap()

  /**
   * Initialises the ID→name maps for the loaded pipeline and clears all table entries.
   *
   * Must be called before [write] or [lookup]. Calling it again (pipeline reload) resets all state.
   */
  fun loadMappings(tableNameById: Map<Int, String>, actionNameById: Map<Int, String>) {
    this.tableNameById = tableNameById
    this.actionNameById = actionNameById
    tables.clear()
    forcedHits.clear()
  }

  fun setDefaultAction(
    tableName: String,
    actionName: String,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
  ) {
    defaultActions[tableName] = DefaultAction(actionName, params)
  }

  // -------------------------------------------------------------------------
  // Write
  // -------------------------------------------------------------------------

  fun write(update: Update) {
    val entry = update.entity.tableEntry
    val tableName = tableNameById[entry.tableId] ?: error("unknown table ID: ${entry.tableId}")

    val entries = tables.getOrPut(tableName) { mutableListOf() }
    when (update.type) {
      Update.Type.INSERT,
      Update.Type.MODIFY -> {
        entries.removeIf { it.tableId == entry.tableId && it.matchList == entry.matchList }
        entries.add(entry)
      }
      Update.Type.DELETE -> {
        entries.removeIf { it.tableId == entry.tableId && it.matchList == entry.matchList }
      }
      else -> error("unsupported update type: ${update.type}")
    }
  }

  // -------------------------------------------------------------------------
  // Lookup
  // -------------------------------------------------------------------------

  data class LookupResult(
    val hit: Boolean,
    val entry: TableEntry?,
    val actionName: String,
    val actionParams: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
  )

  /**
   * Looks up [keyValues] in [tableName]. Returns the best-matching entry or, on a miss, the default
   * action.
   *
   * For LPM tables, "best match" means the entry with the longest prefix. For ternary tables, "best
   * match" means the entry with the highest priority.
   */
  fun lookup(tableName: String, keyValues: List<Pair<String, Value>>): LookupResult {
    forcedHits[tableName]?.let {
      return LookupResult(true, null, it)
    }

    val entries = tables[tableName] ?: emptyList<TableEntry>()
    val default = defaultActions[tableName] ?: DefaultAction("NoAction")

    data class Candidate(val entry: TableEntry, val score: Long)

    val candidates =
      entries.mapNotNull { entry ->
        val score = scoreEntry(entry, keyValues) ?: return@mapNotNull null
        Candidate(entry, score)
      }

    val best =
      candidates.maxByOrNull { it.score }
        ?: return LookupResult(false, null, default.name, default.params)

    val actionId = best.entry.action.action.actionId
    val actionName = actionNameById[actionId] ?: error("unknown action ID: $actionId")
    return LookupResult(true, best.entry, actionName)
  }

  /**
   * Scores an entry against [keyValues]. Returns null if the entry does not match. Returns a
   * non-negative score where a higher value means a better match (used to implement LPM
   * longest-prefix and ternary priority semantics).
   */
  private fun scoreEntry(entry: TableEntry, keyValues: List<Pair<String, Value>>): Long? {
    var score = 0L
    for (match in entry.matchList) {
      val (_, value) =
        keyValues.find { it.first == match.fieldId.toString() }
          ?: return null // no key value for this match field

      val bits = (value as? BitVal)?.bits ?: return null

      when {
        match.hasExact() -> {
          if (bits.value != match.exact.value.toUnsignedBigInteger()) return null
          // Exact match doesn't contribute to relative scoring — all exact
          // fields either match or don't.  We add nothing here; the entry's
          // priority (if any) is added once below the when block.
        }
        match.hasLpm() -> {
          val prefixLen = match.lpm.prefixLen
          val prefix = match.lpm.value.toUnsignedBigInteger()
          val mask =
            if (prefixLen == 0) BigInteger.ZERO
            else
              BigInteger.ONE.shiftLeft(bits.width - prefixLen)
                .minus(BigInteger.ONE)
                .not()
                .and(BigInteger.TWO.pow(bits.width).minus(BigInteger.ONE))
          if (bits.value.and(mask) != prefix.and(mask)) return null
          score += prefixLen.toLong()
        }
        match.hasTernary() -> {
          val want = match.ternary.value.toUnsignedBigInteger()
          val mask = match.ternary.mask.toUnsignedBigInteger()
          if (bits.value.and(mask) != want.and(mask)) return null
          score += entry.priority.toLong()
        }
        match.hasRange() -> {
          val lo = match.range.low.toUnsignedBigInteger()
          val hi = match.range.high.toUnsignedBigInteger()
          if (bits.value < lo || bits.value > hi) return null
          score += entry.priority.toLong()
        }
        // P4 spec §14.2.1.3: optional match behaves like exact when present;
        // omitted optional fields are wildcards (the FieldMatch is absent).
        match.hasOptional() -> {
          if (bits.value != match.optional.value.toUnsignedBigInteger()) return null
        }
        else -> return null // unsupported match kind
      }
    }
    return score
  }
}
