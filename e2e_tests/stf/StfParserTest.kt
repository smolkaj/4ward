package fourward.e2e

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [StfFile.parse]. */
class StfParserTest {

  /** Writes [content] to a temp file, parses it, and deletes the temp file. */
  private fun parse(content: String): StfFile {
    val tmp = Files.createTempFile("stf-test", ".stf")
    try {
      tmp.toFile().writeText(content)
      return StfFile.parse(tmp)
    } finally {
      Files.delete(tmp)
    }
  }

  @Test
  fun `empty file produces no packets`() {
    val stf = parse("")
    assertTrue(stf.packets.isEmpty())
  }

  @Test
  fun `comment-only file produces no packets`() {
    val stf =
      parse(
        """
        # This is a comment
        # Another comment
        """
          .trimIndent()
      )
    assertTrue(stf.packets.isEmpty())
  }

  @Test
  fun `blank lines are ignored`() {
    val stf = parse("\n\n\npacket 0 AA\n\n")
    assertEquals(1, stf.packets.size)
  }

  @Test
  fun `single packet with no expected output`() {
    val stf = parse("packet 0 AABBCC")
    assertEquals(1, stf.packets.size)
    val pkt = stf.packets[0]
    assertEquals(0, pkt.ingressPort)
    assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), pkt.payload)
    assertTrue(pkt.expectedOutputs.isEmpty())
  }

  @Test
  fun `packet with an expect line`() {
    val stf =
      parse(
        """
        packet 1 0102
        expect 2 0304
        """
          .trimIndent()
      )
    assertEquals(1, stf.packets.size)
    val pkt = stf.packets[0]
    assertEquals(1, pkt.ingressPort)
    assertArrayEquals(byteArrayOf(0x01, 0x02), pkt.payload)
    assertEquals(1, pkt.expectedOutputs.size)
    val exp = pkt.expectedOutputs[0]
    assertEquals(2, exp.port)
    assertArrayEquals(byteArrayOf(0x03, 0x04), exp.payload)
    // Normal bytes have a full mask.
    assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), exp.mask)
  }

  @Test
  fun `expect with wildcard bytes`() {
    val stf =
      parse(
        """
        packet 0 AABB
        expect 1 ** BB
        """
          .trimIndent()
      )
    val exp = stf.packets[0].expectedOutputs[0]
    // First byte is wildcard (mask=0x00), second is concrete (mask=0xFF).
    assertArrayEquals(byteArrayOf(0x00, 0xBB.toByte()), exp.payload)
    assertArrayEquals(byteArrayOf(0x00, 0xFF.toByte()), exp.mask)
  }

  @Test
  fun `expect with four-char wildcard token`() {
    // "****" is two consecutive wildcard bytes.
    val stf = parse("packet 0 AABB\nexpect 1 ****")
    val exp = stf.packets[0].expectedOutputs[0]
    assertArrayEquals(byteArrayOf(0x00, 0x00), exp.payload)
    assertArrayEquals(byteArrayOf(0x00, 0x00), exp.mask)
  }

  @Test
  fun `expect with end-of-packet marker`() {
    // "$" is stripped; the payload comparison is exact-length regardless.
    val stf = parse("packet 0 AABB\nexpect 1 AABB $")
    val exp = stf.packets[0].expectedOutputs[0]
    assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), exp.payload)
    assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), exp.mask)
  }

  @Test
  fun `multiple expect lines attach to the preceding packet`() {
    val stf =
      parse(
        """
        packet 0 FF
        expect 1 FF
        expect 2 FF
        """
          .trimIndent()
      )
    assertEquals(1, stf.packets.size)
    assertEquals(2, stf.packets[0].expectedOutputs.size)
  }

  @Test
  fun `multiple packets each accumulate their own expects`() {
    val stf =
      parse(
        """
        packet 0 AA
        expect 1 AA
        packet 0 BB
        expect 1 BB
        """
          .trimIndent()
      )
    assertEquals(2, stf.packets.size)
    assertArrayEquals(byteArrayOf(0xAA.toByte()), stf.packets[0].payload)
    assertArrayEquals(byteArrayOf(0xBB.toByte()), stf.packets[1].payload)
    assertEquals(1, stf.packets[0].expectedOutputs.size)
    assertEquals(1, stf.packets[1].expectedOutputs.size)
  }

  @Test
  fun `lowercase hex payload is decoded correctly`() {
    val stf = parse("packet 0 deadbeef")
    assertArrayEquals(
      byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
      stf.packets[0].payload,
    )
  }

  @Test
  fun `mixed case hex payload is decoded correctly`() {
    val stf = parse("packet 0 DeAdBeEf")
    assertArrayEquals(
      byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
      stf.packets[0].payload,
    )
  }

  @Test
  fun `packet with empty payload has zero-length byte array`() {
    // Port only, no hex bytes following
    val stf = parse("packet 3")
    assertEquals(1, stf.packets.size)
    assertEquals(3, stf.packets[0].ingressPort)
    assertEquals(0, stf.packets[0].payload.size)
  }

  @Test
  fun `no add lines produces empty table entries list`() {
    val stf = parse("packet 0 FF\nexpect 1 FF")
    assertTrue(stf.tableEntries.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // add directive parsing
  // ---------------------------------------------------------------------------

  @Test
  fun `add exact match entry`() {
    val stf = parse("add port_table hdr.ethernet.etherType:0x0800 forward(1)")
    assertEquals(1, stf.tableEntries.size)
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals("port_table", entry.tableName)
    assertNull(entry.priority)
    assertEquals(1, entry.matches.size)
    val m = entry.matches[0]
    assertEquals("hdr.ethernet.etherType", m.fieldName)
    assertEquals(MatchKind.EXACT, m.kind)
    assertEquals("0x0800", m.value)
    assertEquals("forward", entry.actionName)
    assertEquals(listOf("1"), entry.actionParams)
  }

  @Test
  fun `add entry with no action params`() {
    val stf = parse("add acl hdr.ipv4.protocol:0x11 drop()")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals("drop", entry.actionName)
    assertTrue(entry.actionParams.isEmpty())
  }

  @Test
  fun `add LPM entry`() {
    val stf = parse("add ipv4_lpm hdr.ipv4.dstAddr:10.0.0.0/24 forward(1)")
    val m = (stf.tableEntries[0] as StfAddEntry).matches[0]
    assertEquals(MatchKind.LPM, m.kind)
    assertEquals("10.0.0.0", m.value)
    assertEquals(24, m.prefixLen)
  }

  @Test
  fun `add ternary entry with priority`() {
    val stf = parse("add acl 10 hdr.ipv4.protocol:0x06&&&0xff drop()")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals(10, entry.priority)
    val m = entry.matches[0]
    assertEquals(MatchKind.TERNARY, m.kind)
    assertEquals("0x06", m.value)
    assertEquals("0xff", m.mask)
  }

  @Test
  fun `add with multiple match fields`() {
    val stf = parse("add acl hdr.ipv4.protocol:0x06 hdr.tcp.dstPort:0x0050 drop()")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals(2, entry.matches.size)
    assertEquals("hdr.ipv4.protocol", entry.matches[0].fieldName)
    assertEquals("hdr.tcp.dstPort", entry.matches[1].fieldName)
  }

  @Test
  fun `add with multiple action params`() {
    val stf = parse("add t key:0x01 action_two_params(10, 20)")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals("action_two_params", entry.actionName)
    assertEquals(listOf("10", "20"), entry.actionParams)
  }

  @Test
  fun `add lines before packets are parsed correctly`() {
    val stf =
      parse(
        """
        add t k:0x01 a()
        packet 0 FF
        expect 1 FF
        """
          .trimIndent()
      )
    assertEquals(1, stf.tableEntries.size)
    assertEquals(1, stf.packets.size)
  }

  @Test
  fun `decimal match value`() {
    val stf = parse("add t port:80 a()")
    assertEquals("80", (stf.tableEntries[0] as StfAddEntry).matches[0].value)
  }

  // ---------------------------------------------------------------------------
  // p4testgen dialect: quoted identifiers
  // ---------------------------------------------------------------------------

  @Test
  fun `add with quoted table and action names`() {
    val stf = parse("""add "port_table" hdr.etherType:0x0800 "forward"(1)""")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals("port_table", entry.tableName)
    assertEquals("forward", entry.actionName)
    assertEquals(listOf("1"), entry.actionParams)
  }

  @Test
  fun `add with quoted field names`() {
    val stf = parse("""add "t" "hdr.ipv4.protocol":0x06 "drop"()""")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals("hdr.ipv4.protocol", entry.matches[0].fieldName)
  }

  // ---------------------------------------------------------------------------
  // p4testgen dialect: named action params
  // ---------------------------------------------------------------------------

  @Test
  fun `add with named action params`() {
    val stf = parse("""add "t" "key":0x01 "action"("port":1,"mask":0xff)""")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals("action", entry.actionName)
    assertEquals(listOf("1", "0xff"), entry.actionParams)
  }

  @Test
  fun `add with single named param`() {
    val stf = parse("""add "t" "key":0x01 "fwd"("port":0x02)""")
    assertEquals(listOf("0x02"), stf.tableEntries[0].actionParams)
  }

  // ---------------------------------------------------------------------------
  // p4testgen dialect: binary wildcard matches
  // ---------------------------------------------------------------------------

  @Test
  fun `add with binary wildcard ternary match`() {
    // 0b1010**** → value = 0xa0, mask = 0xf0
    val stf = parse("""add "t" 10 "field":0b1010**** "drop"()""")
    val entry = stf.tableEntries[0] as StfAddEntry
    assertEquals(10, entry.priority)
    val m = entry.matches[0]
    assertEquals(MatchKind.TERNARY, m.kind)
    assertEquals("0xa0", m.value)
    assertEquals("0xf0", m.mask)
  }

  @Test
  fun `add with all-wildcard binary match`() {
    // 0b******** → value = 0x0, mask = 0x0
    val stf = parse("""add "t" 1 "f":0b******** "a"()""")
    val m = (stf.tableEntries[0] as StfAddEntry).matches[0]
    assertEquals(MatchKind.TERNARY, m.kind)
    assertEquals("0x0", m.value)
    assertEquals("0x0", m.mask)
  }

  @Test
  fun `add with all-concrete binary value is exact`() {
    // 0b11001010 with no wildcards is treated as an exact match value.
    val stf = parse("""add "t" 1 "f":0b11001010 "a"()""")
    val m = (stf.tableEntries[0] as StfAddEntry).matches[0]
    assertEquals(MatchKind.EXACT, m.kind)
    assertEquals("0b11001010", m.value)
  }

  // ---------------------------------------------------------------------------
  // p4testgen dialect: setdefault directive
  // ---------------------------------------------------------------------------

  @Test
  fun `setdefault with no params`() {
    val stf = parse("""setdefault "my_table" "drop"()""")
    assertEquals(1, stf.tableEntries.size)
    val entry = stf.tableEntries[0] as StfSetDefault
    assertEquals("my_table", entry.tableName)
    assertEquals("drop", entry.actionName)
    assertTrue(entry.actionParams.isEmpty())
  }

  @Test
  fun `setdefault with named params`() {
    val stf = parse("""setdefault "tbl" "act"("p1":0x01,"p2":0x02)""")
    val entry = stf.tableEntries[0] as StfSetDefault
    assertEquals("tbl", entry.tableName)
    assertEquals("act", entry.actionName)
    assertEquals(listOf("0x01", "0x02"), entry.actionParams)
  }

  @Test
  fun `setdefault interleaved with packets`() {
    val stf =
      parse(
        """
        setdefault "t1" "drop"()
        packet 0 AABB
        expect 1 AABB
        """
          .trimIndent()
      )
    assertEquals(1, stf.tableEntries.size)
    assertEquals(1, stf.packets.size)
    assertTrue(stf.tableEntries[0] is StfSetDefault)
  }

  @Test
  fun `add produces StfAddEntry`() {
    val stf = parse("add t key:0x01 a()")
    assertTrue(stf.tableEntries[0] is StfAddEntry)
  }

  // ---------------------------------------------------------------------------
  // p4testgen dialect: nibble-level wildcards in expect
  // ---------------------------------------------------------------------------

  @Test
  fun `expect with nibble-level wildcards`() {
    // "A*" → hi nibble = 0xA (mask 0xF), lo nibble = wildcard (mask 0x0)
    val stf = parse("packet 0 AA\nexpect 1 A*BB")
    val exp = stf.packets[0].expectedOutputs[0]
    assertArrayEquals(byteArrayOf(0xA0.toByte(), 0xBB.toByte()), exp.payload)
    assertArrayEquals(byteArrayOf(0xF0.toByte(), 0xFF.toByte()), exp.mask)
  }

  @Test
  fun `expect with single nibble wildcard in low position`() {
    // "*F" → hi nibble = wildcard, lo nibble = 0xF
    val stf = parse("packet 0 AA\nexpect 1 *F")
    val exp = stf.packets[0].expectedOutputs[0]
    assertArrayEquals(byteArrayOf(0x0F.toByte()), exp.payload)
    assertArrayEquals(byteArrayOf(0x0F.toByte()), exp.mask)
  }

  // ---------------------------------------------------------------------------
  // encodeValue
  // ---------------------------------------------------------------------------

  @Test
  fun `encodeValue hex prefix`() {
    assertArrayEquals(byteArrayOf(0x08, 0x00), encodeValue("0x0800", 16).toByteArray())
  }

  @Test
  fun `encodeValue uppercase hex prefix`() {
    assertArrayEquals(byteArrayOf(0x08, 0x00), encodeValue("0X0800", 16).toByteArray())
  }

  @Test
  fun `encodeValue dotted-decimal IPv4`() {
    assertArrayEquals(
      byteArrayOf(0x0a, 0x00, 0x00, 0x01),
      encodeValue("10.0.0.1", 32).toByteArray(),
    )
  }

  @Test
  fun `encodeValue plain decimal`() {
    assertArrayEquals(byteArrayOf(0x00, 0x50), encodeValue("80", 16).toByteArray())
  }

  @Test
  fun `encodeValue strips BigInteger sign byte for high-bit values`() {
    // BigInteger("ff", 16) = 255, which toByteArray() encodes as [0x00, 0xff].
    // encodeValue must strip the sign byte and return just [0xff].
    assertArrayEquals(byteArrayOf(0xff.toByte()), encodeValue("0xff", 8).toByteArray())
  }

  @Test
  fun `encodeValue zero-pads to full byte length`() {
    assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01), encodeValue("1", 32).toByteArray())
  }

  // ---------------------------------------------------------------------------
  // matchesMasked (via expect parsing + manual comparison)
  // ---------------------------------------------------------------------------

  private fun ByteArray.matchesMasked(expected: ByteArray, mask: ByteArray): Boolean {
    if (size != expected.size) return false
    return indices.all { i ->
      (this[i].toInt() and mask[i].toInt()) == (expected[i].toInt() and mask[i].toInt())
    }
  }

  @Test
  fun `matchesMasked exact match`() {
    val actual = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    assertTrue(actual.matchesMasked(expected, mask))
  }

  @Test
  fun `matchesMasked byte mismatch`() {
    val actual = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val expected = byteArrayOf(0xAA.toByte(), 0xCC.toByte())
    val mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    assertTrue(!actual.matchesMasked(expected, mask))
  }

  @Test
  fun `matchesMasked all wildcards always matches`() {
    val actual = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
    val expected = byteArrayOf(0x00, 0x00)
    val mask = byteArrayOf(0x00, 0x00)
    assertTrue(actual.matchesMasked(expected, mask))
  }

  @Test
  fun `matchesMasked wildcard ignores differing byte`() {
    val actual = byteArrayOf(0xFF.toByte(), 0xBB.toByte())
    val expected = byteArrayOf(0x00, 0xBB.toByte())
    val mask = byteArrayOf(0x00, 0xFF.toByte()) // first byte is wildcard
    assertTrue(actual.matchesMasked(expected, mask))
  }

  @Test
  fun `matchesMasked length mismatch is never equal`() {
    val actual = byteArrayOf(0xAA.toByte())
    val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    assertTrue(!actual.matchesMasked(expected, mask))
  }
}
