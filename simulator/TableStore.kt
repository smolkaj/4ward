package fourward.simulator

import com.google.protobuf.ByteString
import java.math.BigInteger
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/** Interprets a protobuf [ByteString] as an unsigned big-endian integer. */
private fun ByteString.toUnsignedBigInteger(): BigInteger = BigInteger(1, toByteArray())

/** Result of a [TableStore.write] operation. */
sealed class WriteResult {
  data object Success : WriteResult()

  data class AlreadyExists(val message: String) : WriteResult()

  data class NotFound(val message: String) : WriteResult()

  data class InvalidArgument(val message: String) : WriteResult()
}

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

  // registerName -> index -> stored value (persists across packets)
  private val registers: MutableMap<String, MutableMap<Int, Value>> = mutableMapOf()

  // For unit tests that cannot easily construct TableEntry protos: makes lookup() return
  // hit=true with this action rather than searching the entry list.
  private val forcedHits: MutableMap<String, String> = mutableMapOf()

  // Action profile storage: action_profile_id → member_id → ActionProfileMember
  private val profileMembers:
    MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileMember>> =
    mutableMapOf()

  // Action profile storage: action_profile_id → group_id → ActionProfileGroup
  private val profileGroups:
    MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileGroup>> =
    mutableMapOf()

  // tableName → action_profile_id (populated from p4info at load time)
  private val tableActionProfile: MutableMap<String, Int> = mutableMapOf()

  // PRE (Packet Replication Engine) storage
  private val cloneSessions: MutableMap<Int, P4RuntimeOuterClass.CloneSessionEntry> = mutableMapOf()
  private val multicastGroups: MutableMap<Int, P4RuntimeOuterClass.MulticastGroupEntry> =
    mutableMapOf()

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
  fun loadMappings(
    tableNameById: Map<Int, String>,
    actionNameById: Map<Int, String>,
    p4infoTables: List<P4InfoOuterClass.Table> = emptyList(),
  ) {
    this.tableNameById = tableNameById
    this.actionNameById = actionNameById
    tables.clear()
    forcedHits.clear()
    registers.clear()
    profileMembers.clear()
    profileGroups.clear()
    tableActionProfile.clear()
    cloneSessions.clear()
    multicastGroups.clear()

    // Register which tables use action profiles (implementation_id != 0).
    for (table in p4infoTables) {
      if (table.implementationId != 0) {
        val tableName = tableNameById[table.preamble.id] ?: continue
        tableActionProfile[tableName] = table.implementationId
      }
    }
  }

  fun setDefaultAction(
    tableName: String,
    actionName: String,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
  ) {
    defaultActions[tableName] = DefaultAction(actionName, params)
  }

  // -------------------------------------------------------------------------
  // Registers
  // -------------------------------------------------------------------------

  fun registerRead(name: String, index: Int): Value? = registers[name]?.get(index)

  fun registerWrite(name: String, index: Int, value: Value) {
    registers.getOrPut(name) { mutableMapOf() }[index] = value
  }

  // -------------------------------------------------------------------------
  // Write
  // -------------------------------------------------------------------------

  fun write(update: Update): WriteResult {
    val entity = update.entity
    return when {
      entity.hasActionProfileMember() -> {
        writeProfileMember(entity.actionProfileMember)
        WriteResult.Success
      }
      entity.hasActionProfileGroup() -> {
        writeProfileGroup(entity.actionProfileGroup)
        WriteResult.Success
      }
      entity.hasPacketReplicationEngineEntry() -> {
        writePreEntry(entity.packetReplicationEngineEntry)
        WriteResult.Success
      }
      else -> writeTableEntry(update)
    }
  }

  private fun writeTableEntry(update: Update): WriteResult {
    val entry = update.entity.tableEntry
    val tableName =
      tableNameById[entry.tableId]
        ?: return WriteResult.NotFound("unknown table ID: ${entry.tableId}")

    val entries = tables.getOrPut(tableName) { mutableListOf() }
    // P4Runtime spec §9.1: two entries are the same iff they have the same match key
    // AND the same priority (priority is part of the key for ternary/range tables).
    val existingIndex =
      entries.indexOfFirst {
        it.tableId == entry.tableId &&
          it.matchList == entry.matchList &&
          it.priority == entry.priority
      }

    // P4Runtime spec §9.1: INSERT requires the entry not to exist, MODIFY and DELETE
    // require it to exist.
    return when (update.type) {
      Update.Type.INSERT -> {
        if (existingIndex >= 0) {
          WriteResult.AlreadyExists(
            "table '$tableName' already contains an entry with the same match key"
          )
        } else {
          entries.add(entry)
          WriteResult.Success
        }
      }
      Update.Type.MODIFY -> {
        if (existingIndex < 0) {
          WriteResult.NotFound("table '$tableName' has no entry with the given match key")
        } else {
          entries[existingIndex] = entry
          WriteResult.Success
        }
      }
      Update.Type.DELETE -> {
        if (existingIndex < 0) {
          WriteResult.NotFound("table '$tableName' has no entry with the given match key")
        } else {
          entries.removeAt(existingIndex)
          WriteResult.Success
        }
      }
      else -> WriteResult.InvalidArgument("unsupported update type: ${update.type}")
    }
  }

  private fun writeProfileMember(member: P4RuntimeOuterClass.ActionProfileMember) {
    profileMembers.getOrPut(member.actionProfileId) { mutableMapOf() }[member.memberId] = member
  }

  private fun writeProfileGroup(group: P4RuntimeOuterClass.ActionProfileGroup) {
    profileGroups.getOrPut(group.actionProfileId) { mutableMapOf() }[group.groupId] = group
  }

  private fun writePreEntry(pre: P4RuntimeOuterClass.PacketReplicationEngineEntry) {
    when {
      pre.hasCloneSessionEntry() ->
        cloneSessions[pre.cloneSessionEntry.sessionId] = pre.cloneSessionEntry
      pre.hasMulticastGroupEntry() ->
        multicastGroups[pre.multicastGroupEntry.multicastGroupId] = pre.multicastGroupEntry
    }
  }

  fun getCloneSession(sessionId: Int): P4RuntimeOuterClass.CloneSessionEntry? =
    cloneSessions[sessionId]

  fun getMulticastGroup(groupId: Int): P4RuntimeOuterClass.MulticastGroupEntry? =
    multicastGroups[groupId]

  // -------------------------------------------------------------------------
  // Read
  // -------------------------------------------------------------------------

  /**
   * Returns table entries as P4Runtime Entity protos, filtered by [filter].
   * - `table_id=0`, no match fields → wildcard: returns all entries from all tables.
   * - `table_id=N`, no match fields → returns all entries from table N.
   * - `table_id=N` with match fields → returns only the entry whose match key matches the filter
   *   (P4Runtime spec §11.1: match fields in the filter act as an exact key lookup).
   */
  fun readEntities(
    filter: P4RuntimeOuterClass.TableEntry = P4RuntimeOuterClass.TableEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val sources =
      if (filter.tableId == 0) tables.values
      else listOfNotNull(tables[tableNameById[filter.tableId]])
    val hasMatchFilter = filter.matchCount > 0
    return sources.flatMap { entries ->
      entries
        .filter { entry ->
          !hasMatchFilter ||
            (entry.matchList == filter.matchList && entry.priority == filter.priority)
        }
        .map { P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(it).build() }
    }
  }

  // -------------------------------------------------------------------------
  // Lookup
  // -------------------------------------------------------------------------

  data class MemberAction(
    val memberId: Int,
    val actionName: String,
    val params: List<P4RuntimeOuterClass.Action.Param>,
  )

  data class LookupResult(
    val hit: Boolean,
    val entry: TableEntry?,
    val actionName: String,
    val actionParams: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
    val members: List<MemberAction>? = null,
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

    val tableAction = best.entry.action

    // Action profile group: resolve group → members → individual actions.
    if (tableAction.hasActionProfileGroupId() && tableAction.actionProfileGroupId != 0) {
      val profileId =
        tableActionProfile[tableName] ?: error("table $tableName has no action profile")
      val group =
        profileGroups[profileId]?.get(tableAction.actionProfileGroupId)
          ?: error("unknown group ${tableAction.actionProfileGroupId} in profile $profileId")
      val members =
        group.membersList.map { groupMember ->
          val member =
            profileMembers[profileId]?.get(groupMember.memberId)
              ?: error("unknown member ${groupMember.memberId} in profile $profileId")
          MemberAction(
            groupMember.memberId,
            resolveActionName(member.action.actionId),
            member.action.paramsList,
          )
        }
      // Use the first member's action name for the table_lookup event.
      val actionName = members.firstOrNull()?.actionName ?: "NoAction"
      return LookupResult(true, best.entry, actionName, members = members)
    }

    // Action profile member (direct, no group): resolve to a single action.
    if (tableAction.hasActionProfileMemberId() && tableAction.actionProfileMemberId != 0) {
      val profileId =
        tableActionProfile[tableName] ?: error("table $tableName has no action profile")
      val member =
        profileMembers[profileId]?.get(tableAction.actionProfileMemberId)
          ?: error("unknown member ${tableAction.actionProfileMemberId} in profile $profileId")
      return LookupResult(true, best.entry, resolveActionName(member.action.actionId))
    }

    return LookupResult(true, best.entry, resolveActionName(best.entry.action.action.actionId))
  }

  private fun resolveActionName(actionId: Int): String =
    actionNameById[actionId] ?: error("unknown action ID: $actionId")

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

      val bits =
        when (value) {
          is BitVal -> value.bits
          is BoolVal -> if (value.value) BOOL_TRUE_BITS else BOOL_FALSE_BITS
          else -> return null
        }

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

  companion object {
    private val BOOL_TRUE_BITS = BitVector.ofInt(1, 1)
    private val BOOL_FALSE_BITS = BitVector.ofInt(0, 1)
  }
}
