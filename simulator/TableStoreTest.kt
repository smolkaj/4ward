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

  private fun writeMember(store: TableStore, memberId: Int, actionId: Int, paramValue: Byte) {
    store.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
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
  }

  private fun writeGroup(store: TableStore, groupId: Int, memberIds: List<Int>) {
    store.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
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
  }

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
  // Constants
  // ---------------------------------------------------------------------------

  companion object {
    private const val TABLE_ID = 1
    private const val TABLE_NAME = "myTable"
    private const val PROFILE_TABLE_ID = 2
    private const val PROFILE_TABLE_NAME = "selectorTable"
    private const val PROFILE_ID = 100
    private val ACTION_ID_TO_NAME =
      listOf(10, 20, 42, 50, 77, 99, 100, 200).associateWith { "action$it" }
  }
}
