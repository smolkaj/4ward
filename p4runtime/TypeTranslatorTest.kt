package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.TranslationEntry
import fourward.ir.TypeTranslation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.config.v1.P4Types
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [TypeTranslator]'s core mapping logic.
 *
 * These test the bidirectional SDN ↔ data-plane value mapping directly, without going through the
 * P4Runtime gRPC service or simulator. Three modes are covered:
 * 1. **Explicit** — all mappings provided upfront; unknown values rejected.
 * 2. **Auto-allocate** — mappings created on first use; data-plane values assigned sequentially.
 * 3. **Hybrid** — explicit pins for known values, auto-allocate for the rest.
 *
 * Both `sdn_bitwidth` (numeric ↔ numeric) and `sdn_string` (string ↔ numeric) are tested.
 */
class TypeTranslatorTest {

  // ===========================================================================
  // Explicit mapping: sdn_bitwidth (numeric → numeric)
  // ===========================================================================

  @Test
  fun `explicit numeric mapping translates SDN to dataplane`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
          addEntries(entry(sdnBytes(2000), dpBytes(2)))
        }
      )

    assertArrayEquals(dpBytes(1), translator.sdnToDataplane("test.port_id", sdnBytes(1000)))
    assertArrayEquals(dpBytes(2), translator.sdnToDataplane("test.port_id", sdnBytes(2000)))
  }

  @Test
  fun `explicit numeric mapping translates dataplane to SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
          addEntries(entry(sdnBytes(2000), dpBytes(2)))
        }
      )

    val result = translator.dataplaneToSdn("test.port_id", dpBytes(1))
    assertEquals(SdnValue.Bitstring(bs(sdnBytes(1000))), result)
  }

  @Test
  fun `explicit mapping rejects unknown SDN value`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = false
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
        }
      )

    assertThrows(TranslationException::class.java) {
      translator.sdnToDataplane("test.port_id", sdnBytes(9999))
    }
  }

  @Test
  fun `explicit mapping rejects unknown dataplane value`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = false
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
        }
      )

    assertThrows(TranslationException::class.java) {
      translator.dataplaneToSdn("test.port_id", dpBytes(99))
    }
  }

  // ===========================================================================
  // Explicit mapping: sdn_string (string → numeric)
  // ===========================================================================

  @Test
  fun `explicit string mapping translates SDN to dataplane`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          addEntries(stringEntry("Ethernet0", dpBytes(0)))
          addEntries(stringEntry("Ethernet1", dpBytes(1)))
          addEntries(stringEntry("CpuPort", dpBytes(510)))
        }
      )

    assertArrayEquals(dpBytes(0), translator.sdnToDataplane("sai.port", "Ethernet0"))
    assertArrayEquals(dpBytes(510), translator.sdnToDataplane("sai.port", "CpuPort"))
  }

  @Test
  fun `explicit string mapping translates dataplane to SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          addEntries(stringEntry("Ethernet0", dpBytes(0)))
        }
      )

    val result = translator.dataplaneToSdn("sai.port", dpBytes(0))
    assertEquals(SdnValue.Str("Ethernet0"), result)
  }

  // ===========================================================================
  // Auto-allocate: sdn_bitwidth
  // ===========================================================================

  @Test
  fun `auto-allocate assigns sequential dataplane values for numeric SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = true
        }
      )

    // First value seen gets dataplane value 0, second gets 1, etc.
    val dp0 = translator.sdnToDataplane("test.port_id", sdnBytes(5000))
    val dp1 = translator.sdnToDataplane("test.port_id", sdnBytes(6000))

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)

    // Same SDN value returns same dataplane value (idempotent).
    assertArrayEquals(dpBytes(0), translator.sdnToDataplane("test.port_id", sdnBytes(5000)))
  }

  @Test
  fun `auto-allocate reverse lookup works after allocation`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = true
        }
      )

    translator.sdnToDataplane("test.port_id", sdnBytes(5000))

    val result = translator.dataplaneToSdn("test.port_id", dpBytes(0))
    assertEquals(SdnValue.Bitstring(bs(sdnBytes(5000))), result)
  }

  // ===========================================================================
  // Auto-allocate: sdn_string
  // ===========================================================================

  @Test
  fun `auto-allocate assigns sequential dataplane values for string SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          autoAllocate = true
        }
      )

    val dp0 = translator.sdnToDataplane("sai.port", "Ethernet0")
    val dp1 = translator.sdnToDataplane("sai.port", "Ethernet1")

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)

    // Idempotent.
    assertArrayEquals(dpBytes(0), translator.sdnToDataplane("sai.port", "Ethernet0"))
  }

  @Test
  fun `auto-allocate string reverse lookup works`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          autoAllocate = true
        }
      )

    translator.sdnToDataplane("sai.port", "Ethernet0")

    val result = translator.dataplaneToSdn("sai.port", dpBytes(0))
    assertEquals(SdnValue.Str("Ethernet0"), result)
  }

  // ===========================================================================
  // Default behavior: no TypeTranslation provided for a URI
  // ===========================================================================

  @Test
  fun `missing TypeTranslation defaults to auto-allocate`() {
    // No TypeTranslation provided — the translator should still handle the URI
    // by auto-allocating.
    val translator = buildTranslator()

    val dp0 = translator.sdnToDataplane("unknown.uri", sdnBytes(42))
    assertArrayEquals(dpBytes(0), dp0)
  }

  // ===========================================================================
  // Hybrid: explicit pins + auto-allocate
  // ===========================================================================

  @Test
  fun `hybrid uses explicit mappings and auto-allocates the rest`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          autoAllocate = true
          addEntries(stringEntry("CpuPort", dpBytes(510)))
          addEntries(stringEntry("DropPort", dpBytes(511)))
        }
      )

    // Explicit entries work.
    assertArrayEquals(dpBytes(510), translator.sdnToDataplane("sai.port", "CpuPort"))
    assertArrayEquals(dpBytes(511), translator.sdnToDataplane("sai.port", "DropPort"))

    // Auto-allocated entries skip reserved dataplane values.
    val dp0 = translator.sdnToDataplane("sai.port", "Ethernet0")
    val dp1 = translator.sdnToDataplane("sai.port", "Ethernet1")

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)
  }

  @Test
  fun `hybrid auto-allocate skips explicitly reserved dataplane values`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.type"
          autoAllocate = true
          // Pin dataplane value 0 to SDN value 100.
          addEntries(entry(sdnBytes(100), dpBytes(0)))
        }
      )

    // Auto-allocate should skip 0 (reserved) and start at 1.
    val dp = translator.sdnToDataplane("test.type", sdnBytes(200))
    assertArrayEquals(dpBytes(1), dp)
  }

  @Test
  fun `auto-allocate skips reserved value zero regardless of encoding width`() {
    // Pin dp value 0 using a 2-byte encoding (\x00\x00). The auto-allocator uses
    // encodeMinWidth(0) which produces 1-byte \x00. Before the fix (reservedValues as
    // Set<ByteString>), ByteString comparison missed this collision.
    val translator =
      buildTranslator(
        translation {
          uri = "test.type"
          autoAllocate = true
          addEntries(entry(sdnBytes(100), byteArrayOf(0, 0)))
        }
      )

    // Auto-allocate must skip 0 (reserved as integer, regardless of byte width).
    val dp = translator.sdnToDataplane("test.type", sdnBytes(200))
    assertArrayEquals(dpBytes(1), dp)
  }

  // ===========================================================================
  // Multiple URIs are independent
  // ===========================================================================

  @Test
  fun `different URIs have independent mapping tables`() {
    val translator =
      buildTranslator(
        translation {
          uri = "type.a"
          autoAllocate = true
        },
        translation {
          uri = "type.b"
          autoAllocate = true
        },
      )

    val dpA = translator.sdnToDataplane("type.a", sdnBytes(42))
    val dpB = translator.sdnToDataplane("type.b", sdnBytes(42))

    // Both get dataplane value 0 — they're independent.
    assertArrayEquals(dpBytes(0), dpA)
    assertArrayEquals(dpBytes(0), dpB)
  }

  // ===========================================================================
  // ActionProfileMember translation via P4Info
  // ===========================================================================

  @Test
  fun `action profile member params are translated on write`() {
    val translator = buildP4InfoTranslator()

    val update = memberUpdate(ACTION_ID, PARAM_ID, sdnBytes(5000))
    val translated = translator.translateForWrite(update)
    val param = translated.entity.actionProfileMember.action.paramsList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), param.value)
  }

  @Test
  fun `action profile member params round-trip through read`() {
    val translator = buildP4InfoTranslator()

    // Forward translate to install the mapping.
    translator.translateForWrite(memberUpdate(ACTION_ID, PARAM_ID, sdnBytes(5000)))

    // Reverse: simulate reading back a data-plane entity.
    val dpMember =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(ACTION_PROFILE_ID)
        .setMemberId(1)
        .setAction(
          P4RuntimeOuterClass.Action.newBuilder()
            .setActionId(ACTION_ID)
            .addParams(
              P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(PARAM_ID)
                .setValue(ByteString.copyFrom(dpBytes(0)))
            )
        )
        .build()
    val dpEntity = P4RuntimeOuterClass.Entity.newBuilder().setActionProfileMember(dpMember).build()
    val sdnEntity = translator.translateForRead(dpEntity)
    val param = sdnEntity.actionProfileMember.action.paramsList.first()
    assertEquals(ByteString.copyFrom(sdnBytes(5000)), param.value)
  }

  // ===========================================================================
  // Match field translation via P4Info
  // ===========================================================================

  @Test
  fun `match field with translated type is discovered from p4info`() {
    val translator = buildP4InfoTranslator()

    // Forward: SDN exact match value → data-plane value.
    val update =
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, sdnBytes(5000), ACTION_ID, PARAM_ID, sdnBytes(1))
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.exact.value)
  }

  @Test
  fun `match field translation round-trips through read`() {
    val translator = buildP4InfoTranslator()

    // Forward translate to install the mapping.
    translator.translateForWrite(
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, sdnBytes(5000), ACTION_ID, PARAM_ID, sdnBytes(1))
    )

    // Reverse: simulate reading back a data-plane entity.
    val dpEntity = readEntity(TABLE_ID, MATCH_FIELD_ID, dpBytes(0), ACTION_ID, PARAM_ID, dpBytes(0))
    val sdnEntity = translator.translateForRead(dpEntity)
    val match = sdnEntity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(sdnBytes(5000)), match.exact.value)
  }

  @Test
  fun `optional match field with translated type is translated`() {
    val translator = buildP4InfoTranslator()

    val update =
      writeUpdateOptionalMatch(
        TABLE_ID,
        MATCH_FIELD_ID,
        sdnBytes(5000),
        ACTION_ID,
        PARAM_ID,
        sdnBytes(1),
      )
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.optional.value)
  }

  @Test
  fun `non-translated match field passes through unchanged`() {
    val translator = buildP4InfoTranslator()

    // Use a match field ID that has no type_name in p4info.
    val rawValue = sdnBytes(42)
    val update =
      writeUpdate(TABLE_ID, NON_TRANSLATED_FIELD_ID, rawValue, ACTION_ID, PARAM_ID, sdnBytes(1))
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    // Match value should be unchanged.
    assertEquals(ByteString.copyFrom(rawValue), match.exact.value)
  }

  // ===========================================================================
  // Match field and action param translation: sdn_string via P4Info
  // ===========================================================================

  @Test
  fun `sdn_string match field write decodes UTF-8 and allocates`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val sdnBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val update = writeUpdate(TABLE_ID, MATCH_FIELD_ID, sdnBytes, ACTION_ID, PARAM_ID, sdnBytes)
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.exact.value)
  }

  @Test
  fun `sdn_string match field round-trips through read`() {
    val translator = buildP4InfoTranslatorWithStringType()

    // Write to install the mapping.
    val sdnBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    translator.translateForWrite(
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, sdnBytes, ACTION_ID, PARAM_ID, sdnBytes)
    )

    // Read back.
    val dpEntity = readEntity(TABLE_ID, MATCH_FIELD_ID, dpBytes(0), ACTION_ID, PARAM_ID, dpBytes(0))
    val sdnEntity = translator.translateForRead(dpEntity)
    val match = sdnEntity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), match.exact.value)
  }

  @Test
  fun `sdn_string action param write decodes UTF-8`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val matchBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val paramBytes = "Ethernet1".toByteArray(Charsets.UTF_8)
    val update = writeUpdate(TABLE_ID, MATCH_FIELD_ID, matchBytes, ACTION_ID, PARAM_ID, paramBytes)
    val translated = translator.translateForWrite(update)
    val param = translated.entity.tableEntry.action.action.paramsList.first()
    // Match allocated "Ethernet0" → dp=0; param allocates "Ethernet1" → dp=1.
    assertEquals(ByteString.copyFrom(dpBytes(1)), param.value)
  }

  @Test
  fun `sdn_string action param round-trips through read`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val matchBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val paramBytes = "Ethernet1".toByteArray(Charsets.UTF_8)
    translator.translateForWrite(
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, matchBytes, ACTION_ID, PARAM_ID, paramBytes)
    )

    val dpEntity = readEntity(TABLE_ID, MATCH_FIELD_ID, dpBytes(0), ACTION_ID, PARAM_ID, dpBytes(1))
    val sdnEntity = translator.translateForRead(dpEntity)
    val param = sdnEntity.tableEntry.action.action.paramsList.first()
    assertEquals(ByteString.copyFromUtf8("Ethernet1"), param.value)
  }

  @Test
  fun `sdn_string optional match field is translated on write`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val sdnBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val update =
      writeUpdateOptionalMatch(TABLE_ID, MATCH_FIELD_ID, sdnBytes, ACTION_ID, PARAM_ID, sdnBytes)
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.optional.value)
  }

  // ===========================================================================
  // Mixed sdn_bitwidth and sdn_string in the same p4info
  // ===========================================================================

  @Test
  fun `mixed sdn_bitwidth and sdn_string types are partitioned correctly`() {
    val translator = buildP4InfoTranslatorWithMixedTypes()

    // Field 1 is sdn_string (port_name_t), field 3 is sdn_bitwidth (port_id_t).
    val stringBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val bitstringBytes = sdnBytes(5000)

    // Write with both match fields.
    val entry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(TABLE_ID)
        .addMatch(
          P4RuntimeOuterClass.FieldMatch.newBuilder()
            .setFieldId(MATCH_FIELD_ID)
            .setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(ByteString.copyFrom(stringBytes))
            )
        )
        .addMatch(
          P4RuntimeOuterClass.FieldMatch.newBuilder()
            .setFieldId(BITWIDTH_FIELD_ID)
            .setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(ByteString.copyFrom(bitstringBytes))
            )
        )
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(ACTION_ID)
                .addParams(
                  P4RuntimeOuterClass.Action.Param.newBuilder()
                    .setParamId(PARAM_ID)
                    .setValue(ByteString.copyFrom(stringBytes))
                )
            )
        )
        .build()
    val update =
      P4RuntimeOuterClass.Update.newBuilder()
        .setType(P4RuntimeOuterClass.Update.Type.INSERT)
        .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
        .build()

    val translated = translator.translateForWrite(update)
    val matches = translated.entity.tableEntry.matchList

    // String field → auto-allocated dp=0.
    assertEquals(ByteString.copyFrom(dpBytes(0)), matches[0].exact.value)
    // Bitwidth field → also auto-allocated dp=0 (independent table).
    assertEquals(ByteString.copyFrom(dpBytes(0)), matches[1].exact.value)

    // Read back: string field returns UTF-8, bitwidth field returns raw bytes.
    val dpEntity =
      P4RuntimeOuterClass.Entity.newBuilder()
        .setTableEntry(
          P4RuntimeOuterClass.TableEntry.newBuilder()
            .setTableId(TABLE_ID)
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(MATCH_FIELD_ID)
                .setExact(
                  P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(dpBytes(0)))
                )
            )
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(BITWIDTH_FIELD_ID)
                .setExact(
                  P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                    .setValue(ByteString.copyFrom(dpBytes(0)))
                )
            )
            .setAction(
              P4RuntimeOuterClass.TableAction.newBuilder()
                .setAction(
                  P4RuntimeOuterClass.Action.newBuilder()
                    .setActionId(ACTION_ID)
                    .addParams(
                      P4RuntimeOuterClass.Action.Param.newBuilder()
                        .setParamId(PARAM_ID)
                        .setValue(ByteString.copyFrom(dpBytes(0)))
                    )
                )
            )
        )
        .build()
    val sdnEntity = translator.translateForRead(dpEntity)
    val sdnMatches = sdnEntity.tableEntry.matchList
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), sdnMatches[0].exact.value)
    assertEquals(ByteString.copyFrom(bitstringBytes), sdnMatches[1].exact.value)
  }

  // ===========================================================================
  // PacketIO metadata translation via P4Info
  // ===========================================================================

  @Test
  fun `packet metadata with translated type is translated for packet out`() {
    val translator = buildP4InfoTranslatorWithPacketIO()

    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(sdnBytes(5000)))
        )
        .build()

    val translated = translator.translatePacketOut(packetOut)
    val meta = translated.metadataList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), meta.value)
  }

  @Test
  fun `packet metadata with translated type is translated for packet in`() {
    val translator = buildP4InfoTranslatorWithPacketIO()

    // Install forward mapping first.
    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(sdnBytes(5000)))
        )
        .build()
    translator.translatePacketOut(packetOut)

    // Reverse: data-plane → SDN.
    val packetIn =
      P4RuntimeOuterClass.PacketIn.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(dpBytes(0)))
        )
        .build()

    val translated = translator.translatePacketIn(packetIn)
    val meta = translated.metadataList.first()
    assertEquals(ByteString.copyFrom(sdnBytes(5000)), meta.value)
  }

  @Test
  fun `sdn_string packet metadata round-trips through packet out and in`() {
    val translator = buildP4InfoTranslatorWithStringPacketIO()

    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFromUtf8("Ethernet0"))
        )
        .build()

    val translatedOut = translator.translatePacketOut(packetOut)
    assertEquals(ByteString.copyFrom(dpBytes(0)), translatedOut.metadataList.first().value)

    // Reverse: data-plane → SDN.
    val packetIn =
      P4RuntimeOuterClass.PacketIn.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(dpBytes(0)))
        )
        .build()

    val translatedIn = translator.translatePacketIn(packetIn)
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), translatedIn.metadataList.first().value)
  }

  @Test
  fun `non-translated packet metadata passes through unchanged`() {
    val translator = buildP4InfoTranslatorWithPacketIO()

    val rawValue = ByteString.copyFrom(sdnBytes(42))
    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(NON_TRANSLATED_METADATA_ID)
            .setValue(rawValue)
        )
        .build()

    val translated = translator.translatePacketOut(packetOut)
    val meta = translated.metadataList.first()
    assertEquals(rawValue, meta.value)
  }

  // ===========================================================================
  // P4Info test constants and builders
  // ===========================================================================

  companion object {
    private const val TABLE_ID = 100
    private const val MATCH_FIELD_ID = 1
    private const val NON_TRANSLATED_FIELD_ID = 2
    private const val ACTION_ID = 200
    private const val PARAM_ID = 1
    private const val PACKET_METADATA_ID = 1
    private const val NON_TRANSLATED_METADATA_ID = 2
    private const val ACTION_PROFILE_ID = 300
    private const val TYPE_NAME = "port_id_t"
    private const val TYPE_URI = "test.port_id"
    private const val BITWIDTH_FIELD_ID = 3
    private const val STRING_TYPE_NAME = "port_name_t"
    private const val STRING_TYPE_URI = "sai.port"
  }

  /** Builds a single-entry P4TypeInfo for a translated type. */
  private fun typeInfo(
    typeName: String,
    uri: String,
    sdnType: P4Types.P4NewTypeTranslation.Builder.() -> Unit,
  ): P4Types.P4TypeInfo =
    P4Types.P4TypeInfo.newBuilder()
      .putNewTypes(
        typeName,
        P4Types.P4NewTypeSpec.newBuilder()
          .setTranslatedType(P4Types.P4NewTypeTranslation.newBuilder().setUri(uri).apply(sdnType))
          .build(),
      )
      .build()

  /** Builds a TypeTranslator with sdn_string translated match field and action param. */
  private fun buildP4InfoTranslatorWithStringType(): TypeTranslator {
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID))
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(MATCH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
        )
        .setTypeInfo(stringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds a TypeTranslator with sdn_string translated PacketIO metadata. */
  private fun buildP4InfoTranslatorWithStringPacketIO(): TypeTranslator {
    val translatedMetadata =
      P4InfoOuterClass.ControllerPacketMetadata.Metadata.newBuilder()
        .setId(PACKET_METADATA_ID)
        .setName("ingress_port")
        .setBitwidth(32)
        .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(1).setName("packet_out"))
            .addMetadata(translatedMetadata)
        )
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(2).setName("packet_in"))
            .addMetadata(translatedMetadata)
        )
        .setTypeInfo(stringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds a TypeTranslator with both sdn_string and sdn_bitwidth match fields. */
  private fun buildP4InfoTranslatorWithMixedTypes(): TypeTranslator {
    val typeInfo =
      bitstringTypeInfo().toBuilder().putAllNewTypes(stringTypeInfo().newTypesMap).build()
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID))
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(MATCH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(BITWIDTH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
        )
        .setTypeInfo(typeInfo)
        .build()
    return TypeTranslator.create(p4info)
  }

  private fun bitstringTypeInfo() = typeInfo(TYPE_NAME, TYPE_URI) { setSdnBitwidth(32) }

  private fun stringTypeInfo() =
    typeInfo(STRING_TYPE_NAME, STRING_TYPE_URI) {
      setSdnString(P4Types.P4NewTypeTranslation.SdnString.getDefaultInstance())
    }

  /** Builds a TypeTranslator from synthetic p4info with a translated match field. */
  private fun buildP4InfoTranslator(): TypeTranslator {
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID))
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(MATCH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
            )
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(NON_TRANSLATED_FIELD_ID)
                .setBitwidth(16)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
            )
        )
        .setTypeInfo(bitstringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds a TypeTranslator from synthetic p4info with translated PacketIO metadata. */
  private fun buildP4InfoTranslatorWithPacketIO(): TypeTranslator {
    val translatedMetadata =
      P4InfoOuterClass.ControllerPacketMetadata.Metadata.newBuilder()
        .setId(PACKET_METADATA_ID)
        .setName("ingress_port")
        .setBitwidth(32)
        .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(1).setName("packet_out"))
            .addMetadata(translatedMetadata)
            .addMetadata(
              P4InfoOuterClass.ControllerPacketMetadata.Metadata.newBuilder()
                .setId(NON_TRANSLATED_METADATA_ID)
                .setName("padding")
                .setBitwidth(7)
            )
        )
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(2).setName("packet_in"))
            .addMetadata(translatedMetadata)
        )
        .setTypeInfo(bitstringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds an Update wrapping a table entry with an exact match. */
  private fun writeUpdate(
    tableId: Int,
    fieldId: Int,
    matchValue: ByteArray,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Update {
    val match = exactMatch(fieldId, matchValue)
    return wrapUpdate(buildTableEntry(tableId, match, actionId, paramId, paramValue))
  }

  /** Builds an Update wrapping a table entry with an optional match. */
  private fun writeUpdateOptionalMatch(
    tableId: Int,
    fieldId: Int,
    matchValue: ByteArray,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Update {
    val match =
      P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(fieldId)
        .setOptional(
          P4RuntimeOuterClass.FieldMatch.Optional.newBuilder()
            .setValue(ByteString.copyFrom(matchValue))
        )
        .build()
    return wrapUpdate(buildTableEntry(tableId, match, actionId, paramId, paramValue))
  }

  /** Builds an Entity with exact match and action param (simulates a read response). */
  private fun readEntity(
    tableId: Int,
    fieldId: Int,
    matchValue: ByteArray,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Entity {
    val match = exactMatch(fieldId, matchValue)
    return P4RuntimeOuterClass.Entity.newBuilder()
      .setTableEntry(buildTableEntry(tableId, match, actionId, paramId, paramValue))
      .build()
  }

  private fun exactMatch(fieldId: Int, value: ByteArray): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(
        P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value))
      )
      .build()

  private fun buildTableEntry(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.TableEntry =
    P4RuntimeOuterClass.TableEntry.newBuilder()
      .setTableId(tableId)
      .addMatch(match)
      .setAction(
        P4RuntimeOuterClass.TableAction.newBuilder()
          .setAction(
            P4RuntimeOuterClass.Action.newBuilder()
              .setActionId(actionId)
              .addParams(
                P4RuntimeOuterClass.Action.Param.newBuilder()
                  .setParamId(paramId)
                  .setValue(ByteString.copyFrom(paramValue))
              )
          )
      )
      .build()

  private fun wrapUpdate(entry: P4RuntimeOuterClass.TableEntry): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
      .build()

  /** Builds an Update wrapping an ActionProfileMember with a single param. */
  private fun memberUpdate(
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Update {
    val member =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(ACTION_PROFILE_ID)
        .setMemberId(1)
        .setAction(
          P4RuntimeOuterClass.Action.newBuilder()
            .setActionId(actionId)
            .addParams(
              P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(paramId)
                .setValue(ByteString.copyFrom(paramValue))
            )
        )
        .build()
    return P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setActionProfileMember(member))
      .build()
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** Builds a TypeTranslator from the given TypeTranslation configs. */
  private fun buildTranslator(vararg translations: TypeTranslation): TypeTranslator =
    TypeTranslator.create(translations.toList())

  /** Shorthand for building a TypeTranslation proto. */
  private fun translation(block: TypeTranslation.Builder.() -> Unit): TypeTranslation =
    TypeTranslation.newBuilder().apply(block).build()

  /** Builds a numeric-to-numeric TranslationEntry. */
  private fun entry(sdnValue: ByteArray, dataplaneValue: ByteArray): TranslationEntry =
    TranslationEntry.newBuilder()
      .setSdnBitstring(ByteString.copyFrom(sdnValue))
      .setDataplaneValue(ByteString.copyFrom(dataplaneValue))
      .build()

  /** Builds a string-to-numeric TranslationEntry. */
  private fun stringEntry(sdnStr: String, dataplaneValue: ByteArray): TranslationEntry =
    TranslationEntry.newBuilder()
      .setSdnStr(sdnStr)
      .setDataplaneValue(ByteString.copyFrom(dataplaneValue))
      .build()

  private fun sdnBytes(value: Int): ByteArray = encodeMinWidth(value)

  private fun dpBytes(value: Int): ByteArray = encodeMinWidth(value)

  private fun encodeMinWidth(value: Int): ByteArray {
    if (value == 0) return byteArrayOf(0)
    val bytes = mutableListOf<Byte>()
    var v = value
    while (v > 0) {
      bytes.add(0, (v and 0xFF).toByte())
      v = v shr 8
    }
    return bytes.toByteArray()
  }

  /** Wraps a ByteArray as ByteString. */
  private fun bs(bytes: ByteArray): ByteString = ByteString.copyFrom(bytes)
}
