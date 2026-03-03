package fourward.simulator

import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update
import java.math.BigInteger

/**
 * Stores and looks up P4 table entries for all tables in a loaded pipeline.
 *
 * Supports exact, LPM, and ternary match kinds. Range match is a TODO.
 * Entries are stored per-table; lookup returns the highest-priority match.
 */
class TableStore {

    // tableName -> list of entries, ordered by insertion (priority is explicit in the entry)
    private val tables: MutableMap<String, MutableList<TableEntry>> = mutableMapOf()

    // tableName -> default action name (from p4info)
    private val defaultActions: MutableMap<String, String> = mutableMapOf()

    fun setDefaultAction(tableName: String, actionName: String) {
        defaultActions[tableName] = actionName
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    fun write(update: Update, tableNameById: Map<Int, String>, actionNameById: Map<Int, String>) {
        val entry = update.entity.tableEntry
        val tableName = tableNameById[entry.tableId] ?: error("unknown table ID: ${entry.tableId}")

        val entries = tables.getOrPut(tableName) { mutableListOf() }
        when (update.type) {
            Update.Type.INSERT, Update.Type.MODIFY -> {
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
    )

    /**
     * Looks up [keyValues] in [tableName]. Returns the best-matching entry or,
     * on a miss, the default action.
     *
     * For LPM tables, "best match" means the entry with the longest prefix.
     * For ternary tables, "best match" means the entry with the highest priority.
     */
    fun lookup(tableName: String, keyValues: List<Pair<String, Value>>): LookupResult {
        val entries = tables[tableName] ?: emptyList<TableEntry>()
        val defaultAction = defaultActions[tableName] ?: "NoAction"

        data class Candidate(val entry: TableEntry, val score: Long)

        val candidates = entries.mapNotNull { entry ->
            val score = scoreEntry(entry, keyValues) ?: return@mapNotNull null
            Candidate(entry, score)
        }

        val best = candidates.maxByOrNull { it.score }
            ?: return LookupResult(false, null, defaultAction)

        // Resolve action name from the entry's action ID (we don't have p4info here;
        // action names are embedded as strings in the entry's action spec for now).
        // TODO: resolve via actionNameById once the TableStore is wired to p4info.
        val actionName = best.entry.action.action.actionId.toString()  // placeholder
        return LookupResult(true, best.entry, actionName)
    }

    /**
     * Scores an entry against [keyValues]. Returns null if the entry does not
     * match. Returns a non-negative score where a higher value means a better
     * match (used to implement LPM longest-prefix and ternary priority semantics).
     */
    private fun scoreEntry(entry: TableEntry, keyValues: List<Pair<String, Value>>): Long? {
        var score = 0L
        for (match in entry.matchList) {
            val (_, value) = keyValues.find { it.first == match.fieldId.toString() }
                ?: return null  // no key value for this match field

            val bits = (value as? BitVal)?.bits ?: return null

            when {
                match.hasExact() -> {
                    val want = BigInteger(1, match.exact.value.toByteArray())
                    if (bits.value != want) return null
                    score += Long.MAX_VALUE / 2  // exact beats everything
                }
                match.hasLpm() -> {
                    val prefixLen = match.lpm.prefixLen
                    val prefix = BigInteger(1, match.lpm.value.toByteArray())
                    val mask = if (prefixLen == 0) BigInteger.ZERO
                    else BigInteger.ONE.shiftLeft(bits.width - prefixLen).minus(BigInteger.ONE).not()
                        .and(BigInteger.TWO.pow(bits.width).minus(BigInteger.ONE))
                    if (bits.value.and(mask) != prefix.and(mask)) return null
                    score += prefixLen.toLong()
                }
                match.hasTernary() -> {
                    val want = BigInteger(1, match.ternary.value.toByteArray())
                    val mask = BigInteger(1, match.ternary.mask.toByteArray())
                    if (bits.value.and(mask) != want.and(mask)) return null
                    score += entry.priority.toLong()
                }
                else -> return null  // unsupported match kind
            }
        }
        return score
    }
}
