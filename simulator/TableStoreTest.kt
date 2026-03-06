package fourward.simulator

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableAction
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Unit tests for [TableStore] covering exact, LPM, and ternary match kinds along with basic
 * write/delete and default-action semantics.
 */
class TableStoreTest {

  private lateinit var store: TableStore

  @Before
  fun setUp() {
    store = TableStore()
    store.loadMappings(
      tableNameById = mapOf(TABLE_ID to TABLE_NAME),
      actionNameById = ACTION_ID_TO_NAME,
    )
  }

  // ---------------------------------------------------------------------------
  // Entry builders
  // ---------------------------------------------------------------------------

  private fun exactEntry(fieldId: Int, value: ByteArray, actionId: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
      )
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun lpmEntry(fieldId: Int, value: ByteArray, prefixLen: Int, actionId: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setLpm(
            FieldMatch.LPM.newBuilder().setValue(ByteString.copyFrom(value)).setPrefixLen(prefixLen)
          )
      )
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun ternaryEntry(
    fieldId: Int,
    value: ByteArray,
    mask: ByteArray,
    priority: Int,
    actionId: Int,
  ): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(value))
              .setMask(ByteString.copyFrom(mask))
          )
      )
      .setPriority(priority)
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun rangeEntry(
    fieldId: Int,
    lo: ByteArray,
    hi: ByteArray,
    priority: Int,
    actionId: Int,
  ): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setRange(
            FieldMatch.Range.newBuilder()
              .setLow(ByteString.copyFrom(lo))
              .setHigh(ByteString.copyFrom(hi))
          )
      )
      .setPriority(priority)
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun optionalEntry(fieldId: Int, value: ByteArray, actionId: Int): TableEntry =
    TableEntry.newBuilder()
      .setTableId(TABLE_ID)
      .addMatch(
        FieldMatch.newBuilder()
          .setFieldId(fieldId)
          .setOptional(FieldMatch.Optional.newBuilder().setValue(ByteString.copyFrom(value)))
      )
      .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(actionId)))
      .build()

  private fun write(entry: TableEntry) {
    store.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )
  }

  // ---------------------------------------------------------------------------
  // Default action / miss
  // ---------------------------------------------------------------------------

  @Test
  fun `miss on empty table returns default action`() {
    store.setDefaultAction(TABLE_NAME, "drop")
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(1, 8)))
    assertFalse(result.hit)
    assertNull(result.entry)
    assertEquals("drop", result.actionName)
  }

  @Test
  fun `miss with no registered default returns NoAction`() {
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(1, 8)))
    assertFalse(result.hit)
    assertEquals("NoAction", result.actionName)
  }

  // ---------------------------------------------------------------------------
  // Exact match
  // ---------------------------------------------------------------------------

  @Test
  fun `exact match hit returns entry and resolved action name`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8)))
    assertTrue(result.hit)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `exact match hit with BoolVal key`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x00), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BoolVal(false)))
    assertTrue(result.hit)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `exact match miss on different value`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(11, 8)))
    assertFalse(result.hit)
  }

  // ---------------------------------------------------------------------------
  // LPM
  // ---------------------------------------------------------------------------

  @Test
  fun `LPM selects the longest matching prefix`() {
    // /1: top bit set → matches 0b1xxx_xxxx (0x80–0xFF), action10
    write(lpmEntry(1, byteArrayOf(0x80.toByte()), prefixLen = 1, actionId = 10))
    // /2: top two bits = 11 → matches 0b11xx_xxxx (0xC0–0xFF), action20
    write(lpmEntry(1, byteArrayOf(0xC0.toByte()), prefixLen = 2, actionId = 20))

    // 0xC1 matches both /1 and /2; /2 is longer → action20
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xC1, 8)))
    assertTrue(result.hit)
    assertEquals("action20", result.actionName)
  }

  @Test
  fun `LPM falls back to shorter prefix when longer does not match`() {
    write(lpmEntry(1, byteArrayOf(0x80.toByte()), prefixLen = 1, actionId = 10))
    write(lpmEntry(1, byteArrayOf(0xC0.toByte()), prefixLen = 2, actionId = 20))

    // 0x81 matches /1 (top bit set) but not /2 (top two bits = 10) → action10
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0x81, 8)))
    assertTrue(result.hit)
    assertEquals("action10", result.actionName)
  }

  @Test
  fun `LPM miss when no prefix covers the lookup key`() {
    // Only covers 0b11xx_xxxx
    write(lpmEntry(1, byteArrayOf(0xC0.toByte()), prefixLen = 2, actionId = 20))
    // 0x01 (top bit clear) does not match
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(1, 8)))
    assertFalse(result.hit)
  }

  // ---------------------------------------------------------------------------
  // Ternary
  // ---------------------------------------------------------------------------

  @Test
  fun `ternary highest priority entry wins when both match`() {
    val ff = byteArrayOf(0xFF.toByte())
    // Both entries match 0xFF exactly (all-ones mask); higher priority wins
    write(ternaryEntry(1, value = ff, mask = ff, priority = 5, actionId = 100))
    write(ternaryEntry(1, value = ff, mask = ff, priority = 10, actionId = 200))

    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xFF, 8)))
    assertTrue(result.hit)
    assertEquals("action200", result.actionName)
  }

  @Test
  fun `ternary wildcard mask matches any value`() {
    // All-zeros mask → match anything (value && 0x00 == 0x00 && 0x00)
    write(
      ternaryEntry(
        1,
        value = byteArrayOf(0x00),
        mask = byteArrayOf(0x00),
        priority = 1,
        actionId = 50,
      )
    )
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xAB, 8)))
    assertTrue(result.hit)
    assertEquals("action50", result.actionName)
  }

  @Test
  fun `ternary miss when value does not match masked pattern`() {
    // Matches only if top nibble = 0xA (mask = 0xF0, value = 0xA0)
    write(
      ternaryEntry(
        1,
        value = byteArrayOf(0xA0.toByte()),
        mask = byteArrayOf(0xF0.toByte()),
        priority = 1,
        actionId = 77,
      )
    )
    // 0xB0: top nibble = 0xB ≠ 0xA → no match
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xB0, 8)))
    assertFalse(result.hit)
  }

  // ---------------------------------------------------------------------------
  // Range
  // ---------------------------------------------------------------------------

  @Test
  fun `range match hit when value is within bounds`() {
    write(
      rangeEntry(1, lo = byteArrayOf(0x10), hi = byteArrayOf(0x20), priority = 1, actionId = 10)
    )
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0x18, 8)))
    assertTrue(result.hit)
    assertEquals("action10", result.actionName)
  }

  @Test
  fun `range match hit on exact boundary values`() {
    write(
      rangeEntry(1, lo = byteArrayOf(0x10), hi = byteArrayOf(0x20), priority = 1, actionId = 10)
    )
    assertTrue(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x10, 8))).hit)
    assertTrue(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x20, 8))).hit)
  }

  @Test
  fun `range match miss when value is outside bounds`() {
    write(
      rangeEntry(1, lo = byteArrayOf(0x10), hi = byteArrayOf(0x20), priority = 1, actionId = 10)
    )
    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x0F, 8))).hit)
    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(0x21, 8))).hit)
  }

  // ---------------------------------------------------------------------------
  // Optional
  // ---------------------------------------------------------------------------

  @Test
  fun `optional match hit on exact value`() {
    write(optionalEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8)))
    assertTrue(result.hit)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `optional match miss on different value`() {
    write(optionalEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42))
    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(11, 8))).hit)
  }

  @Test
  fun `optional wildcard matches any value when field is absent from entry`() {
    // Entry has no match fields → all keys are wildcarded
    val entry =
      TableEntry.newBuilder()
        .setTableId(TABLE_ID)
        .setPriority(1)
        .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(10)))
        .build()
    write(entry)
    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(0xFF, 8)))
    assertTrue(result.hit)
  }

  // ---------------------------------------------------------------------------
  // Write operations
  // ---------------------------------------------------------------------------

  @Test
  fun `delete removes entry so subsequent lookup misses`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    write(entry)

    assertTrue(store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8))).hit)

    store.write(
      Update.newBuilder()
        .setType(Update.Type.DELETE)
        .setEntity(Entity.newBuilder().setTableEntry(entry))
        .build()
    )

    assertFalse(store.lookup(TABLE_NAME, listOf("1" to BitVal(10, 8))).hit)
  }

  @Test
  fun `modify overwrites an existing entry`() {
    write(exactEntry(fieldId = 1, value = byteArrayOf(0x05), actionId = 10))

    store.write(
      Update.newBuilder()
        .setType(Update.Type.MODIFY)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(exactEntry(fieldId = 1, value = byteArrayOf(0x05), actionId = 99))
        )
        .build()
    )

    val result = store.lookup(TABLE_NAME, listOf("1" to BitVal(5, 8)))
    assertTrue(result.hit)
    assertEquals("action99", result.actionName)
  }

  // P4Runtime spec §9.1: INSERT of a duplicate entry must return ALREADY_EXISTS.
  @Test
  fun `insert duplicate entry returns AlreadyExists`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    write(entry)
    val result =
      store.write(
        Update.newBuilder()
          .setType(Update.Type.INSERT)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected AlreadyExists", result is WriteResult.AlreadyExists)
  }

  // P4Runtime spec §9.1: MODIFY of a non-existent entry must return NotFound.
  @Test
  fun `modify non-existent entry returns NotFound`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    val result =
      store.write(
        Update.newBuilder()
          .setType(Update.Type.MODIFY)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  // P4Runtime spec §9.1: DELETE of a non-existent entry must return NotFound.
  @Test
  fun `delete non-existent entry returns NotFound`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(0x0A), actionId = 42)
    val result =
      store.write(
        Update.newBuilder()
          .setType(Update.Type.DELETE)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  @Test
  fun `write with unknown table ID returns NotFound`() {
    val entry = TableEntry.newBuilder().setTableId(99999).build()
    val result =
      store.write(
        Update.newBuilder()
          .setType(Update.Type.INSERT)
          .setEntity(Entity.newBuilder().setTableEntry(entry))
          .build()
      )
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  // ---------------------------------------------------------------------------
  // Action profiles
  // ---------------------------------------------------------------------------

  /** Creates a TableStore with an action-profile-backed table for profile tests. */
  private fun storeWithProfile(): TableStore {
    val store = TableStore()
    val p4infoTable =
      P4InfoOuterClass.Table.newBuilder()
        .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(PROFILE_TABLE_ID))
        .setImplementationId(PROFILE_ID)
        .build()
    store.loadMappings(
      tableNameById = mapOf(PROFILE_TABLE_ID to PROFILE_TABLE_NAME),
      actionNameById = ACTION_ID_TO_NAME,
      p4infoTables = listOf(p4infoTable),
    )
    return store
  }

  private fun writeMember(
    store: TableStore,
    memberId: Int,
    actionId: Int,
    paramValue: Byte,
    type: Update.Type = Update.Type.INSERT,
  ): WriteResult =
    store.write(
      Update.newBuilder()
        .setType(type)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileMember(
              P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(PROFILE_ID)
                .setMemberId(memberId)
                .setAction(
                  Action.newBuilder()
                    .setActionId(actionId)
                    .addParams(
                      Action.Param.newBuilder()
                        .setParamId(1)
                        .setValue(ByteString.copyFrom(byteArrayOf(paramValue)))
                    )
                )
            )
        )
        .build()
    )

  private fun writeGroup(
    store: TableStore,
    groupId: Int,
    memberIds: List<Int>,
    type: Update.Type = Update.Type.INSERT,
  ): WriteResult =
    store.write(
      Update.newBuilder()
        .setType(type)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileGroup(
              P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
                .setActionProfileId(PROFILE_ID)
                .setGroupId(groupId)
                .addAllMembers(
                  memberIds.map { mid ->
                    P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                      .setMemberId(mid)
                      .setWeight(1)
                      .build()
                  }
                )
            )
        )
        .build()
    )

  private fun writeGroupEntry(store: TableStore, fieldValue: Byte, groupId: Int) {
    store.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(PROFILE_TABLE_ID)
                .addMatch(
                  FieldMatch.newBuilder()
                    .setFieldId(1)
                    .setExact(
                      FieldMatch.Exact.newBuilder()
                        .setValue(ByteString.copyFrom(byteArrayOf(fieldValue)))
                    )
                )
                .setAction(TableAction.newBuilder().setActionProfileGroupId(groupId))
            )
        )
        .build()
    )
  }

  private fun writeMemberEntry(store: TableStore, fieldValue: Byte, memberId: Int) {
    store.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(PROFILE_TABLE_ID)
                .addMatch(
                  FieldMatch.newBuilder()
                    .setFieldId(1)
                    .setExact(
                      FieldMatch.Exact.newBuilder()
                        .setValue(ByteString.copyFrom(byteArrayOf(fieldValue)))
                    )
                )
                .setAction(TableAction.newBuilder().setActionProfileMemberId(memberId))
            )
        )
        .build()
    )
  }

  @Test
  fun `group entry lookup returns members for forking`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 0, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 1, actionId = 20, paramValue = 2)
    writeGroup(s, groupId = 1, memberIds = listOf(0, 1))
    writeGroupEntry(s, fieldValue = 0x0A, groupId = 1)

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0A, 8)))
    assertTrue(result.hit)
    assertNotNull(result.members)
    assertEquals(2, result.members!!.size)
    assertEquals(0, result.members!![0].memberId)
    assertEquals("action10", result.members!![0].actionName)
    assertEquals(1, result.members!![1].memberId)
    assertEquals("action20", result.members!![1].actionName)
  }

  @Test
  fun `direct member entry lookup returns no members`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 5, actionId = 42, paramValue = 0x7F)
    writeMemberEntry(s, fieldValue = 0x0B, memberId = 5)

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0B, 8)))
    assertTrue(result.hit)
    assertNull(result.members)
    assertEquals("action42", result.actionName)
  }

  @Test
  fun `group entry miss returns default action without members`() {
    val s = storeWithProfile()
    s.setDefaultAction(PROFILE_TABLE_NAME, "drop")

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0xFF, 8)))
    assertFalse(result.hit)
    assertNull(result.members)
    assertEquals("drop", result.actionName)
  }

  @Test
  fun `group member params are preserved through lookup`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 0, actionId = 10, paramValue = 0x42)
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    writeGroupEntry(s, fieldValue = 0x0A, groupId = 1)

    val result = s.lookup(PROFILE_TABLE_NAME, listOf("1" to BitVal(0x0A, 8)))
    val params = result.members!![0].params
    assertEquals(1, params.size)
    assertEquals(ByteString.copyFrom(byteArrayOf(0x42)), params[0].value)
  }

  // ---------------------------------------------------------------------------
  // PRE: clone sessions and multicast groups
  // ---------------------------------------------------------------------------

  private fun writeCloneSession(sessionId: Int, egressPort: Int) {
    store.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setCloneSessionEntry(
                  P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                    .setSessionId(sessionId)
                    .addReplicas(P4RuntimeOuterClass.Replica.newBuilder().setEgressPort(egressPort))
                )
            )
        )
        .build()
    )
  }

  private fun writeMulticastGroup(groupId: Int, replicas: List<Pair<Int, Int>>) {
    store.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setMulticastGroupEntry(
                  P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                    .setMulticastGroupId(groupId)
                    .addAllReplicas(
                      replicas.map { (rid, port) ->
                        P4RuntimeOuterClass.Replica.newBuilder()
                          .setInstance(rid)
                          .setEgressPort(port)
                          .build()
                      }
                    )
                )
            )
        )
        .build()
    )
  }

  @Test
  fun `clone session round-trip`() {
    writeCloneSession(sessionId = 100, egressPort = 5)
    val session = store.getCloneSession(100)
    assertNotNull(session)
    assertEquals(100, session!!.sessionId)
    assertEquals(5, session.replicasList[0].egressPort)
  }

  @Test
  fun `clone session miss returns null`() {
    assertNull(store.getCloneSession(999))
  }

  @Test
  fun `multicast group round-trip`() {
    writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1, 0 to 2, 0 to 3))
    val group = store.getMulticastGroup(1)
    assertNotNull(group)
    assertEquals(1, group!!.multicastGroupId)
    assertEquals(3, group.replicasCount)
    assertEquals(1, group.replicasList[0].egressPort)
    assertEquals(2, group.replicasList[1].egressPort)
    assertEquals(3, group.replicasList[2].egressPort)
  }

  @Test
  fun `multicast group miss returns null`() {
    assertNull(store.getMulticastGroup(999))
  }

  @Test
  fun `loadMappings clears PRE entries`() {
    writeCloneSession(sessionId = 1, egressPort = 5)
    writeMulticastGroup(groupId = 1, replicas = listOf(0 to 1))

    store.loadMappings(
      tableNameById = mapOf(TABLE_ID to TABLE_NAME),
      actionNameById = ACTION_ID_TO_NAME,
    )

    assertNull(store.getCloneSession(1))
    assertNull(store.getMulticastGroup(1))
  }

  // ---------------------------------------------------------------------------
  // Action profile write semantics
  // ---------------------------------------------------------------------------

  @Test
  fun `insert duplicate member returns AlreadyExists`() {
    val s = storeWithProfile()
    assertEquals(WriteResult.Success, writeMember(s, memberId = 1, actionId = 10, paramValue = 1))
    assertTrue(
      writeMember(s, memberId = 1, actionId = 20, paramValue = 2) is WriteResult.AlreadyExists
    )
  }

  @Test
  fun `modify existing member succeeds`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    assertEquals(
      WriteResult.Success,
      writeMember(s, memberId = 1, actionId = 20, paramValue = 2, type = Update.Type.MODIFY),
    )
  }

  @Test
  fun `modify non-existent member returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeMember(s, memberId = 99, actionId = 10, paramValue = 1, type = Update.Type.MODIFY)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `delete existing member succeeds`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    assertEquals(
      WriteResult.Success,
      writeMember(s, memberId = 1, actionId = 10, paramValue = 1, type = Update.Type.DELETE),
    )
  }

  @Test
  fun `delete non-existent member returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeMember(s, memberId = 99, actionId = 10, paramValue = 1, type = Update.Type.DELETE)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `insert duplicate group returns AlreadyExists`() {
    val s = storeWithProfile()
    assertEquals(WriteResult.Success, writeGroup(s, groupId = 1, memberIds = listOf(0)))
    assertTrue(writeGroup(s, groupId = 1, memberIds = listOf(0)) is WriteResult.AlreadyExists)
  }

  @Test
  fun `modify existing group succeeds`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    assertEquals(
      WriteResult.Success,
      writeGroup(s, groupId = 1, memberIds = listOf(0, 1), type = Update.Type.MODIFY),
    )
  }

  @Test
  fun `modify non-existent group returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeGroup(s, groupId = 99, memberIds = listOf(0), type = Update.Type.MODIFY)
        is WriteResult.NotFound
    )
  }

  @Test
  fun `delete existing group succeeds`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    assertEquals(
      WriteResult.Success,
      writeGroup(s, groupId = 1, memberIds = listOf(0), type = Update.Type.DELETE),
    )
  }

  @Test
  fun `delete non-existent group returns NotFound`() {
    val s = storeWithProfile()
    assertTrue(
      writeGroup(s, groupId = 99, memberIds = listOf(0), type = Update.Type.DELETE)
        is WriteResult.NotFound
    )
  }

  // ---------------------------------------------------------------------------
  // Action profile reads
  // ---------------------------------------------------------------------------

  @Test
  fun `readProfileMembers wildcard returns all members`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 2, actionId = 20, paramValue = 2)
    val results = s.readProfileMembers()
    assertEquals(2, results.size)
    assertTrue(results.all { it.hasActionProfileMember() })
  }

  @Test
  fun `readProfileMembers with profile filter returns only that profile`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    // Members from a different profile (id=999) should not be returned.
    s.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileMember(
              P4RuntimeOuterClass.ActionProfileMember.newBuilder()
                .setActionProfileId(999)
                .setMemberId(1)
                .setAction(Action.newBuilder().setActionId(10))
            )
        )
        .build()
    )
    val filter =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder().setActionProfileId(PROFILE_ID).build()
    val results = s.readProfileMembers(filter)
    assertEquals(1, results.size)
    assertEquals(PROFILE_ID, results[0].actionProfileMember.actionProfileId)
  }

  @Test
  fun `readProfileMembers with member filter returns single member`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    writeMember(s, memberId = 2, actionId = 20, paramValue = 2)
    val filter =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(PROFILE_ID)
        .setMemberId(1)
        .build()
    val results = s.readProfileMembers(filter)
    assertEquals(1, results.size)
    assertEquals(1, results[0].actionProfileMember.memberId)
  }

  @Test
  fun `readProfileMembers non-matching returns empty`() {
    val s = storeWithProfile()
    writeMember(s, memberId = 1, actionId = 10, paramValue = 1)
    val filter =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(PROFILE_ID)
        .setMemberId(99)
        .build()
    assertTrue(s.readProfileMembers(filter).isEmpty())
  }

  @Test
  fun `readProfileGroups wildcard returns all groups`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    writeGroup(s, groupId = 2, memberIds = listOf(0))
    val results = s.readProfileGroups()
    assertEquals(2, results.size)
    assertTrue(results.all { it.hasActionProfileGroup() })
  }

  @Test
  fun `readProfileGroups with group filter returns single group`() {
    val s = storeWithProfile()
    writeGroup(s, groupId = 1, memberIds = listOf(0))
    writeGroup(s, groupId = 2, memberIds = listOf(0))
    val filter =
      P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
        .setActionProfileId(PROFILE_ID)
        .setGroupId(1)
        .build()
    val results = s.readProfileGroups(filter)
    assertEquals(1, results.size)
    assertEquals(1, results[0].actionProfileGroup.groupId)
  }

  // ---------------------------------------------------------------------------
  // Register write semantics
  // ---------------------------------------------------------------------------

  /** Creates a TableStore with register metadata for register tests. */
  private fun storeWithRegister(): TableStore {
    val store = TableStore()
    store.loadMappings(
      tableNameById = mapOf(TABLE_ID to TABLE_NAME),
      actionNameById = ACTION_ID_TO_NAME,
      p4infoRegisters =
        listOf(buildRegisterProto(REGISTER_ID, REGISTER_NAME, REGISTER_BITWIDTH, REGISTER_SIZE)),
    )
    return store
  }

  private fun buildRegisterProto(
    id: Int,
    name: String,
    bitwidth: Int,
    size: Int,
  ): P4InfoOuterClass.Register =
    P4InfoOuterClass.Register.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name))
      .setTypeSpec(
        p4.config.v1.P4Types.P4DataTypeSpec.newBuilder()
          .setBitstring(
            p4.config.v1.P4Types.P4BitstringLikeTypeSpec.newBuilder()
              .setBit(p4.config.v1.P4Types.P4BitTypeSpec.newBuilder().setBitwidth(bitwidth))
          )
      )
      .setSize(size)
      .build()

  private fun registerUpdate(
    type: Update.Type,
    registerId: Int = REGISTER_ID,
    index: Long = 0,
    value: Long = 0,
  ): Update {
    val entry =
      P4RuntimeOuterClass.RegisterEntry.newBuilder()
        .setRegisterId(registerId)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(index))
        .setData(
          p4.v1.P4DataOuterClass.P4Data.newBuilder()
            .setBitstring(ByteString.copyFrom(longToBytes(value, (REGISTER_BITWIDTH + 7) / 8)))
        )
        .build()
    return Update.newBuilder()
      .setType(type)
      .setEntity(Entity.newBuilder().setRegisterEntry(entry))
      .build()
  }

  private fun longToBytes(value: Long, byteLen: Int): ByteArray {
    val bytes = ByteArray(byteLen)
    for (i in 0 until byteLen) {
      bytes[byteLen - 1 - i] = (value shr (i * 8) and 0xFF).toByte()
    }
    return bytes
  }

  @Test
  fun `modify register entry persists value`() {
    val s = storeWithRegister()
    assertEquals(WriteResult.Success, s.write(registerUpdate(Update.Type.MODIFY, value = 42)))
    val readBack = s.registerRead(REGISTER_NAME, 0)
    assertNotNull(readBack)
    assertEquals(BitVal(42, REGISTER_BITWIDTH), readBack)
  }

  @Test
  fun `modify register entry at out-of-bounds index returns error`() {
    val s = storeWithRegister()
    val result = s.write(registerUpdate(Update.Type.MODIFY, index = REGISTER_SIZE.toLong()))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `insert register entry returns InvalidArgument`() {
    val s = storeWithRegister()
    val result = s.write(registerUpdate(Update.Type.INSERT))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `delete register entry returns InvalidArgument`() {
    val s = storeWithRegister()
    val result = s.write(registerUpdate(Update.Type.DELETE))
    assertTrue("expected InvalidArgument", result is WriteResult.InvalidArgument)
  }

  @Test
  fun `modify register with unknown ID returns NotFound`() {
    val s = storeWithRegister()
    val result = s.write(registerUpdate(Update.Type.MODIFY, registerId = 999))
    assertTrue("expected NotFound", result is WriteResult.NotFound)
  }

  // ---------------------------------------------------------------------------
  // Register reads
  // ---------------------------------------------------------------------------

  @Test
  fun `readRegisterEntries single index returns written value`() {
    val s = storeWithRegister()
    s.write(registerUpdate(Update.Type.MODIFY, index = 1, value = 0xABCD))
    val filter =
      P4RuntimeOuterClass.RegisterEntry.newBuilder()
        .setRegisterId(REGISTER_ID)
        .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(1))
        .build()
    val results = s.readRegisterEntries(filter)
    assertEquals(1, results.size)
    val entry = results[0].registerEntry
    assertEquals(REGISTER_ID, entry.registerId)
    assertEquals(1, entry.index.index)
    assertEquals(
      ByteString.copyFrom(longToBytes(0xABCD, (REGISTER_BITWIDTH + 7) / 8)),
      entry.data.bitstring,
    )
  }

  @Test
  fun `readRegisterEntries all indices returns full array with defaults`() {
    val s = storeWithRegister()
    s.write(registerUpdate(Update.Type.MODIFY, index = 2, value = 99))
    val filter = P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(REGISTER_ID).build()
    val results = s.readRegisterEntries(filter)
    assertEquals(REGISTER_SIZE, results.size)
    // Index 2 has value 99, others default to 0.
    for (entity in results) {
      val entry = entity.registerEntry
      val expected = if (entry.index.index == 2L) 99L else 0L
      assertEquals(
        ByteString.copyFrom(longToBytes(expected, (REGISTER_BITWIDTH + 7) / 8)),
        entry.data.bitstring,
      )
    }
  }

  @Test
  fun `readRegisterEntries wildcard returns all registers`() {
    val s = TableStore()
    s.loadMappings(
      tableNameById = emptyMap(),
      actionNameById = emptyMap(),
      p4infoRegisters =
        listOf(
          buildRegisterProto(REGISTER_ID, REGISTER_NAME, REGISTER_BITWIDTH, 2),
          buildRegisterProto(REGISTER_ID + 1, "otherRegister", 8, 1),
        ),
    )
    s.write(registerUpdate(Update.Type.MODIFY, registerId = REGISTER_ID, index = 0, value = 1))
    val filter = P4RuntimeOuterClass.RegisterEntry.getDefaultInstance()
    val results = s.readRegisterEntries(filter)
    // 2 entries from first register + 1 from second = 3
    assertEquals(3, results.size)
  }

  @Test
  fun `readRegisterEntries unknown register returns empty`() {
    val s = storeWithRegister()
    val filter = P4RuntimeOuterClass.RegisterEntry.newBuilder().setRegisterId(999).build()
    assertTrue(s.readRegisterEntries(filter).isEmpty())
  }

  // ---------------------------------------------------------------------------
  // Per-entry reads
  // ---------------------------------------------------------------------------

  @Test
  fun `readEntities with match filter returns only matching entry`() {
    val entry1 = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    val entry2 = exactEntry(fieldId = 1, value = byteArrayOf(200.toByte()), actionId = 20)
    store.write(insertUpdate(entry1))
    store.write(insertUpdate(entry2))

    val filter = TableEntry.newBuilder().setTableId(TABLE_ID).addMatch(entry1.getMatch(0)).build()
    val results = store.readEntities(filter)
    assertEquals("should return exactly one entry", 1, results.size)
    assertEquals(entry1.matchList, results[0].tableEntry.matchList)
  }

  @Test
  fun `readEntities with non-matching filter returns empty`() {
    val entry = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    store.write(insertUpdate(entry))

    val noMatchFilter =
      TableEntry.newBuilder()
        .setTableId(TABLE_ID)
        .addMatch(
          FieldMatch.newBuilder()
            .setFieldId(1)
            .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(99))))
        )
        .build()
    assertTrue(store.readEntities(noMatchFilter).isEmpty())
  }

  @Test
  fun `readEntities with table-only filter returns all entries in table`() {
    val entry1 = exactEntry(fieldId = 1, value = byteArrayOf(100), actionId = 10)
    val entry2 = exactEntry(fieldId = 1, value = byteArrayOf(200.toByte()), actionId = 20)
    store.write(insertUpdate(entry1))
    store.write(insertUpdate(entry2))

    // Filter with table_id only, no match fields → returns all entries in the table.
    val tableOnlyFilter = TableEntry.newBuilder().setTableId(TABLE_ID).build()
    assertEquals(2, store.readEntities(tableOnlyFilter).size)
  }

  @Test
  fun `readEntities distinguishes ternary entries by priority`() {
    // Two ternary entries with the same match key but different priorities are distinct.
    val entry1 =
      ternaryEntry(
        fieldId = 1,
        value = byteArrayOf(0x0A),
        mask = byteArrayOf(0xFF.toByte()),
        priority = 10,
        actionId = 10,
      )
    val entry2 =
      ternaryEntry(
        fieldId = 1,
        value = byteArrayOf(0x0A),
        mask = byteArrayOf(0xFF.toByte()),
        priority = 20,
        actionId = 20,
      )
    store.write(insertUpdate(entry1))
    store.write(insertUpdate(entry2))

    // Filter for priority=10 should return only entry1.
    val filter =
      TableEntry.newBuilder()
        .setTableId(TABLE_ID)
        .addMatch(entry1.getMatch(0))
        .setPriority(10)
        .build()
    val results = store.readEntities(filter)
    assertEquals("should return exactly one entry", 1, results.size)
    assertEquals(10, results[0].tableEntry.priority)
  }

  @Test
  fun `readEntities wildcard returns all entries across tables`() {
    store.loadMappings(
      tableNameById = mapOf(TABLE_ID to TABLE_NAME, 3 to "otherTable"),
      actionNameById = ACTION_ID_TO_NAME,
    )
    val entry1 = exactEntry(fieldId = 1, value = byteArrayOf(1), actionId = 10)
    val entry2 =
      TableEntry.newBuilder()
        .setTableId(3)
        .addMatch(
          FieldMatch.newBuilder()
            .setFieldId(1)
            .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(2))))
        )
        .setAction(TableAction.newBuilder().setAction(Action.newBuilder().setActionId(10)))
        .build()
    store.write(insertUpdate(entry1))
    store.write(insertUpdate(entry2))

    // Default filter (table_id=0, no match fields) → wildcard.
    assertEquals(2, store.readEntities().size)
  }

  private fun insertUpdate(entry: TableEntry): Update =
    Update.newBuilder()
      .setType(Update.Type.INSERT)
      .setEntity(Entity.newBuilder().setTableEntry(entry))
      .build()

  // ---------------------------------------------------------------------------
  // Constants
  // ---------------------------------------------------------------------------

  companion object {
    private const val TABLE_ID = 1
    private const val TABLE_NAME = "myTable"
    private const val PROFILE_TABLE_ID = 2
    private const val PROFILE_TABLE_NAME = "selectorTable"
    private const val PROFILE_ID = 100
    private const val REGISTER_ID = 500
    private const val REGISTER_NAME = "myRegister"
    private const val REGISTER_BITWIDTH = 32
    private const val REGISTER_SIZE = 4
    private val ACTION_ID_TO_NAME =
      listOf(10, 20, 42, 50, 77, 99, 100, 200).associateWith { "action$it" }
  }
}
