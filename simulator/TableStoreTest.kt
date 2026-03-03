package fourward.simulator

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

  // ---------------------------------------------------------------------------
  // Constants
  // ---------------------------------------------------------------------------

  companion object {
    private const val TABLE_ID = 1
    private const val TABLE_NAME = "myTable"
    private val ACTION_ID_TO_NAME =
      listOf(10, 20, 42, 50, 77, 99, 100, 200).associateWith { "action$it" }
  }
}
