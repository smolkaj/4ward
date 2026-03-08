package fourward.web

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
}
