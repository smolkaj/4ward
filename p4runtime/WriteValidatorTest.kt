package fourward.p4runtime

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [WriteValidator].
 *
 * Tests validation logic directly without the gRPC service or simulator. Each test constructs a
 * synthetic p4info and checks that the validator accepts valid updates and rejects invalid ones
 * with the correct gRPC status code.
 */
class WriteValidatorTest {

  // =========================================================================
  // Valid updates — should not throw
  // =========================================================================

  @Test
  fun `valid insert passes validation`() {
    val v = validator()
    v.validate(insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action()))
  }

  @Test
  fun `delete skips content validation`() {
    // DELETE with a bogus action ID — should pass because DELETE only needs the match key.
    val v = validator()
    v.validate(
      deleteUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action(actionId = 99999))
    )
  }

  // =========================================================================
  // Table ID validation
  // =========================================================================

  @Test
  fun `unknown table ID returns NOT_FOUND`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(99999, exactMatch(MATCH_FIELD_ID, bytes(2)), action()))
      }
    assertEquals(Status.Code.NOT_FOUND, e.status.code)
  }

  // =========================================================================
  // Action validation (§9.1.2)
  // =========================================================================

  @Test
  fun `unknown action ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action(actionId = 99))
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  @Test
  fun `action not in table action_refs returns INVALID_ARGUMENT`() {
    val v = validator()
    // OTHER_ACTION_ID exists in p4info but is not in the exact table's action_refs.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(actionId = OTHER_ACTION_ID),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("not valid for table"))
  }

  @Test
  fun `wrong param count returns INVALID_ARGUMENT`() {
    val v = validator()
    // forward expects 1 param; send 0.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(params = emptyList()),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("expects"))
  }

  @Test
  fun `unknown param ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(params = listOf(param(paramId = 99, value = bytes(2)))),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("unknown param ID"))
  }

  @Test
  fun `wrong param byte width returns INVALID_ARGUMENT`() {
    val v = validator()
    // Param is 16-bit (2 bytes); send 1 byte.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(params = listOf(param(value = bytes(1)))),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("bytes"))
  }

  @Test
  fun `zero-bitwidth param skips width check`() {
    // Simulates @p4runtime_translation with sdn_string where bitwidth is 0.
    val v = validatorWithZeroBitwidthParam()
    v.validate(
      insertUpdate(
        EXACT_TABLE_ID,
        exactMatch(MATCH_FIELD_ID, bytes(2)),
        action(params = listOf(param(value = bytes(5)))),
      )
    )
  }

  // =========================================================================
  // Match field validation (§9.1.1)
  // =========================================================================

  @Test
  fun `unknown match field ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(EXACT_TABLE_ID, exactMatch(fieldId = 99, bytes(2)), action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("unknown match field ID"))
  }

  @Test
  fun `wrong match kind returns INVALID_ARGUMENT`() {
    val v = validator()
    // Table expects EXACT; send TERNARY.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(EXACT_TABLE_ID, ternaryMatch(MATCH_FIELD_ID, bytes(2)), action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("EXACT"))
  }

  @Test
  fun `wrong match value width returns INVALID_ARGUMENT`() {
    val v = validator()
    // Match field is 16-bit (2 bytes); send 1 byte.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(1)), action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("bytes"))
  }

  @Test
  fun `missing required exact field returns INVALID_ARGUMENT`() {
    val v = validator()
    // No match fields at all.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(EXACT_TABLE_ID, matches = emptyList(), action = action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("missing"))
  }

  @Test
  fun `ternary match value and mask widths validated`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            TERNARY_TABLE_ID,
            ternaryMatch(TERNARY_FIELD_ID, value = bytes(1), mask = bytes(2)),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("value"))
  }

  @Test
  fun `LPM match value width validated`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            LPM_TABLE_ID,
            lpmMatch(LPM_FIELD_ID, value = bytes(1), prefixLen = 8),
            ternaryAction(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  // =========================================================================
  // Priority validation (§9.1.1)
  // =========================================================================

  @Test
  fun `exact table with nonzero priority returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action(), priority = 5)
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("priority"))
  }

  @Test
  fun `ternary table with zero priority returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            TERNARY_TABLE_ID,
            ternaryMatch(TERNARY_FIELD_ID, bytes(2)),
            ternaryAction(),
            priority = 0,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("priority"))
  }

  // =========================================================================
  // Ternary/LPM semantic validation (§8.3)
  // =========================================================================

  @Test
  fun `ternary value with bits set outside mask returns INVALID_ARGUMENT`() {
    val v = validator()
    // value = 0x0F00, mask = 0x00FF → byte 0 has bits set where mask is 0.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            TERNARY_TABLE_ID,
            ternaryMatch(
              TERNARY_FIELD_ID,
              value = byteArrayOf(0x0F, 0x00),
              mask = byteArrayOf(0x00, 0xFF.toByte()),
            ),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("masked"))
  }

  @Test
  fun `ternary value properly masked passes`() {
    val v = validator()
    // value = 0x00AB, mask = 0x00FF → value & ~mask == 0.
    v.validate(
      insertUpdate(
        TERNARY_TABLE_ID,
        ternaryMatch(
          TERNARY_FIELD_ID,
          value = byteArrayOf(0x00, 0xAB.toByte()),
          mask = byteArrayOf(0x00, 0xFF.toByte()),
        ),
        ternaryAction(),
        priority = 1,
      )
    )
  }

  @Test
  fun `LPM value with bits set beyond prefix_len returns INVALID_ARGUMENT`() {
    val v = validator()
    // 16-bit field, prefix_len = 8 → second byte must be zero.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            LPM_TABLE_ID,
            lpmMatch(LPM_FIELD_ID, value = byteArrayOf(0xFF.toByte(), 0x01), prefixLen = 8),
            ternaryAction(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("prefix"))
  }

  @Test
  fun `LPM value with partial byte correctly masked passes`() {
    val v = validator()
    // 16-bit field, prefix_len = 12 → second byte low 4 bits must be zero. 0xF0 is valid.
    v.validate(
      insertUpdate(
        LPM_TABLE_ID,
        lpmMatch(LPM_FIELD_ID, value = byteArrayOf(0xFF.toByte(), 0xF0.toByte()), prefixLen = 12),
        ternaryAction(),
      )
    )
  }

  @Test
  fun `LPM value with partial byte bits set returns INVALID_ARGUMENT`() {
    val v = validator()
    // 16-bit field, prefix_len = 12 → second byte low 4 bits must be zero. 0xFF violates.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            LPM_TABLE_ID,
            lpmMatch(
              LPM_FIELD_ID,
              value = byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
              prefixLen = 12,
            ),
            ternaryAction(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("prefix"))
  }

  // =========================================================================
  // Duplicate match field validation (§9.1)
  // =========================================================================

  @Test
  fun `duplicate match field ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            matches =
              listOf(exactMatch(MATCH_FIELD_ID, bytes(2)), exactMatch(MATCH_FIELD_ID, bytes(2))),
            action = action(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("duplicate"))
  }

  // =========================================================================
  // Priority validation (§9.1.1)
  // =========================================================================

  @Test
  fun `ternary table with positive priority passes`() {
    val v = validator()
    v.validate(
      insertUpdate(
        TERNARY_TABLE_ID,
        ternaryMatch(TERNARY_FIELD_ID, bytes(2)),
        ternaryAction(),
        priority = 10,
      )
    )
  }

  // =========================================================================
  // Test fixtures
  // =========================================================================

  companion object {
    private const val EXACT_TABLE_ID = 1
    private const val TERNARY_TABLE_ID = 2
    private const val LPM_TABLE_ID = 3
    private const val MATCH_FIELD_ID = 1
    private const val TERNARY_FIELD_ID = 2
    private const val LPM_FIELD_ID = 3
    private const val ACTION_ID = 10
    private const val OTHER_ACTION_ID = 11
    private const val TERNARY_ACTION_ID = 12
    private const val PARAM_ID = 1
    private const val MATCH_BITWIDTH = 16
    private const val PARAM_BITWIDTH = 16
  }

  private fun validator(): WriteValidator = WriteValidator(testP4Info())

  private fun validatorWithZeroBitwidthParam(): WriteValidator =
    WriteValidator(testP4InfoWithZeroBitwidthParam())

  private fun testP4Info(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(EXACT_TABLE_ID).setName("exact_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(MATCH_FIELD_ID)
              .setName("f1")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(TERNARY_TABLE_ID).setName("ternary_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(TERNARY_FIELD_ID)
              .setName("f2")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.TERNARY)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(TERNARY_ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(LPM_TABLE_ID).setName("lpm_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(LPM_FIELD_ID)
              .setName("f3")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.LPM)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(TERNARY_ACTION_ID))
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID).setName("forward"))
          .addParams(
            P4InfoOuterClass.Action.Param.newBuilder()
              .setId(PARAM_ID)
              .setName("port")
              .setBitwidth(PARAM_BITWIDTH)
          )
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(OTHER_ACTION_ID).setName("other")
          )
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(TERNARY_ACTION_ID).setName("drop")
          )
      )
      .build()

  private fun testP4InfoWithZeroBitwidthParam(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(EXACT_TABLE_ID).setName("exact_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(MATCH_FIELD_ID)
              .setName("f1")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID).setName("forward"))
          .addParams(
            P4InfoOuterClass.Action.Param.newBuilder()
              .setId(PARAM_ID)
              .setName("port")
              .setBitwidth(0) // sdn_string translation — bitwidth 0
          )
      )
      .build()

  // =========================================================================
  // Proto builders
  // =========================================================================

  private fun bytes(len: Int): ByteArray = ByteArray(len)

  private fun exactMatch(
    fieldId: Int = MATCH_FIELD_ID,
    value: ByteArray,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(
        P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value))
      )
      .build()

  private fun ternaryMatch(
    fieldId: Int = TERNARY_FIELD_ID,
    value: ByteArray,
    mask: ByteArray = value,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setTernary(
        P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setMask(ByteString.copyFrom(mask))
      )
      .build()

  private fun lpmMatch(
    fieldId: Int,
    value: ByteArray,
    prefixLen: Int,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setLpm(
        P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setPrefixLen(prefixLen)
      )
      .build()

  private fun param(
    paramId: Int = PARAM_ID,
    value: ByteArray = bytes(PARAM_BITWIDTH / 8),
  ): P4RuntimeOuterClass.Action.Param =
    P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId)
      .setValue(ByteString.copyFrom(value))
      .build()

  private fun action(
    actionId: Int = ACTION_ID,
    params: List<P4RuntimeOuterClass.Action.Param> = listOf(param()),
  ): P4RuntimeOuterClass.Action =
    P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId).addAllParams(params).build()

  private fun ternaryAction(): P4RuntimeOuterClass.Action =
    P4RuntimeOuterClass.Action.newBuilder().setActionId(TERNARY_ACTION_ID).build()

  private fun insertUpdate(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
    action: P4RuntimeOuterClass.Action,
    priority: Int = 0,
  ): P4RuntimeOuterClass.Update = insertUpdate(tableId, listOf(match), action, priority)

  private fun insertUpdate(
    tableId: Int,
    matches: List<P4RuntimeOuterClass.FieldMatch>,
    action: P4RuntimeOuterClass.Action,
    priority: Int = 0,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setTableEntry(
            P4RuntimeOuterClass.TableEntry.newBuilder()
              .setTableId(tableId)
              .addAllMatch(matches)
              .setPriority(priority)
              .setAction(P4RuntimeOuterClass.TableAction.newBuilder().setAction(action))
          )
      )
      .build()

  private fun deleteUpdate(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
    action: P4RuntimeOuterClass.Action,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.DELETE)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setTableEntry(
            P4RuntimeOuterClass.TableEntry.newBuilder()
              .setTableId(tableId)
              .addMatch(match)
              .setAction(P4RuntimeOuterClass.TableAction.newBuilder().setAction(action))
          )
      )
      .build()
}
