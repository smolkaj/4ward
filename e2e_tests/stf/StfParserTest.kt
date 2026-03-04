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
    val entry = stf.tableEntries[0]
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
    val entry = stf.tableEntries[0]
    assertEquals("drop", entry.actionName)
    assertTrue(entry.actionParams.isEmpty())
  }

  @Test
  fun `add LPM entry`() {
    val stf = parse("add ipv4_lpm hdr.ipv4.dstAddr:10.0.0.0/24 forward(1)")
    val m = stf.tableEntries[0].matches[0]
    assertEquals(MatchKind.LPM, m.kind)
    assertEquals("10.0.0.0", m.value)
    assertEquals(24, m.prefixLen)
  }

  @Test
  fun `add ternary entry with priority`() {
    val stf = parse("add acl 10 hdr.ipv4.protocol:0x06&&&0xff drop()")
    val entry = stf.tableEntries[0]
    assertEquals(10, entry.priority)
    val m = entry.matches[0]
    assertEquals(MatchKind.TERNARY, m.kind)
    assertEquals("0x06", m.value)
    assertEquals("0xff", m.mask)
  }

  @Test
  fun `add with multiple match fields`() {
    val stf = parse("add acl hdr.ipv4.protocol:0x06 hdr.tcp.dstPort:0x0050 drop()")
    val entry = stf.tableEntries[0]
    assertEquals(2, entry.matches.size)
    assertEquals("hdr.ipv4.protocol", entry.matches[0].fieldName)
    assertEquals("hdr.tcp.dstPort", entry.matches[1].fieldName)
  }

  @Test
  fun `add with multiple action params`() {
    val stf = parse("add t key:0x01 action_two_params(10, 20)")
    val entry = stf.tableEntries[0]
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
    assertEquals("80", stf.tableEntries[0].matches[0].value)
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
}
