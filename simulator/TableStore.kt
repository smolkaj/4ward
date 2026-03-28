package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ir.DeviceConfig
import java.math.BigInteger
import java.util.IdentityHashMap
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Cache for wide (>63-bit) ByteString→BigInteger conversions. Identity-keyed: proto ByteStrings
 * from table entries are immutable and reused, so pointer equality suffices.
 */
private val bigIntCache = java.util.IdentityHashMap<ByteString, BigInteger>()

/** Interprets a protobuf [ByteString] as an unsigned big-endian [BigInteger]. Cached for reuse. */
internal fun ByteString.toUnsignedBigInteger(): BigInteger =
  bigIntCache.getOrPut(this) { BigInteger(1, toByteArray()) }

/** Decode a proto ByteString as an unsigned Long. For the common case (≤8 bytes). */
private fun ByteString.toUnsignedLong(): Long {
  var v = 0L
  for (i in 0 until size()) v = (v shl 8) or (byteAt(i).toLong() and 0xFF)
  return v
}

/**
 * Tests whether a [BitVector] matches a P4Runtime [FieldMatch].
 *
 * Supports exact, ternary, LPM, range, and optional match kinds. An unset FieldMatch (no kind set)
 * acts as a wildcard (matches any value). For fields ≤ 63 bits (ports, IPs, MACs — the vast
 * majority), uses Long arithmetic with zero heap allocation. Wider fields (IPv6 at 128 bits) fall
 * back to BigInteger.
 */
fun matchesFieldMatch(bits: BitVector, match: P4RuntimeOuterClass.FieldMatch): Boolean =
  if (bits.width <= BitVector.LONG_WIDTH) matchLong(bits.longValue, bits.width, match)
  else matchBig(bits.value, bits.width, match)

private fun matchLong(bits: Long, width: Int, match: P4RuntimeOuterClass.FieldMatch): Boolean =
  when {
    match.hasExact() -> bits == match.exact.value.toUnsignedLong()
    match.hasTernary() -> {
      val mask = match.ternary.mask.toUnsignedLong()
      (bits and mask) == (match.ternary.value.toUnsignedLong() and mask)
    }
    match.hasLpm() -> {
      val prefixLen = match.lpm.prefixLen
      if (prefixLen == 0) true
      else {
        val shift = width - prefixLen
        bits ushr shift == match.lpm.value.toUnsignedLong() ushr shift
      }
    }
    match.hasRange() -> bits in match.range.low.toUnsignedLong()..match.range.high.toUnsignedLong()
    match.hasOptional() -> bits == match.optional.value.toUnsignedLong()
    else -> true
  }

private fun matchBig(bits: BigInteger, width: Int, match: P4RuntimeOuterClass.FieldMatch): Boolean =
  when {
    match.hasExact() -> bits == match.exact.value.toUnsignedBigInteger()
    match.hasTernary() -> {
      val mask = match.ternary.mask.toUnsignedBigInteger()
      bits.and(mask) == match.ternary.value.toUnsignedBigInteger().and(mask)
    }
    match.hasLpm() -> {
      val prefixLen = match.lpm.prefixLen
      if (prefixLen == 0) true
      else {
        val shift = width - prefixLen
        bits.shiftRight(shift) == match.lpm.value.toUnsignedBigInteger().shiftRight(shift)
      }
    }
    match.hasRange() -> {
      val lo = match.range.low.toUnsignedBigInteger()
      val hi = match.range.high.toUnsignedBigInteger()
      bits in lo..hi
    }
    match.hasOptional() -> bits == match.optional.value.toUnsignedBigInteger()
    else -> true
  }

/**
 * P4Runtime spec §9.1: two entries match the same key iff they have the same table_id, match
 * fields, and priority (priority is part of the key for ternary/range tables).
 */
fun TableEntry.sameKey(other: TableEntry): Boolean =
  tableId == other.tableId && priority == other.priority && matchList == other.matchList

/** Default action state for a table: action name and optional parameters. */
data class DefaultAction(
  val name: String,
  val params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
)

/** Result of a [TableStore.write] operation. */
sealed class WriteResult {
  open val message: String
    get() = ""

  data object Success : WriteResult()

  data class AlreadyExists(override val message: String) : WriteResult()

  data class NotFound(override val message: String) : WriteResult()

  data class InvalidArgument(override val message: String) : WriteResult()

  data class ResourceExhausted(override val message: String) : WriteResult()
}

/**
 * Stores and looks up P4 table entries for all tables in a loaded pipeline.
 *
 * Supports exact, LPM, ternary, range, and optional match kinds. Entries are stored per-table;
 * lookup returns the highest-priority match.
 *
 * Call [loadMappings] once per pipeline load before any [write] or [lookup] calls.
 */
class TableStore : TableDataReader {

  private data class RegisterInfo(val name: String, val bitwidth: Int, val size: Int)

  /** Metadata for statically-allocated indexed externs (counters, meters). */
  private data class IndexedExternInfo(val size: Int)

  // -------------------------------------------------------------------------
  // Write-state
  // -------------------------------------------------------------------------

  /**
   * All mutable write-state (table entries, counters, meters, etc.) lives here.
   *
   * Bundled into a single class so that [deepCopy] (used for ROLLBACK_ON_ERROR and
   * DATAPLANE_ATOMIC) is defined right next to the fields it operates on. When adding a new field,
   * update [deepCopy] — it's right here so you can't miss it.
   *
   * Entries themselves are immutable protobuf messages; only the container structures (maps, lists)
   * need copying.
   */
  class WriteState internal constructor() {
    internal val tables: MutableMap<String, MutableList<TableEntry>> = mutableMapOf()
    internal val directCounterData = IdentityHashMap<TableEntry, P4RuntimeOuterClass.CounterData>()
    internal val directMeterData = IdentityHashMap<TableEntry, P4RuntimeOuterClass.MeterConfig>()
    internal val defaultActions: MutableMap<String, DefaultAction> = mutableMapOf()
    /** Tables whose default action has been explicitly modified via P4Runtime Write. */
    internal val modifiedDefaults: MutableSet<String> = mutableSetOf()
    internal val profileMembers:
      MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileMember>> =
      mutableMapOf()
    internal val profileGroups:
      MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileGroup>> =
      mutableMapOf()
    internal val registers: MutableMap<String, MutableMap<Int, Value>> = mutableMapOf()
    internal val counters: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.CounterData>> =
      mutableMapOf()
    internal val meters: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.MeterConfig>> =
      mutableMapOf()
    internal val cloneSessions: MutableMap<Int, P4RuntimeOuterClass.CloneSessionEntry> =
      mutableMapOf()
    internal val multicastGroups: MutableMap<Int, P4RuntimeOuterClass.MulticastGroupEntry> =
      mutableMapOf()
    internal val valueSets: MutableMap<String, MutableList<P4RuntimeOuterClass.ValueSetMember>> =
      mutableMapOf()

    /** Creates a deep copy for snapshot/restore (P4Runtime spec §12.2). */
    fun deepCopy(): WriteState =
      WriteState().also { copy ->
        tables.forEach { (k, v) -> copy.tables[k] = v.toMutableList() }
        // directCounterData/directMeterData use IdentityHashMap — their keys must be the
        // exact same TableEntry references that live in the tables lists. toMutableList()
        // above copies list structure without cloning elements, so the identity relationship
        // is preserved. Do NOT deep-copy the TableEntry objects themselves.
        copy.directCounterData.putAll(directCounterData)
        copy.directMeterData.putAll(directMeterData)
        copy.defaultActions.putAll(defaultActions)
        copy.modifiedDefaults.addAll(modifiedDefaults)
        profileMembers.forEach { (k, v) -> copy.profileMembers[k] = v.toMutableMap() }
        profileGroups.forEach { (k, v) -> copy.profileGroups[k] = v.toMutableMap() }
        registers.forEach { (k, v) -> copy.registers[k] = v.toMutableMap() }
        counters.forEach { (k, v) -> copy.counters[k] = v.toMutableMap() }
        meters.forEach { (k, v) -> copy.meters[k] = v.toMutableMap() }
        copy.cloneSessions.putAll(cloneSessions)
        copy.multicastGroups.putAll(multicastGroups)
        valueSets.forEach { (k, v) -> copy.valueSets[k] = v.toMutableList() }
      }
  }

  private var writeState = WriteState()

  /**
   * Returns the p4info alias names of tables that have at least one installed entry. This includes
   * const entries installed from `device.staticEntries` at load time, since those also need
   * schema-compatible tables in the new pipeline to survive RECONCILE_AND_COMMIT.
   */
  fun populatedTableAliases(): Set<String> =
    tables.entries
      .filter { (_, entries) -> entries.isNotEmpty() }
      .mapNotNull { (behavioralName, _) -> tableAliasByName[behavioralName] }
      .toSet()

  /** Snapshots the current write-state for later restoration (used by RECONCILE_AND_COMMIT). */
  fun snapshotWriteState(): WriteState = writeState.deepCopy()

  /**
   * Restores table entries from a previous [WriteState] snapshot, matching tables by p4info alias.
   *
   * Only entries for tables whose alias appears in both the old and new pipeline are restored. The
   * caller is responsible for verifying schema compatibility before calling this.
   */
  fun restoreTableEntries(snapshot: WriteState, oldAliasByName: Map<String, String>) {
    // Reverse of tableAliasByName: p4info alias → new behavioral name.
    val newNameByAlias = tableAliasByName.entries.associate { (name, alias) -> alias to name }

    for ((oldName, entries) in snapshot.tables) {
      if (entries.isEmpty()) continue
      val alias = oldAliasByName[oldName] ?: continue
      val newName = newNameByAlias[alias] ?: continue
      tables[newName] = entries.toMutableList()
      for (entry in entries) {
        snapshot.directCounterData[entry]?.let { directCounterData[entry] = it }
        snapshot.directMeterData[entry]?.let { directMeterData[entry] = it }
      }
    }

    for (oldName in snapshot.modifiedDefaults) {
      val alias = oldAliasByName[oldName] ?: continue
      val newName = newNameByAlias[alias] ?: continue
      snapshot.defaultActions[oldName]?.let {
        defaultActions[newName] = it
        modifiedDefaults.add(newName)
      }
    }

    // Restore PRE entries (multicast groups, clone sessions) — these are table-independent.
    cloneSessions.putAll(snapshot.cloneSessions)
    multicastGroups.putAll(snapshot.multicastGroups)

    // Restore action profile members and groups.
    for ((profileId, members) in snapshot.profileMembers) {
      profileMembers.getOrPut(profileId) { mutableMapOf() }.putAll(members)
    }
    for ((profileId, groups) in snapshot.profileGroups) {
      profileGroups.getOrPut(profileId) { mutableMapOf() }.putAll(groups)
    }
  }

  // Delegating properties — all code transparently accesses writeState fields.
  private val tables
    get() = writeState.tables

  private val directCounterData
    get() = writeState.directCounterData

  private val directMeterData
    get() = writeState.directMeterData

  private val defaultActions
    get() = writeState.defaultActions

  private val modifiedDefaults
    get() = writeState.modifiedDefaults

  private val profileMembers
    get() = writeState.profileMembers

  private val profileGroups
    get() = writeState.profileGroups

  private val registers
    get() = writeState.registers

  private val counters
    get() = writeState.counters

  private val meters
    get() = writeState.meters

  private val cloneSessions
    get() = writeState.cloneSessions

  private val multicastGroups
    get() = writeState.multicastGroups

  private val valueSets
    get() = writeState.valueSets

  // Pipeline config (populated by loadMappings, not part of write-state).
  private var tableSizeLimit: Map<String, Int> = emptyMap()
  private var profileMaxGroupSize: Map<Int, Int> = emptyMap()
  private var profileSizeLimit: Map<Int, Int> = emptyMap()
  private val tableActionProfile: MutableMap<String, Int> = mutableMapOf()
  private var directCounterTables: Set<String> = emptySet()
  private var directMeterTables: Set<String> = emptySet()

  // For unit tests: makes lookup() return hit=true with this action rather than searching entries.
  private val forcedHits: MutableMap<String, String> = mutableMapOf()

  fun setForcedHit(tableName: String, actionName: String) {
    forcedHits[tableName] = actionName
  }

  // Populated by loadMappings; used to resolve IDs to names in write() and lookup().
  // Public read-only accessors let the P4Runtime layer build Entity protos without
  // duplicating the behavioral name resolution logic.
  var tableNameById: Map<Int, String> = emptyMap()
    private set

  var actionNameById: Map<Int, String> = emptyMap()
    private set

  // Map behavioral (post-midend) names back to p4info aliases (the short names from the
  // P4 source). Used for human-readable trace output.
  private var actionAliasByName: Map<String, String> = emptyMap()

  /**
   * Behavioral-name → p4info-alias mapping for tables. Populated by [loadMappings]. Public for
   * [Simulator.loadPipelinePreservingEntries] to snapshot before reload.
   */
  var tableAliasByName: Map<String, String> = emptyMap()
    private set

  private var registerInfoById: Map<Int, RegisterInfo> = emptyMap()
  private var counterInfoById: Map<Int, IndexedExternInfo> = emptyMap()
  private var meterInfoById: Map<Int, IndexedExternInfo> = emptyMap()

  private data class ValueSetInfo(val name: String, val size: Int)

  private var valueSetInfoById: Map<Int, ValueSetInfo> = emptyMap()

  /** Match field descriptors per table (behavioral name), for data-plane add_entry. */
  private var tableMatchFields: Map<String, List<P4InfoOuterClass.MatchField>> = emptyMap()

  /** Reverse mapping: behavioral action name -> action ID. */
  private var actionIdByName: Map<String, Int> = emptyMap()

  /** Reverse mapping: behavioral table name -> table ID. */
  private var tableIdByName: Map<String, Int> = emptyMap()

  /** Action parameter info per action (behavioral name), for data-plane add_entry. */
  private var actionParamInfo: Map<String, List<P4InfoOuterClass.Action.Param>> = emptyMap()

  /**
   * Initialises the store for a loaded pipeline and clears all mutable state.
   *
   * Resolves p4info IDs to behavioral IR names (which may differ from p4info aliases for inlined
   * controls — e.g. behavioral "c_t" vs p4info alias "t"). When [device] has no behavioral config,
   * p4info aliases are used directly (convenient for tests).
   *
   * Also installs default actions and static table entries from [p4info] and [device].
   *
   * Must be called before [write] or [lookup]. Calling it again (pipeline reload) resets all state.
   */
  fun loadMappings(
    p4info: P4InfoOuterClass.P4Info = P4InfoOuterClass.P4Info.getDefaultInstance(),
    device: DeviceConfig = DeviceConfig.getDefaultInstance(),
  ) {
    // Extract behavioral names from the IR. The behavioral IR uses its own table/action
    // names (e.g. inlined "c_t" vs p4info alias "t"). When the device config is empty
    // (e.g. unit tests), resolveName falls through to the p4info alias itself.
    val behavioral = device.behavioral
    val behavioralTableNames = behavioral.tablesList.map { it.name }
    val behavioralActionNames =
      (behavioral.actionsList + behavioral.controlsList.flatMap { it.localActionsList }).flatMap {
        action ->
        listOfNotNull(action.name, action.currentName.ifEmpty { null })
      }

    fun resolveName(alias: String, candidates: List<String>): String {
      // p4info aliases use dots for nested controls (e.g. "ct.ipv4_da"), while
      // the behavioral IR uses underscores (e.g. "ct_ipv4_da"). When two tables
      // share a short name, p4c disambiguates with a control prefix
      // (e.g. "MainControlImpl.ipv4_da" for behavioral "ipv4_da"). Try multiple
      // forms to handle all cases.
      val underscored = alias.replace('.', '_')
      val afterFirstDot = if ('.' in alias) alias.substringAfter('.') else null
      val afterFirstDotUnderscored = afterFirstDot?.replace('.', '_')
      return candidates.find { it == alias }
        ?: candidates.find { it.endsWith("_$alias") }
        ?: candidates.find { it == underscored }
        ?: candidates.find { it.endsWith("_$underscored") }
        ?: afterFirstDotUnderscored?.let { suffix ->
          candidates.find { it == suffix } ?: candidates.find { it.endsWith("_$suffix") }
        }
        ?: alias
    }

    // Build both forward (id → behavioral name) and reverse (behavioral name → alias) maps
    // in a single pass per list.
    val tableById = mutableMapOf<Int, String>()
    val tableByName = mutableMapOf<String, String>()
    for (table in p4info.tablesList) {
      val alias = table.preamble.alias.ifEmpty { table.preamble.name }
      val behavioral = resolveName(alias, behavioralTableNames)
      tableById[table.preamble.id] = behavioral
      tableByName[behavioral] = alias
    }
    this.tableNameById = tableById
    this.tableAliasByName = tableByName

    val actionById = mutableMapOf<Int, String>()
    val actionByName = mutableMapOf<String, String>()
    for (action in p4info.actionsList) {
      val alias = action.preamble.alias.ifEmpty { action.preamble.name }
      val behavioral = resolveName(alias, behavioralActionNames)
      actionById[action.preamble.id] = behavioral
      actionByName[behavioral] = alias
    }
    this.actionNameById = actionById
    this.actionAliasByName = actionByName
    this.actionIdByName = actionById.entries.associate { (id, name) -> name to id }
    this.tableIdByName = tableById.entries.associate { (id, name) -> name to id }
    this.actionParamInfo =
      p4info.actionsList
        .mapNotNull { action ->
          val name = actionById[action.preamble.id] ?: return@mapNotNull null
          name to action.paramsList
        }
        .toMap()
    this.tableMatchFields =
      p4info.tablesList
        .mapNotNull { table ->
          val name = tableById[table.preamble.id] ?: return@mapNotNull null
          name to table.matchFieldsList
        }
        .toMap()
    this.registerInfoById =
      p4info.registersList.associate { reg ->
        val bitwidth = reg.typeSpec.bitstring.bit.bitwidth
        reg.preamble.id to RegisterInfo(reg.preamble.name, bitwidth, reg.size)
      }
    this.counterInfoById =
      p4info.countersList.associate { it.preamble.id to IndexedExternInfo(it.size.toInt()) }
    this.meterInfoById =
      p4info.metersList.associate { it.preamble.id to IndexedExternInfo(it.size.toInt()) }
    this.valueSetInfoById =
      p4info.valueSetsList.associate {
        it.preamble.id to
          ValueSetInfo(name = it.preamble.alias.ifEmpty { it.preamble.name }, size = it.size)
      }
    this.directCounterTables =
      p4info.directCountersList.mapNotNull { tableNameById[it.directTableId] }.toSet()
    this.directMeterTables =
      p4info.directMetersList.mapNotNull { tableNameById[it.directTableId] }.toSet()
    writeState = WriteState()
    forcedHits.clear()
    tableActionProfile.clear()

    // Cache proto repeated-field accessor (each call creates a defensive copy).
    val p4infoTables = p4info.tablesList

    // P4Runtime spec §9.27: enforce table size limits from p4info.
    tableSizeLimit =
      p4infoTables
        .filter { it.size > 0 }
        .mapNotNull { table ->
          val name = tableNameById[table.preamble.id] ?: return@mapNotNull null
          name to table.size.toInt()
        }
        .toMap()

    // P4Runtime spec §9.2: enforce max_group_size and total size from p4info action profiles.
    profileMaxGroupSize =
      p4info.actionProfilesList
        .filter { it.maxGroupSize > 0 }
        .associate { it.preamble.id to it.maxGroupSize }
    profileSizeLimit =
      p4info.actionProfilesList
        .filter { it.size > 0 }
        .associate { it.preamble.id to it.size.toInt() }

    // Register which tables use action profiles (implementation_id != 0).
    for (table in p4infoTables) {
      if (table.implementationId != 0) {
        val tableName = tableNameById[table.preamble.id] ?: continue
        tableActionProfile[tableName] = table.implementationId
      }
    }

    // Install default actions from p4info.
    for (table in p4infoTables) {
      // const_default_action_id: immutable default set in the P4 source with `const`.
      // initial_default_action: mutable default set in the P4 source without `const`.
      val defaultActionId =
        if (table.constDefaultActionId != 0) table.constDefaultActionId
        else if (table.hasInitialDefaultAction()) table.initialDefaultAction.actionId else 0
      if (defaultActionId != 0) {
        val tableName = tableNameById[table.preamble.id] ?: continue
        val actionName = actionNameById[defaultActionId] ?: "NoAction"
        // Convert p4info TableActionCall.Argument to P4Runtime Action.Param.
        val params =
          if (table.hasInitialDefaultAction())
            table.initialDefaultAction.argumentsList.map { arg ->
              p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(arg.paramId)
                .setValue(arg.value)
                .build()
            }
          else emptyList()
        setDefaultAction(tableName, actionName, params)
      }
    }

    // Install static table entries declared with `const entries` in the P4 source.
    for (update in device.staticEntries.updatesList) {
      write(update)
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
  // Data-plane table insertion (PNA add_entry)
  // -------------------------------------------------------------------------

  /**
   * Inserts a table entry from the data plane, as done by PNA's `add_entry` extern.
   *
   * Unlike [write] (which uses p4info IDs), this method works with behavioral names and runtime
   * [Value]s -- the natural representation available inside the simulator during packet processing.
   *
   * @param tableName the behavioral name of the table to insert into.
   * @param keyValues the match key values (field ID string to [Value]), from the table miss.
   * @param actionName the behavioral name of the action to install.
   * @param actionParams action parameter values, in declaration order.
   * @return true if the entry was inserted, false if the table is full or the entry already exists.
   */
  fun addEntry(
    tableName: String,
    keyValues: List<Pair<String, Value>>,
    actionName: String,
    actionParams: List<Value>,
  ): Boolean {
    val tableId = tableIdByName[tableName] ?: error("add_entry: unknown table '$tableName'")
    val actionId = actionIdByName[actionName] ?: error("add_entry: unknown action '$actionName'")
    val matchFields =
      tableMatchFields[tableName] ?: error("add_entry: no match fields for '$tableName'")
    val paramInfo = actionParamInfo[actionName] ?: emptyList()

    val entryBuilder = TableEntry.newBuilder().setTableId(tableId)

    for (mf in matchFields) {
      val (_, value) =
        keyValues.find { it.first == mf.id.toString() }
          ?: error("add_entry: missing key value for field ${mf.id} in table '$tableName'")
      val bytes = valueToBytesForMatch(value, mf.bitwidth)
      val fm = P4RuntimeOuterClass.FieldMatch.newBuilder().setFieldId(mf.id)
      when (mf.matchType) {
        P4InfoOuterClass.MatchField.MatchType.EXACT ->
          fm.setExact(P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(bytes))
        P4InfoOuterClass.MatchField.MatchType.LPM ->
          fm.setLpm(
            P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
              .setValue(bytes)
              .setPrefixLen(mf.bitwidth)
          )
        P4InfoOuterClass.MatchField.MatchType.TERNARY ->
          fm.setTernary(
            P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
              .setValue(bytes)
              .setMask(ByteString.copyFrom(ByteArray((mf.bitwidth + 7) / 8) { 0xFF.toByte() }))
          )
        P4InfoOuterClass.MatchField.MatchType.OPTIONAL ->
          fm.setOptional(P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(bytes))
        else -> error("add_entry: unsupported match type ${mf.matchType} in table '$tableName'")
      }
      entryBuilder.addMatch(fm)
    }

    val actionBuilder = P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId)
    for ((i, value) in actionParams.withIndex()) {
      val paramId = paramInfo.getOrNull(i)?.id ?: (i + 1)
      actionBuilder.addParams(
        P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramId)
          .setValue(valueToBytes(value))
      )
    }
    entryBuilder.setAction(P4RuntimeOuterClass.TableAction.newBuilder().setAction(actionBuilder))

    val entry = entryBuilder.build()
    val entries = tables.getOrPut(tableName) { mutableListOf() }

    // If an entry with the same key already exists, the add_entry is a no-op (returns true
    // per PNA spec -- the existing entry is retained).
    if (entries.any { it.sameKey(entry) }) return true

    val limit = tableSizeLimit[tableName]
    if (limit != null && entries.size >= limit) return false

    entries.add(entry)
    return true
  }

  /** Encodes a runtime [Value] as a P4Runtime [ByteString] for a match field of [bitwidth] bits. */
  private fun valueToBytesForMatch(value: Value, bitwidth: Int): ByteString =
    when (value) {
      is BitVal -> ByteString.copyFrom(value.bits.toByteArray())
      is BoolVal -> {
        val b = if (value.value) 1 else 0
        ByteString.copyFrom(BitVector.ofInt(b, bitwidth).toByteArray())
      }
      else -> error("add_entry: unsupported key value type: ${value::class.simpleName}")
    }

  /** Encodes a runtime [Value] as a P4Runtime [ByteString] for an action parameter. */
  private fun valueToBytes(value: Value): ByteString =
    when (value) {
      is BitVal -> ByteString.copyFrom(value.bits.toByteArray())
      is BoolVal -> ByteString.copyFrom(byteArrayOf(if (value.value) 1 else 0))
      else -> error("add_entry: unsupported action param type: ${value::class.simpleName}")
    }

  // -------------------------------------------------------------------------
  // Raw data accessors (used by the P4Runtime layer's EntityReader)
  // -------------------------------------------------------------------------

  override fun getTableEntries(tableName: String): List<TableEntry> =
    tables[tableName] ?: emptyList()

  override fun getDefaultAction(tableName: String): DefaultAction? = defaultActions[tableName]

  override fun isDefaultModified(tableName: String): Boolean = tableName in modifiedDefaults

  override fun getDirectCounterData(entry: TableEntry): P4RuntimeOuterClass.CounterData? =
    directCounterData[entry]

  override fun getDirectMeterData(entry: TableEntry): P4RuntimeOuterClass.MeterConfig? =
    directMeterData[entry]

  override fun hasDirectCounter(tableName: String): Boolean = tableName in directCounterTables

  override fun hasDirectMeter(tableName: String): Boolean = tableName in directMeterTables

  // -------------------------------------------------------------------------
  // Registers
  // -------------------------------------------------------------------------

  fun registerRead(name: String, index: Int): Value? = registers[name]?.get(index)

  fun registerWrite(name: String, index: Int, value: Value) {
    registers.getOrPut(name) { mutableMapOf() }[index] = value
  }

  // P4Runtime spec: RegisterEntry MODIFY only (statically allocated arrays).
  private fun writeRegisterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.RegisterEntry,
  ): WriteResult {
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("registers only support MODIFY, not $type")
    val info =
      registerInfoById[entry.registerId]
        ?: return WriteResult.NotFound(
          "unknown register ID: ${entry.registerId} " +
            "(valid registers: ${formatOptions(registerInfoById.entries.map { "'${it.value.name}' (${it.key})" })})"
        )
    val index = entry.index.index.toInt()
    if (index < 0 || index >= info.size)
      return WriteResult.InvalidArgument("register index $index out of bounds [0, ${info.size})")
    val value = BitVal(BitVector(entry.data.bitstring.toUnsignedBigInteger(), info.bitwidth))
    registerWrite(info.name, index, value)
    return WriteResult.Success
  }

  /**
   * Reads register entries as P4Runtime Entity protos, filtered by [filter].
   * - `register_id=0` → wildcard: all indices of all registers.
   * - `register_id=N`, no index → all indices for register N (0..size-1).
   * - `register_id=N`, with index → single entry.
   *
   * Unwritten indices return the default value (zero).
   */
  fun readRegisterEntries(
    filter: P4RuntimeOuterClass.RegisterEntry =
      P4RuntimeOuterClass.RegisterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val infos =
      if (filter.registerId == 0) registerInfoById
      else {
        val info = registerInfoById[filter.registerId] ?: return emptyList()
        mapOf(filter.registerId to info)
      }
    val hasIndex = filter.hasIndex()
    return infos.flatMap { (regId, info) ->
      val zeroBits = BitVector(BigInteger.ZERO, info.bitwidth)
      val indices = if (hasIndex) listOf(filter.index.index.toInt()) else (0 until info.size)
      indices.map { idx ->
        val bits = (registerRead(info.name, idx) as? BitVal)?.bits ?: zeroBits
        val data = ByteString.copyFrom(bits.toByteArray())
        P4RuntimeOuterClass.Entity.newBuilder()
          .setRegisterEntry(
            P4RuntimeOuterClass.RegisterEntry.newBuilder()
              .setRegisterId(regId)
              .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(idx.toLong()))
              .setData(p4.v1.P4DataOuterClass.P4Data.newBuilder().setBitstring(data))
          )
          .build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Indexed externs (counters, meters) — shared write helper
  // -------------------------------------------------------------------------

  /**
   * Validates and stores a value for a MODIFY-only indexed extern (counter, meter).
   *
   * Checks: MODIFY-only, known ID, index in bounds. On success, stores [value] at
   * `storage[id][index]`.
   */
  private fun <V> writeIndexedExtern(
    type: Update.Type,
    entityName: String,
    id: Int,
    index: P4RuntimeOuterClass.Index,
    infoById: Map<Int, IndexedExternInfo>,
    storage: MutableMap<Int, MutableMap<Int, V>>,
    value: V,
  ): WriteResult {
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("${entityName}s only support MODIFY, not $type")
    val info =
      infoById[id]
        ?: return WriteResult.NotFound(
          "unknown $entityName ID: $id " +
            "(valid ${entityName} IDs: ${formatOptions(infoById.keys.sorted().map { it.toString() })})"
        )
    val idx = index.index.toInt()
    if (idx < 0 || idx >= info.size)
      return WriteResult.InvalidArgument("$entityName index $idx out of bounds [0, ${info.size})")
    storage.getOrPut(id) { mutableMapOf() }[idx] = value
    return WriteResult.Success
  }

  // -------------------------------------------------------------------------
  // Counters
  // -------------------------------------------------------------------------

  private fun writeCounterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.CounterEntry,
  ): WriteResult =
    writeIndexedExtern(
      type,
      "counter",
      entry.counterId,
      entry.index,
      counterInfoById,
      counters,
      entry.data,
    )

  fun readCounterEntries(
    filter: P4RuntimeOuterClass.CounterEntry = P4RuntimeOuterClass.CounterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val infos =
      if (filter.counterId == 0) counterInfoById
      else {
        val info = counterInfoById[filter.counterId] ?: return emptyList()
        mapOf(filter.counterId to info)
      }
    val hasIndex = filter.hasIndex()
    return infos.flatMap { (counterId, info) ->
      val indices = if (hasIndex) listOf(filter.index.index.toInt()) else (0 until info.size)
      indices.map { idx ->
        val data =
          counters[counterId]?.get(idx) ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
        P4RuntimeOuterClass.Entity.newBuilder()
          .setCounterEntry(
            P4RuntimeOuterClass.CounterEntry.newBuilder()
              .setCounterId(counterId)
              .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(idx.toLong()))
              .setData(data)
          )
          .build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Meters
  // -------------------------------------------------------------------------

  private fun writeMeterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.MeterEntry,
  ): WriteResult =
    writeIndexedExtern(
      type,
      "meter",
      entry.meterId,
      entry.index,
      meterInfoById,
      meters,
      entry.config,
    )

  fun readMeterEntries(
    filter: P4RuntimeOuterClass.MeterEntry = P4RuntimeOuterClass.MeterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val infos =
      if (filter.meterId == 0) meterInfoById
      else {
        val info = meterInfoById[filter.meterId] ?: return emptyList()
        mapOf(filter.meterId to info)
      }
    val hasIndex = filter.hasIndex()
    return infos.flatMap { (meterId, info) ->
      val indices = if (hasIndex) listOf(filter.index.index.toInt()) else (0 until info.size)
      indices.map { idx ->
        val builder =
          P4RuntimeOuterClass.MeterEntry.newBuilder()
            .setMeterId(meterId)
            .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(idx.toLong()))
        meters[meterId]?.get(idx)?.let { builder.setConfig(it) }
        P4RuntimeOuterClass.Entity.newBuilder().setMeterEntry(builder).build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Direct counters
  // -------------------------------------------------------------------------

  /**
   * Increments the direct counter for [entry] in [tableName] (called on every table hit).
   *
   * [entry] must be the same object reference returned by [lookup] (i.e. the object stored in
   * [tables]), since [directCounterData] uses identity-based keying.
   */
  @Synchronized
  fun directCounterIncrement(tableName: String, entry: TableEntry, packetLengthBytes: Int) {
    if (tableName !in directCounterTables) return
    val existing = directCounterData[entry] ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
    directCounterData[entry] =
      existing
        .toBuilder()
        .setPacketCount(existing.packetCount + 1)
        .setByteCount(existing.byteCount + packetLengthBytes)
        .build()
  }

  /**
   * Validates a MODIFY-only direct extern write and calls [update] with the stored entry.
   *
   * Checks: MODIFY-only, known table ID, table has the direct extern, table entry exists.
   */
  private inline fun writeDirectExtern(
    type: Update.Type,
    entityName: String,
    tableEntry: TableEntry,
    knownTables: Set<String>,
    update: (TableEntry) -> Unit,
  ): WriteResult {
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("${entityName}s only support MODIFY, not $type")
    val tableName =
      tableNameById[tableEntry.tableId]
        ?: return WriteResult.NotFound(
          "unknown table ID ${tableEntry.tableId} " +
            "(valid tables: ${formatOptions(tableNameById.entries.map { "'${it.value}' (${it.key})" })})"
        )
    if (tableName !in knownTables)
      return WriteResult.InvalidArgument("table '$tableName' has no $entityName")
    val entries =
      tables[tableName] ?: return WriteResult.NotFound("no entries in table '$tableName'")
    val stored =
      entries.find { it.sameKey(tableEntry) }
        ?: return WriteResult.NotFound("no matching entry in table '$tableName'")
    update(stored)
    return WriteResult.Success
  }

  private fun writeDirectCounterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.DirectCounterEntry,
  ): WriteResult =
    writeDirectExtern(type, "direct counter", entry.tableEntry, directCounterTables) {
      directCounterData[it] = entry.data
    }

  fun readDirectCounterEntries(
    filter: P4RuntimeOuterClass.DirectCounterEntry =
      P4RuntimeOuterClass.DirectCounterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val tableEntry = filter.tableEntry
    val tableNames = resolveTableNames(tableEntry.tableId, directCounterTables)
    val matchFilter = if (tableEntry.matchCount > 0) tableEntry else null
    return tableNames.flatMap { tableName ->
      filteredEntries(tableName, matchFilter).map { entry ->
        val data = directCounterData[entry] ?: P4RuntimeOuterClass.CounterData.getDefaultInstance()
        P4RuntimeOuterClass.Entity.newBuilder()
          .setDirectCounterEntry(
            P4RuntimeOuterClass.DirectCounterEntry.newBuilder().setTableEntry(entry).setData(data)
          )
          .build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Direct meters
  // -------------------------------------------------------------------------

  private fun writeDirectMeterEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.DirectMeterEntry,
  ): WriteResult =
    writeDirectExtern(type, "direct meter", entry.tableEntry, directMeterTables) {
      directMeterData[it] = entry.config
    }

  fun readDirectMeterEntries(
    filter: P4RuntimeOuterClass.DirectMeterEntry =
      P4RuntimeOuterClass.DirectMeterEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val tableEntry = filter.tableEntry
    val tableNames = resolveTableNames(tableEntry.tableId, directMeterTables)
    val matchFilter = if (tableEntry.matchCount > 0) tableEntry else null
    return tableNames.flatMap { tableName ->
      filteredEntries(tableName, matchFilter).map { entry ->
        val builder = P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(entry)
        directMeterData[entry]?.let { builder.setConfig(it) }
        P4RuntimeOuterClass.Entity.newBuilder().setDirectMeterEntry(builder).build()
      }
    }
  }

  // -------------------------------------------------------------------------
  // Value sets (P4 spec §12.14, P4Runtime spec §9.6)
  // -------------------------------------------------------------------------

  /** Returns the members of a parser value_set, or empty if not populated. */
  fun getValueSetMembers(name: String): List<P4RuntimeOuterClass.ValueSetMember> =
    valueSets[name] ?: emptyList()

  /** Directly sets value_set members by name, bypassing P4Runtime write path. For testing. */
  internal fun populateValueSet(name: String, members: List<P4RuntimeOuterClass.ValueSetMember>) {
    valueSets[name] = members.toMutableList()
  }

  private fun writeValueSetEntry(
    type: Update.Type,
    entry: P4RuntimeOuterClass.ValueSetEntry,
  ): WriteResult {
    // P4Runtime spec §9.6: value_set only supports MODIFY (replaces all members atomically).
    if (type != Update.Type.MODIFY)
      return WriteResult.InvalidArgument("value_set only supports MODIFY, not $type")
    val info =
      valueSetInfoById[entry.valueSetId]
        ?: return WriteResult.NotFound(
          "unknown value_set ID: ${entry.valueSetId} " +
            "(valid value_sets: ${formatOptions(valueSetInfoById.entries.map { "'${it.value.name}' (${it.key})" })})"
        )
    val name = info.name
    val maxSize = info.size
    if (entry.membersCount > maxSize)
      return WriteResult.ResourceExhausted(
        "value_set '$name' has max size $maxSize, got ${entry.membersCount} members"
      )
    valueSets[name] = entry.membersList.toMutableList()
    return WriteResult.Success
  }

  fun readValueSetEntries(
    filter: P4RuntimeOuterClass.ValueSetEntry =
      P4RuntimeOuterClass.ValueSetEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val entries =
      if (filter.valueSetId == 0) valueSetInfoById
      else {
        val info = valueSetInfoById[filter.valueSetId] ?: return emptyList()
        mapOf(filter.valueSetId to info)
      }
    return entries.map { (vsId, info) ->
      P4RuntimeOuterClass.Entity.newBuilder()
        .setValueSetEntry(
          P4RuntimeOuterClass.ValueSetEntry.newBuilder()
            .setValueSetId(vsId)
            .addAllMembers(valueSets[info.name] ?: emptyList())
        )
        .build()
    }
  }

  // -------------------------------------------------------------------------
  // Write
  // -------------------------------------------------------------------------

  fun write(update: Update): WriteResult {
    val entity = update.entity
    return when {
      entity.hasActionProfileMember() -> writeProfileMember(update.type, entity.actionProfileMember)
      entity.hasActionProfileGroup() -> writeProfileGroup(update.type, entity.actionProfileGroup)
      entity.hasRegisterEntry() -> writeRegisterEntry(update.type, entity.registerEntry)
      entity.hasCounterEntry() -> writeCounterEntry(update.type, entity.counterEntry)
      entity.hasMeterEntry() -> writeMeterEntry(update.type, entity.meterEntry)
      entity.hasDirectCounterEntry() ->
        writeDirectCounterEntry(update.type, entity.directCounterEntry)
      entity.hasDirectMeterEntry() -> writeDirectMeterEntry(update.type, entity.directMeterEntry)
      entity.hasPacketReplicationEngineEntry() ->
        writePreEntry(update.type, entity.packetReplicationEngineEntry)
      entity.hasValueSetEntry() -> writeValueSetEntry(update.type, entity.valueSetEntry)
      entity.hasTableEntry() -> writeTableEntry(update)
      else ->
        WriteResult.InvalidArgument(
          "unsupported entity type; only table entries, action profiles, counters, meters, " +
            "registers, value sets, and PRE entries are supported"
        )
    }
  }

  private fun writeTableEntry(update: Update): WriteResult {
    val entry = update.entity.tableEntry
    val tableName =
      tableNameById[entry.tableId]
        ?: return WriteResult.NotFound(
          "unknown table ID ${entry.tableId} " +
            "(valid tables: ${formatOptions(tableNameById.entries.map { "'${it.value}' (${it.key})" })})"
        )

    // P4Runtime spec §9.1: default entries are stored separately and only support MODIFY.
    // The WriteValidator already rejects INSERT/DELETE for defaults.
    if (entry.isDefaultAction) {
      val actionName = resolveActionName(entry.action.action.actionId)
      defaultActions[tableName] = DefaultAction(actionName, entry.action.action.paramsList)
      modifiedDefaults.add(tableName)
      return WriteResult.Success
    }

    val entries = tables.getOrPut(tableName) { mutableListOf() }
    val existingIndex = entries.indexOfFirst { it.sameKey(entry) }

    // P4Runtime spec §9.1: INSERT requires the entry not to exist, MODIFY and DELETE
    // require it to exist.
    return when (update.type) {
      Update.Type.INSERT -> {
        if (existingIndex >= 0) {
          WriteResult.AlreadyExists(
            "table '$tableName' already contains an entry with the same match key"
          )
        } else {
          val limit = tableSizeLimit[tableName]
          if (limit != null && entries.size >= limit) {
            WriteResult.ResourceExhausted("table '$tableName' is full ($limit entries)")
          } else {
            entries.add(entry)
            WriteResult.Success
          }
        }
      }
      Update.Type.MODIFY -> {
        if (existingIndex < 0) {
          WriteResult.NotFound("table '$tableName' has no entry with the given match key")
        } else {
          // Transfer direct counter/meter data from old entry object to new one.
          // NOTE: if a third direct extern type is added, add a transfer line here and
          // a remove in the DELETE branch below.
          val old = entries[existingIndex]
          entries[existingIndex] = entry
          directCounterData.remove(old)?.let { directCounterData[entry] = it }
          directMeterData.remove(old)?.let { directMeterData[entry] = it }
          WriteResult.Success
        }
      }
      Update.Type.DELETE -> {
        if (existingIndex < 0) {
          WriteResult.NotFound("table '$tableName' has no entry with the given match key")
        } else {
          val removed = entries.removeAt(existingIndex)
          directCounterData.remove(removed)
          directMeterData.remove(removed)
          WriteResult.Success
        }
      }
      else -> WriteResult.InvalidArgument("unsupported update type: ${update.type}")
    }
  }

  /** Generic INSERT/MODIFY/DELETE for a keyed map entry. */
  private fun <V> writeProfileEntity(
    type: Update.Type,
    map: MutableMap<Int, V>,
    key: Int,
    value: V,
    desc: String,
  ): WriteResult =
    when (type) {
      Update.Type.INSERT ->
        if (key in map) WriteResult.AlreadyExists("$desc already exists")
        else {
          map[key] = value
          WriteResult.Success
        }
      Update.Type.MODIFY ->
        if (key !in map) WriteResult.NotFound("$desc not found")
        else {
          map[key] = value
          WriteResult.Success
        }
      Update.Type.DELETE ->
        if (key !in map) WriteResult.NotFound("$desc not found")
        else {
          map.remove(key)
          WriteResult.Success
        }
      else -> WriteResult.InvalidArgument("unsupported update type: $type")
    }

  private fun writeProfileMember(
    type: Update.Type,
    member: P4RuntimeOuterClass.ActionProfileMember,
  ): WriteResult {
    if (type == Update.Type.INSERT) {
      val err = checkProfileSizeLimit(member.actionProfileId)
      if (err != null) return err
    }
    return writeProfileEntity(
      type,
      profileMembers.getOrPut(member.actionProfileId) { mutableMapOf() },
      member.memberId,
      member,
      "member ${member.memberId} in action profile ${member.actionProfileId}",
    )
  }

  private fun writeProfileGroup(
    type: Update.Type,
    group: P4RuntimeOuterClass.ActionProfileGroup,
  ): WriteResult {
    if (type == Update.Type.INSERT) {
      val err = checkProfileSizeLimit(group.actionProfileId)
      if (err != null) return err
    }
    // P4Runtime spec §9.2: enforce max_group_size on INSERT and MODIFY.
    if (type == Update.Type.INSERT || type == Update.Type.MODIFY) {
      val maxSize = profileMaxGroupSize[group.actionProfileId]
      if (maxSize != null && group.membersCount > maxSize) {
        return WriteResult.ResourceExhausted(
          "group ${group.groupId} has ${group.membersCount} members, max is $maxSize"
        )
      }
    }
    return writeProfileEntity(
      type,
      profileGroups.getOrPut(group.actionProfileId) { mutableMapOf() },
      group.groupId,
      group,
      "group ${group.groupId} in action profile ${group.actionProfileId}",
    )
  }

  /** Checks total member+group count against the p4info action_profile.size limit. */
  private fun checkProfileSizeLimit(profileId: Int): WriteResult? {
    val limit = profileSizeLimit[profileId] ?: return null
    val memberCount = profileMembers[profileId]?.size ?: 0
    val groupCount = profileGroups[profileId]?.size ?: 0
    val current = memberCount + groupCount
    if (current >= limit) {
      return WriteResult.ResourceExhausted(
        "action profile $profileId is at capacity ($current/$limit members+groups)"
      )
    }
    return null
  }

  private fun writePreEntry(
    type: Update.Type,
    pre: P4RuntimeOuterClass.PacketReplicationEngineEntry,
  ): WriteResult =
    when {
      pre.hasCloneSessionEntry() -> {
        val entry = pre.cloneSessionEntry
        writeProfileEntity(
          type,
          cloneSessions,
          entry.sessionId,
          entry,
          "clone session ${entry.sessionId}",
        )
      }
      pre.hasMulticastGroupEntry() -> {
        val entry = pre.multicastGroupEntry
        writeProfileEntity(
          type,
          multicastGroups,
          entry.multicastGroupId,
          entry,
          "multicast group ${entry.multicastGroupId}",
        )
      }
      else -> WriteResult.InvalidArgument("PRE entry must have a clone session or multicast group")
    }

  fun getCloneSession(sessionId: Int): P4RuntimeOuterClass.CloneSessionEntry? =
    cloneSessions[sessionId]

  fun getMulticastGroup(groupId: Int): P4RuntimeOuterClass.MulticastGroupEntry? =
    multicastGroups[groupId]

  /**
   * Reads PRE entries matching the filter.
   *
   * No oneof set → wildcard (return all clone sessions and multicast groups). One oneof set →
   * return entries of that type; ID 0 means all of that type, non-zero means that specific entry.
   */
  fun readPreEntries(
    filter: P4RuntimeOuterClass.PacketReplicationEngineEntry =
      P4RuntimeOuterClass.PacketReplicationEngineEntry.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> {
    val includeClone = filter.hasCloneSessionEntry() || !filter.hasMulticastGroupEntry()
    val includeMulticast = filter.hasMulticastGroupEntry() || !filter.hasCloneSessionEntry()
    return buildList {
      if (includeClone) {
        val id = if (filter.hasCloneSessionEntry()) filter.cloneSessionEntry.sessionId else 0
        for (entry in readFromMap(cloneSessions, id)) {
          add(entry.toPreEntity { setCloneSessionEntry(entry) })
        }
      }
      if (includeMulticast) {
        val id =
          if (filter.hasMulticastGroupEntry()) filter.multicastGroupEntry.multicastGroupId else 0
        for (entry in readFromMap(multicastGroups, id)) {
          add(entry.toPreEntity { setMulticastGroupEntry(entry) })
        }
      }
    }
  }

  /** Returns all values if [id] is 0 (wildcard), or the single entry matching [id]. */
  private fun <T> readFromMap(map: MutableMap<Int, T>, id: Int): Collection<T> =
    if (id != 0) listOfNotNull(map[id]) else map.values

  /** Wraps a PRE sub-entry into an `Entity` proto. */
  private fun <T> T.toPreEntity(
    setter: P4RuntimeOuterClass.PacketReplicationEngineEntry.Builder.(T) -> Unit
  ): P4RuntimeOuterClass.Entity =
    P4RuntimeOuterClass.Entity.newBuilder()
      .setPacketReplicationEngineEntry(
        P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder().also { it.setter(this) }
      )
      .build()

  // -------------------------------------------------------------------------
  // Snapshot / Restore (for ROLLBACK_ON_ERROR / DATAPLANE_ATOMIC)
  // -------------------------------------------------------------------------

  /** Captures a deep copy of all mutable write-state for later [restore]. */
  fun snapshot(): WriteState = writeState.deepCopy()

  /** Restores write-state to a previously captured snapshot, consuming it. */
  fun restore(snapshot: WriteState) {
    writeState = snapshot
  }

  // -------------------------------------------------------------------------
  // Read
  // -------------------------------------------------------------------------

  /** Resolves table names for a read, optionally restricted to [scope]. */
  private fun resolveTableNames(tableId: Int, scope: Set<String>? = null): Collection<String> {
    if (tableId == 0) return scope ?: tableNameById.values
    val name = tableNameById[tableId] ?: return emptyList()
    if (scope != null && name !in scope) return emptyList()
    return listOf(name)
  }

  /** Returns entries from [tableName], filtered by match key if [matchFilter] is non-null. */
  private fun filteredEntries(
    tableName: String,
    matchFilter: P4RuntimeOuterClass.TableEntry?,
  ): List<P4RuntimeOuterClass.TableEntry> {
    val entries = tables[tableName] ?: return emptyList()
    if (matchFilter == null) return entries
    // P4Runtime spec §9.1: match key + priority uniquely identify an entry.
    return listOfNotNull(entries.find { it.sameKey(matchFilter) })
  }

  /**
   * Generic read for a two-level profile map (profile_id → entry_id → value).
   * - `profileId=0` → wildcard: all values from all profiles.
   * - `profileId=N, entryId=0` → all values from profile N.
   * - `profileId=N, entryId=M` → single value (N, M).
   */
  private fun <V> readProfileEntities(
    storage: Map<Int, Map<Int, V>>,
    profileId: Int,
    entryId: Int,
    toEntity: (V) -> P4RuntimeOuterClass.Entity,
  ): List<P4RuntimeOuterClass.Entity> {
    val sources =
      if (profileId == 0) storage.values.flatMap { it.values }
      else {
        val entries = storage[profileId] ?: return emptyList()
        if (entryId != 0) listOfNotNull(entries[entryId]) else entries.values.toList()
      }
    return sources.map(toEntity)
  }

  fun readProfileMembers(
    filter: P4RuntimeOuterClass.ActionProfileMember =
      P4RuntimeOuterClass.ActionProfileMember.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> =
    readProfileEntities(profileMembers, filter.actionProfileId, filter.memberId) {
      P4RuntimeOuterClass.Entity.newBuilder().setActionProfileMember(it).build()
    }

  fun readProfileGroups(
    filter: P4RuntimeOuterClass.ActionProfileGroup =
      P4RuntimeOuterClass.ActionProfileGroup.getDefaultInstance()
  ): List<P4RuntimeOuterClass.Entity> =
    readProfileEntities(profileGroups, filter.actionProfileId, filter.groupId) {
      P4RuntimeOuterClass.Entity.newBuilder().setActionProfileGroup(it).build()
    }

  /**
   * Returns true if the table with p4info [tableId] has an entry with match field [fieldId] equal
   * to [value].
   *
   * Used by `@refers_to` referential integrity validation. Checks exact and optional match fields.
   */
  fun hasEntryWithFieldValue(tableId: Int, fieldId: Int, value: ByteString): Boolean {
    val tableName = tableNameById[tableId] ?: return false
    return tables[tableName]?.any { entry ->
      entry.matchList.any { fm ->
        fm.fieldId == fieldId &&
          when {
            fm.hasExact() -> fm.exact.value == value
            fm.hasOptional() -> fm.optional.value == value
            else -> false
          }
      }
    } ?: false
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

    // One-shot action selector (P4Runtime spec §9.2.3): the entry embeds actions inline.
    // Member IDs are synthetic (list indices) since one-shot entries have no real member IDs.
    if (tableAction.hasActionProfileActionSet()) {
      val members =
        tableAction.actionProfileActionSet.actionProfileActionsList.mapIndexed { i, action ->
          MemberAction(i, resolveActionName(action.action.actionId), action.action.paramsList)
        }
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

  private fun formatOptions(options: List<String>): String =
    if (options.size <= 10) options.joinToString(", ")
    else options.take(10).joinToString(", ") + " ... and ${options.size - 10} more"

  private fun resolveActionName(actionId: Int): String =
    actionNameById[actionId]
      ?: error(
        "unknown action ID: $actionId " +
          "(valid actions: ${formatOptions(actionNameById.entries.map { "'${it.value}' (${it.key})" })})"
      )

  /** Resolves an action name (alias or behavioral) to its behavioral name. */
  fun resolveActionByAlias(name: String): String? {
    if (name in actionIdByName) return name
    // Exact alias match.
    actionAliasByName.entries
      .find { it.value == name }
      ?.let {
        return it.key
      }
    // Fuzzy match: nested controls use underscored behavioral names for dotted aliases.
    val candidates = actionIdByName.keys.toList()
    val underscored = name.replace('.', '_')
    candidates
      .find { it.endsWith("_$name") || it.endsWith("_$underscored") }
      ?.let {
        return it
      }
    return null
  }

  /** Returns the short p4info alias for a behavioral action name, or the name itself if unknown. */
  fun actionDisplayName(behavioralName: String): String =
    actionAliasByName[behavioralName] ?: behavioralName

  /** Returns the short p4info alias for a behavioral table name, or the name itself if unknown. */
  fun tableDisplayName(behavioralName: String): String =
    tableAliasByName[behavioralName] ?: behavioralName

  /** Returns the short p4info alias for a behavioral name (table or action), or the name itself. */
  fun displayName(behavioralName: String): String =
    tableAliasByName[behavioralName] ?: actionAliasByName[behavioralName] ?: behavioralName

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

      if (!matchesFieldMatch(bits, match)) return null

      // Accumulate score for priority-based match kinds.
      when {
        match.hasLpm() -> score += match.lpm.prefixLen.toLong()
        match.hasTernary() -> score += entry.priority.toLong()
        match.hasRange() -> score += entry.priority.toLong()
      // Exact and optional don't contribute to relative scoring — all exact
      // fields either match or don't.
      }
    }
    return score
  }

  companion object {
    private val BOOL_TRUE_BITS = BitVector.ofInt(1, 1)
    private val BOOL_FALSE_BITS = BitVector.ofInt(0, 1)
  }
}
