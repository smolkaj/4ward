package fourward.e2e

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
  fun `table entries list is always empty until add is implemented`() {
    val stf = parse("packet 0 FF\nexpect 1 FF")
    assertTrue(stf.tableEntries.isEmpty())
  }
}
