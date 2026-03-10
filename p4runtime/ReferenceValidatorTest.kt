package fourward.p4runtime

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [ReferenceValidator].
 *
 * Tests annotation parsing and referential integrity checking without a simulator — uses lambda
 * stubs for entry-existence checks.
 */
class ReferenceValidatorTest {

  // =========================================================================
  // Construction: annotation parsing
  // =========================================================================

  @Test
  fun `create returns null when no refers_to annotations exist`() {
    val p4info = p4InfoWithoutAnnotations()
    assertNull(ReferenceValidator.create(p4info))
  }

  @Test
  fun `create parses match field refers_to annotation`() {
    val p4info = p4InfoWithMatchFieldRef()
    assertNotNull(ReferenceValidator.create(p4info))
  }

  @Test
  fun `create parses action param refers_to annotation`() {
    val p4info = p4InfoWithActionParamRef()
    assertNotNull(ReferenceValidator.create(p4info))
  }

  @Test
  fun `create handles builtin multicast_group_table with spaces`() {
    // p4c emits "builtin : : multicast_group_table" with spaces around colons.
    val p4info = p4InfoWithMulticastRef()
    assertNotNull(ReferenceValidator.create(p4info))
  }

  // =========================================================================
  // Match field validation
  // =========================================================================

  @Test
  fun `insert with existing referenced entry passes`() {
    val v = validatorWithMatchFieldRef()
    // Referenced entry exists.
    v.validate(
      insertUpdate(REFERRING_TABLE_ID, exactMatch(REF_FIELD_ID, VALUE_A)),
      entryExists = { _, _, value -> value == VALUE_A },
      multicastGroupExists = { false },
    )
  }

  @Test
  fun `insert with missing referenced entry throws INVALID_ARGUMENT`() {
    val v = validatorWithMatchFieldRef()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(REFERRING_TABLE_ID, exactMatch(REF_FIELD_ID, VALUE_A)),
          entryExists = { _, _, _ -> false }, // Nothing exists.
          multicastGroupExists = { false },
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("@refers_to"))
  }

  @Test
  fun `delete skips referential integrity check`() {
    val v = validatorWithMatchFieldRef()
    // DELETE should pass even though the reference doesn't exist.
    v.validate(
      deleteUpdate(REFERRING_TABLE_ID, exactMatch(REF_FIELD_ID, VALUE_A)),
      entryExists = { _, _, _ -> false },
      multicastGroupExists = { false },
    )
  }

  @Test
  fun `modify with missing referenced entry throws INVALID_ARGUMENT`() {
    val v = validatorWithMatchFieldRef()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          modifyUpdate(REFERRING_TABLE_ID, exactMatch(REF_FIELD_ID, VALUE_A)),
          entryExists = { _, _, _ -> false },
          multicastGroupExists = { false },
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  @Test
  fun `non-annotated match field is not checked`() {
    val v = validatorWithMatchFieldRef()
    // Field ID 99 has no @refers_to — should pass regardless.
    v.validate(
      insertUpdate(REFERRING_TABLE_ID, exactMatch(fieldId = 99, VALUE_A)),
      entryExists = { _, _, _ -> false },
      multicastGroupExists = { false },
    )
  }

  @Test
  fun `optional match field value is checked`() {
    val v = validatorWithOptionalFieldRef()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertWithOptionalMatch(REFERRING_TABLE_ID, REF_FIELD_ID, VALUE_A),
          entryExists = { _, _, _ -> false },
          multicastGroupExists = { false },
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  // =========================================================================
  // Action param validation
  // =========================================================================

  @Test
  fun `action param with existing referenced entry passes`() {
    val v = validatorWithActionParamRef()
    v.validate(
      insertWithAction(REF_ACTION_ID, paramWithValue(REF_PARAM_ID, VALUE_A)),
      entryExists = { _, _, value -> value == VALUE_A },
      multicastGroupExists = { false },
    )
  }

  @Test
  fun `action param with missing referenced entry throws INVALID_ARGUMENT`() {
    val v = validatorWithActionParamRef()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertWithAction(REF_ACTION_ID, paramWithValue(REF_PARAM_ID, VALUE_A)),
          entryExists = { _, _, _ -> false },
          multicastGroupExists = { false },
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("@refers_to"))
  }

  @Test
  fun `non-annotated action param is not checked`() {
    val v = validatorWithActionParamRef()
    // Param ID 99 has no @refers_to — should pass.
    v.validate(
      insertWithAction(REF_ACTION_ID, paramWithValue(paramId = 99, VALUE_A)),
      entryExists = { _, _, _ -> false },
      multicastGroupExists = { false },
    )
  }

  // =========================================================================
  // Builtin multicast_group_table
  // =========================================================================

  @Test
  fun `multicast group reference with existing group passes`() {
    val v = validatorWithMulticastRef()
    v.validate(
      insertWithAction(MCAST_ACTION_ID, paramWithValue(MCAST_PARAM_ID, mcastGroupId(42))),
      entryExists = { _, _, _ -> false },
      multicastGroupExists = { id -> id == 42 },
    )
  }

  @Test
  fun `multicast group reference with missing group throws INVALID_ARGUMENT`() {
    val v = validatorWithMulticastRef()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertWithAction(MCAST_ACTION_ID, paramWithValue(MCAST_PARAM_ID, mcastGroupId(42))),
          entryExists = { _, _, _ -> false },
          multicastGroupExists = { false },
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("multicast"))
  }

  // =========================================================================
  // Unresolvable references (table/field not in p4info)
  // =========================================================================

  @Test
  fun `annotation referencing unknown table is silently skipped`() {
    // The annotation references "nonexistent_table" which isn't in p4info.
    val p4info = p4InfoWithUnknownTableRef()
    // Should still create a validator (other annotations may exist), but this
    // particular reference is silently dropped.
    val v = ReferenceValidator.create(p4info)
    // If no other annotations exist, create returns null.
    assertNull(v)
  }

  // =========================================================================
  // Test fixtures
  // =========================================================================

  companion object {
    private const val REFERRING_TABLE_ID = 1
    private const val TARGET_TABLE_ID = 2
    private const val REF_FIELD_ID = 1
    private const val TARGET_FIELD_ID = 1
    private const val REF_ACTION_ID = 10
    private const val REF_PARAM_ID = 1
    private const val MCAST_ACTION_ID = 20
    private const val MCAST_PARAM_ID = 1
    private const val NO_ACTION_ID = 100

    private val VALUE_A: ByteString = ByteString.copyFrom(byteArrayOf(0, 1))
  }

  // -------------------------------------------------------------------------
  // Validator factories
  // -------------------------------------------------------------------------

  private fun validatorWithMatchFieldRef(): ReferenceValidator =
    ReferenceValidator.create(p4InfoWithMatchFieldRef())!!

  private fun validatorWithOptionalFieldRef(): ReferenceValidator =
    ReferenceValidator.create(p4InfoWithOptionalFieldRef())!!

  private fun validatorWithActionParamRef(): ReferenceValidator =
    ReferenceValidator.create(p4InfoWithActionParamRef())!!

  private fun validatorWithMulticastRef(): ReferenceValidator =
    ReferenceValidator.create(p4InfoWithMulticastRef())!!

  // -------------------------------------------------------------------------
  // p4info builders
  // -------------------------------------------------------------------------

  /** p4info with no @refers_to annotations. */
  private fun p4InfoWithoutAnnotations(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        table(REFERRING_TABLE_ID, "t1", matchField(REF_FIELD_ID, "f1"), actionRef(NO_ACTION_ID))
      )
      .addActions(action(NO_ACTION_ID, "no_action"))
      .build()

  /** p4info where a match field has @refers_to pointing to another table's match field. */
  private fun p4InfoWithMatchFieldRef(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        table(
          REFERRING_TABLE_ID,
          "referring_table",
          matchField(REF_FIELD_ID, "ref_id", "@refers_to(target_table , target_id)"),
          actionRef(NO_ACTION_ID),
        )
      )
      .addTables(
        table(
          TARGET_TABLE_ID, "target_table",
          matchField(TARGET_FIELD_ID, "target_id"), actionRef(NO_ACTION_ID),
        )
      )
      .addActions(action(NO_ACTION_ID, "no_action"))
      .build()

  /** p4info where an optional match field has @refers_to. */
  private fun p4InfoWithOptionalFieldRef(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        table(
          REFERRING_TABLE_ID,
          "referring_table",
          matchField(
            REF_FIELD_ID,
            "ref_id",
            "@refers_to(target_table , target_id)",
            P4InfoOuterClass.MatchField.MatchType.OPTIONAL,
          ),
          actionRef(NO_ACTION_ID),
        )
      )
      .addTables(
        table(
          TARGET_TABLE_ID, "target_table",
          matchField(TARGET_FIELD_ID, "target_id"), actionRef(NO_ACTION_ID),
        )
      )
      .addActions(action(NO_ACTION_ID, "no_action"))
      .build()

  /** p4info where an action param has @refers_to. */
  private fun p4InfoWithActionParamRef(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        table(
          REFERRING_TABLE_ID,
          "referring_table",
          matchField(REF_FIELD_ID, "key"),
          actionRef(REF_ACTION_ID),
        )
      )
      .addTables(
        table(
          TARGET_TABLE_ID, "target_table",
          matchField(TARGET_FIELD_ID, "target_id"), actionRef(NO_ACTION_ID),
        )
      )
      .addActions(
        action(
          REF_ACTION_ID,
          "set_ref",
          param(REF_PARAM_ID, "ref_id", "@refers_to(target_table , target_id)"),
        )
      )
      .addActions(action(NO_ACTION_ID, "no_action"))
      .build()

  /** p4info where an action param references builtin::multicast_group_table (with spaces). */
  private fun p4InfoWithMulticastRef(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        table(
          REFERRING_TABLE_ID,
          "mcast_table",
          matchField(REF_FIELD_ID, "key"),
          actionRef(MCAST_ACTION_ID),
        )
      )
      .addActions(
        action(
          MCAST_ACTION_ID,
          "set_mcast",
          // p4c emits spaces around "::" in annotations.
          param(
            MCAST_PARAM_ID, "mcast_id",
            "@refers_to(builtin : : multicast_group_table , multicast_group_id)",
          ),
        )
      )
      .build()

  /** p4info where an annotation references a table not present in p4info. */
  private fun p4InfoWithUnknownTableRef(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        table(
          REFERRING_TABLE_ID,
          "t1",
          matchField(REF_FIELD_ID, "f1", "@refers_to(nonexistent_table , some_field)"),
          actionRef(NO_ACTION_ID),
        )
      )
      .addActions(action(NO_ACTION_ID, "no_action"))
      .build()

  // -------------------------------------------------------------------------
  // p4info element builders
  // -------------------------------------------------------------------------

  private fun table(
    id: Int,
    name: String,
    matchField: P4InfoOuterClass.MatchField,
    actionRef: P4InfoOuterClass.ActionRef,
  ): P4InfoOuterClass.Table =
    P4InfoOuterClass.Table.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name).setAlias(name))
      .addMatchFields(matchField)
      .addActionRefs(actionRef)
      .build()

  private fun matchField(
    id: Int,
    name: String,
    annotation: String? = null,
    matchType: P4InfoOuterClass.MatchField.MatchType = P4InfoOuterClass.MatchField.MatchType.EXACT,
  ): P4InfoOuterClass.MatchField {
    val builder =
      P4InfoOuterClass.MatchField.newBuilder()
        .setId(id)
        .setName(name)
        .setBitwidth(16)
        .setMatchType(matchType)
    if (annotation != null) builder.addAnnotations(annotation)
    return builder.build()
  }

  private fun actionRef(id: Int): P4InfoOuterClass.ActionRef =
    P4InfoOuterClass.ActionRef.newBuilder().setId(id).build()

  private fun action(
    id: Int,
    name: String,
    vararg params: P4InfoOuterClass.Action.Param,
  ): P4InfoOuterClass.Action =
    P4InfoOuterClass.Action.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name).setAlias(name))
      .addAllParams(params.toList())
      .build()

  private fun param(
    id: Int,
    name: String,
    annotation: String? = null,
  ): P4InfoOuterClass.Action.Param {
    val builder =
      P4InfoOuterClass.Action.Param.newBuilder().setId(id).setName(name).setBitwidth(16)
    if (annotation != null) builder.addAnnotations(annotation)
    return builder.build()
  }

  // -------------------------------------------------------------------------
  // Update builders
  // -------------------------------------------------------------------------

  private fun exactMatch(fieldId: Int, value: ByteString): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(value))
      .build()

  private fun insertUpdate(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setTableEntry(
            P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(tableId).addMatch(match)
          )
      )
      .build()

  private fun deleteUpdate(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.DELETE)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setTableEntry(
            P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(tableId).addMatch(match)
          )
      )
      .build()

  private fun modifyUpdate(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.MODIFY)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setTableEntry(
            P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(tableId).addMatch(match)
          )
      )
      .build()

  private fun insertWithOptionalMatch(
    tableId: Int,
    fieldId: Int,
    value: ByteString,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setTableEntry(
            P4RuntimeOuterClass.TableEntry.newBuilder()
              .setTableId(tableId)
              .addMatch(
                P4RuntimeOuterClass.FieldMatch.newBuilder()
                  .setFieldId(fieldId)
                  .setOptional(
                    P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(value)
                  )
              )
          )
      )
      .build()

  private fun insertWithAction(
    actionId: Int,
    param: P4RuntimeOuterClass.Action.Param,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setTableEntry(
            P4RuntimeOuterClass.TableEntry.newBuilder()
              .setTableId(REFERRING_TABLE_ID)
              .addMatch(exactMatch(REF_FIELD_ID, VALUE_A))
              .setAction(
                P4RuntimeOuterClass.TableAction.newBuilder()
                  .setAction(
                    P4RuntimeOuterClass.Action.newBuilder()
                      .setActionId(actionId)
                      .addParams(param)
                  )
              )
          )
      )
      .build()

  private fun paramWithValue(
    paramId: Int,
    value: ByteString,
  ): P4RuntimeOuterClass.Action.Param =
    P4RuntimeOuterClass.Action.Param.newBuilder().setParamId(paramId).setValue(value).build()

  /** Encodes a multicast group ID as a 2-byte big-endian ByteString. */
  @Suppress("MagicNumber")
  private fun mcastGroupId(id: Int): ByteString =
    ByteString.copyFrom(byteArrayOf((id shr 8).toByte(), (id and 0xFF).toByte()))
}
