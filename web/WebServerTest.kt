package fourward.web

import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.BitType
import fourward.ir.v1.FieldDecl
import fourward.ir.v1.HeaderDecl
import fourward.ir.v1.IntType
import fourward.ir.v1.Type
import fourward.ir.v1.TypeDecl
import fourward.ir.v1.VarbitType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebServerTest {

  @Test
  fun `extractJsonString extracts simple values`() {
    assertEquals("hello", WebServer.extractJsonString("""{"key":"hello"}""", "key"))
    assertEquals("world", WebServer.extractJsonString("""{"a":"b","key":"world"}""", "key"))
  }

  @Test
  fun `extractJsonString handles escaped characters`() {
    assertEquals("line1\nline2", WebServer.extractJsonString("""{"s":"line1\nline2"}""", "s"))
    assertEquals("a\"b", WebServer.extractJsonString("""{"s":"a\"b"}""", "s"))
  }

  @Test
  fun `extractJsonString handles unicode escapes`() {
    assertEquals("caf\u00e9", WebServer.extractJsonString("""{"s":"caf\u00e9"}""", "s"))
    assertEquals("\u4e16\u754c", WebServer.extractJsonString("""{"s":"\u4e16\u754c"}""", "s"))
  }

  @Test
  fun `extractJsonString returns null for missing key`() {
    assertNull(WebServer.extractJsonString("""{"other":"value"}""", "key"))
  }

  @Test
  fun `extractJsonInt extracts integer values`() {
    assertEquals(42, WebServer.extractJsonInt("""{"port":42}""", "port"))
    assertEquals(0, WebServer.extractJsonInt("""{"port":0}""", "port"))
  }

  @Test
  fun `extractJsonInt returns null for missing key`() {
    assertNull(WebServer.extractJsonInt("""{"other":1}""", "port"))
  }

  @Test
  fun `hexToBytes converts hex string to bytes`() {
    assertArrayEquals(
      byteArrayOf(0xFF.toByte(), 0x00, 0xAB.toByte()),
      WebServer.hexToBytes("ff00ab"),
    )
  }

  @Test
  fun `hexToBytes handles spaces and colons`() {
    assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), WebServer.hexToBytes("de ad"))
    assertArrayEquals(byteArrayOf(0xBE.toByte(), 0xEF.toByte()), WebServer.hexToBytes("be:ef"))
  }

  @Test
  fun `hexToBytes handles 0x prefix`() {
    assertArrayEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte()), WebServer.hexToBytes("0xCAFE"))
  }

  @Test
  fun `jsonEscape escapes special characters`() {
    assertEquals("\"hello\"", WebServer.jsonEscape("hello"))
    assertEquals("\"a\\\"b\"", WebServer.jsonEscape("a\"b"))
    assertEquals("\"line1\\nline2\"", WebServer.jsonEscape("line1\nline2"))
  }

  @Test
  fun `errorJson produces valid JSON`() {
    assertEquals("""{"error":"oops"}""", WebServer.errorJson("oops"))
    assertEquals("""{"error":"a\"b"}""", WebServer.errorJson("a\"b"))
  }

  // ---------------------------------------------------------------------------
  // headerTypesJson
  // ---------------------------------------------------------------------------

  @Test
  fun `headerTypesJson serializes header fields with bitwidths`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("ethernet_t")
            .setHeader(
              HeaderDecl.newBuilder()
                .addFields(field("dstAddr", bitType(48)))
                .addFields(field("srcAddr", bitType(48)))
                .addFields(field("etherType", bitType(16)))
            )
        )
        .build()
    assertEquals(
      """{"ethernet_t":[{"name":"dstAddr","bitwidth":48},{"name":"srcAddr","bitwidth":48},{"name":"etherType","bitwidth":16}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson handles multiple header types`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("h1_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("f", bitType(8))))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("h2_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("g", bitType(32))))
        )
        .build()
    assertEquals(
      """{"h1_t":[{"name":"f","bitwidth":8}],"h2_t":[{"name":"g","bitwidth":32}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson skips non-header types`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("ethernet_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("f", bitType(8))))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("meta_t")
            .setStruct(fourward.ir.v1.StructDecl.newBuilder().addFields(field("x", bitType(16))))
        )
        .build()
    assertEquals(
      """{"ethernet_t":[{"name":"f","bitwidth":8}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson handles signed int and varbit types`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("special_t")
            .setHeader(
              HeaderDecl.newBuilder()
                .addFields(field("signed_f", signedIntType(16)))
                .addFields(field("var_f", varbitType(320)))
            )
        )
        .build()
    assertEquals(
      """{"special_t":[{"name":"signed_f","bitwidth":16},{"name":"var_f","bitwidth":320}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson returns empty object for no headers`() {
    val config = BehavioralConfig.getDefaultInstance()
    assertEquals("{}", WebServer.headerTypesJson(config))
  }

  private fun field(name: String, type: Type): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(type).build()

  private fun bitType(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

  private fun signedIntType(width: Int): Type =
    Type.newBuilder().setSignedInt(IntType.newBuilder().setWidth(width)).build()

  private fun varbitType(maxWidth: Int): Type =
    Type.newBuilder().setVarbit(VarbitType.newBuilder().setMaxWidth(maxWidth)).build()
}
