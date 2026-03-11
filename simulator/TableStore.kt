package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ir.v1.DeviceConfig
import java.math.BigInteger
import java.util.IdentityHashMap
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/** Interprets a protobuf [ByteString] as an unsigned big-endian integer. */
private fun ByteString.toUnsignedBigInteger(): BigInteger = BigInteger(1, toByteArray())

// P4Runtime spec §9.1: two entries are the same iff they have the same match key
// AND the same priority (priority is part of the key for ternary/range tables).
private fun TableEntry.sameKey(other: TableEntry): Boolean =
  tableId == other.tableId && priority == other.priority && matchList == other.matchList

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
class TableStore {

  private data class RegisterInfo(val name: String, val bitwidth: Int, val size: Int)

  /** Metadata for statically-allocated indexed externs (counters, meters). */
  private data class IndexedExternInfo(val size: Int)

  data class DefaultAction(
    val name: String,
    val params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
  )

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
    val tables: MutableMap<String, MutableList<TableEntry>> = mutableMapOf()
    val directCounterData = IdentityHashMap<TableEntry, P4RuntimeOuterClass.CounterData>()
    val directMeterData = IdentityHashMap<TableEntry, P4RuntimeOuterClass.MeterConfig>()
    val defaultActions: MutableMap<String, DefaultAction> = mutableMapOf()
    val profileMembers: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileMember>> =
      mutableMapOf()
    val profileGroups: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.ActionProfileGroup>> =
      mutableMapOf()
    val registers: MutableMap<String, MutableMap<Int, Value>> = mutableMapOf()
    val counters: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.CounterData>> = mutableMapOf()
    val meters: MutableMap<Int, MutableMap<Int, P4RuntimeOuterClass.MeterConfig>> = mutableMapOf()
    val cloneSessions: MutableMap<Int, P4RuntimeOuterClass.CloneSessionEntry> = mutableMapOf()
    val multicastGroups: MutableMap<Int, P4RuntimeOuterClass.MulticastGroupEntry> = mutableMapOf()

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
        profileMembers.forEach { (k, v) -> copy.profileMembers[k] = v.toMutableMap() }
        profileGroups.forEach { (k, v) -> copy.profileGroups[k] = v.toMutableMap() }
        registers.forEach { (k, v) -> copy.registers[k] = v.toMutableMap() }
        counters.forEach { (k, v) -> copy.counters[k] = v.toMutableMap() }
        meters.forEach { (k, v) -> copy.meters[k] = v.toMutableMap() }
        copy.cloneSessions.putAll(cloneSessions)
        copy.multicastGroups.putAll(multicastGroups)
      }
  }

  private var writeState = WriteState()

  // Delegating properties — all code transparently accesses writeState fields.
  private val tables
    get() = writeState.tables

  private val directCounterData
    get() = writeState.directCounterData

  private val directMeterData
    get() = writeState.directMeterData

  private val defaultActions
    get() = writeState.defaultActions

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

  // Pipeline config (populated by loadMappings, not part of write-state).
  private var tableSizeLimit: Map<String, Int> = emptyMap()
  private var profileMaxGroupSize: Map<Int, Int> = emptyMap()
  private val tableActionProfile: MutableMap<String, Int> = mutableMapOf()
  private var directCounterTables: Set<String> = emptySet()
  private var directMeterTables: Set<String> = emptySet()

  // For unit tests: makes lookup() return hit=true with this action rather than searching entries.
  private val forcedHits: MutableMap<String, String> = mutableMapOf()

  fun setForcedHit(tableName: String, actionName: String) {
    forcedHits[tableName] = actionName
  }

  // Populated by loadMappings; used to resolve IDs to names in write() and lookup().
  private var tableNameById: Map<Int, String> = emptyMap()
  private var actionNameById: Map<Int, String> = emptyMap()
  private var registerInfoById: Map<Int, RegisterInfo> = emptyMap()
  private var counterInfoById: Map<Int, IndexedExternInfo> = emptyMap()
  private var meterInfoById: Map<Int, IndexedExternInfo> = emptyMap()

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

    fun resolveName(alias: String, candidates: List<String>): String =
      candidates.find { it == alias } ?: candidates.find { it.endsWith("_$alias") } ?: alias

    this.tableNameById =
      p4info.tablesList.associate { table ->
        val alias = table.preamble.alias.ifEmpty { table.preamble.name }
        table.preamble.id to resolveName(alias, behavioralTableNames)
      }
    this.actionNameById =
      p4info.actionsList.associate { action ->
        val alias = action.preamble.alias.ifEmpty { action.preamble.name }
        action.preamble.id to resolveName(alias, behavioralActionNames)
      }
    this.registerInfoById =
      p4info.registersList.associate { reg ->
        val bitwidth = reg.typeSpec.bitstring.bit.bitwidth
        reg.preamble.id to RegisterInfo(reg.preamble.name, bitwidth, reg.size)
      }
    this.counterInfoById =
      p4info.countersList.associate { it.preamble.id to IndexedExternInfo(it.size.toInt()) }
    this.meterInfoById =
      p4info.metersList.associate { it.preamble.id to IndexedExternInfo(it.size.toInt()) }
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

    // P4Runtime spec §9.2: enforce max_group_size from p4info action profiles.
    profileMaxGroupSize =
      p4info.actionProfilesList
        .filter { it.maxGroupSize > 0 }
        .associate { it.preamble.id to it.maxGroupSize }

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
        ?: return WriteResult.NotFound("unknown register ID: ${entry.registerId}")
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
    val info = infoById[id] ?: return WriteResult.NotFound("unknown $entityName ID: $id")
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
        ?: return WriteResult.NotFound("unknown table ID: ${tableEntry.tableId}")
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
    val tablesToRead =
      if (tableEntry.tableId == 0) directCounterTables
      else {
        val tableName = tableNameById[tableEntry.tableId] ?: return emptyList()
        if (tableName !in directCounterTables) return emptyList()
        setOf(tableName)
      }
    val hasMatchFilter = tableEntry.matchCount > 0
    return tablesToRead.flatMap { tableName ->
      val entries = tables[tableName] ?: return@flatMap emptyList()
      val filtered = if (hasMatchFilter) entries.filter { it.sameKey(tableEntry) } else entries
      filtered.map { entry ->
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
    val tablesToRead =
      if (tableEntry.tableId == 0) directMeterTables
      else {
        val tableName = tableNameById[tableEntry.tableId] ?: return emptyList()
        if (tableName !in directMeterTables) return emptyList()
        setOf(tableName)
      }
    val hasMatchFilter = tableEntry.matchCount > 0
    return tablesToRead.flatMap { tableName ->
      val entries = tables[tableName] ?: return@flatMap emptyList()
      val filtered = if (hasMatchFilter) entries.filter { it.sameKey(tableEntry) } else entries
      filtered.map { entry ->
        val builder = P4RuntimeOuterClass.DirectMeterEntry.newBuilder().setTableEntry(entry)
        directMeterData[entry]?.let { builder.setConfig(it) }
        P4RuntimeOuterClass.Entity.newBuilder().setDirectMeterEntry(builder).build()
      }
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
      entity.hasPacketReplicationEngineEntry() -> {
        writePreEntry(entity.packetReplicationEngineEntry)
        WriteResult.Success
      }
      entity.hasTableEntry() -> writeTableEntry(update)
      else ->
        WriteResult.InvalidArgument(
          "unsupported entity type; only table entries, action profiles, counters, meters, " +
            "registers, and PRE entries are supported"
        )
    }
  }

  private fun writeTableEntry(update: Update): WriteResult {
    val entry = update.entity.tableEntry
    val tableName =
      tableNameById[entry.tableId]
        ?: return WriteResult.NotFound("unknown table ID: ${entry.tableId}")

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
  ): WriteResult =
    writeProfileEntity(
      type,
      profileMembers.getOrPut(member.actionProfileId) { mutableMapOf() },
      member.memberId,
      member,
      "member ${member.memberId} in action profile ${member.actionProfileId}",
    )

  private fun writeProfileGroup(
    type: Update.Type,
    group: P4RuntimeOuterClass.ActionProfileGroup,
  ): WriteResult {
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
  // Snapshot / Restore (for ROLLBACK_ON_ERROR / DATAPLANE_ATOMIC)
  // -------------------------------------------------------------------------

  /** Captures a deep copy of all mutable write-state for later [restore]. */
  fun snapshot(): WriteState = writeState.deepCopy()

  /** Restores write-state to a previously captured snapshot. */
  fun restore(snapshot: WriteState) {
    writeState = snapshot.deepCopy()
  }

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
    return sources
      .flatMap { entries ->
        // P4Runtime spec §9.1: match key + priority uniquely identify an entry,
        // so at most one entry can match a filter with match fields.
        if (hasMatchFilter) listOfNotNull(entries.find { it.sameKey(filter) }) else entries
      }
      .map { P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(it).build() }
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
