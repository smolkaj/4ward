package fourward.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [HeaderLayout] and [HeaderView]. */
class HeaderViewTest {

  // An ethernet header layout: dstAddr(48) | srcAddr(48) | etherType(16) | valid(1).
  // Total 113 bits; field offsets are bit-aligned from 0.
  private val ethernet =
    HeaderLayout(
      typeName = "ethernet_t",
      fields =
        linkedMapOf(
          "dstAddr" to FieldSlot(offset = 0, width = 48),
          "srcAddr" to FieldSlot(offset = 48, width = 48),
          "etherType" to FieldSlot(offset = 96, width = 16),
        ),
      validBitOffset = 112,
    )

  private fun newBuffer() = PacketBuffer(ethernet.totalBits)

  private fun newHeader() = HeaderView(newBuffer(), ethernet, base = 0)

  // =====================================================================
  // Layout sanity
  // =====================================================================

  @Test
  fun `totalBits includes all fields plus validity bit`() {
    assertEquals(48 + 48 + 16 + 1, ethernet.totalBits)
  }

  // =====================================================================
  // Field read/write
  // =====================================================================

  @Test
  fun `set and get individual fields`() {
    val h = newHeader()
    h["dstAddr"] = BitVal(0xAABB_CCDD_EEFFL, 48)
    h["srcAddr"] = BitVal(0x1122_3344_5566L, 48)
    h["etherType"] = BitVal(0x0800L, 16)

    assertEquals(BitVal(0xAABB_CCDD_EEFFL, 48), h["dstAddr"])
    assertEquals(BitVal(0x1122_3344_5566L, 48), h["srcAddr"])
    assertEquals(BitVal(0x0800L, 16), h["etherType"])
  }

  @Test
  fun `reading an unknown field name throws`() {
    val h = newHeader()
    assertThrows(IllegalArgumentException::class.java) { h["missing"] }
  }

  @Test
  fun `writing an unknown field name throws`() {
    val h = newHeader()
    assertThrows(IllegalArgumentException::class.java) { h["missing"] = BitVal(0L, 8) }
  }

  @Test
  fun `writing a value of the wrong width throws`() {
    val h = newHeader()
    assertThrows(IllegalArgumentException::class.java) {
      h["etherType"] = BitVal(0L, 8) // etherType is 16 bits
    }
  }

  // =====================================================================
  // Validity
  // =====================================================================

  @Test
  fun `new headers are invalid by default`() {
    val h = newHeader()
    assertFalse(h.isValid)
  }

  @Test
  fun `setting isValid toggles the validity bit`() {
    val h = newHeader()
    h.isValid = true
    assertTrue(h.isValid)
    h.isValid = false
    assertFalse(h.isValid)
  }

  @Test
  fun `setInvalid zeros all fields (P4 spec section 8_17)`() {
    val h = newHeader()
    h["dstAddr"] = BitVal(0xFFFF_FFFF_FFFFL, 48)
    h["srcAddr"] = BitVal(0xFFFF_FFFF_FFFFL, 48)
    h["etherType"] = BitVal(0xFFFFL, 16)
    h.isValid = true

    h.isValid = false

    assertFalse(h.isValid)
    assertEquals(BitVal(0L, 48), h["dstAddr"])
    assertEquals(BitVal(0L, 48), h["srcAddr"])
    assertEquals(BitVal(0L, 16), h["etherType"])
  }

  // =====================================================================
  // Base offset (multiple headers in one buffer)
  // =====================================================================

  @Test
  fun `views at different base offsets do not interfere`() {
    // A buffer holding two ethernet headers back to back.
    val buf = PacketBuffer(ethernet.totalBits * 2)
    val first = HeaderView(buf, ethernet, base = 0)
    val second = HeaderView(buf, ethernet, base = ethernet.totalBits)

    first["dstAddr"] = BitVal(0xAAAAAAAAAAAAL, 48)
    second["dstAddr"] = BitVal(0xBBBBBBBBBBBBL, 48)

    assertEquals(BitVal(0xAAAAAAAAAAAAL, 48), first["dstAddr"])
    assertEquals(BitVal(0xBBBBBBBBBBBBL, 48), second["dstAddr"])
  }

  @Test
  fun `isValid at a non-zero base refers to the right bit`() {
    val buf = PacketBuffer(ethernet.totalBits * 2)
    val first = HeaderView(buf, ethernet, base = 0)
    val second = HeaderView(buf, ethernet, base = ethernet.totalBits)

    first.isValid = true

    assertTrue(first.isValid)
    assertFalse(second.isValid)
  }

  // =====================================================================
  // Fork / copy
  // =====================================================================

  @Test
  fun `views over a copied buffer are independent`() {
    val h = newHeader()
    h["dstAddr"] = BitVal(0x111111111111L, 48)
    h.isValid = true

    val forkedBuffer = h.buffer.copyOf()
    val forked = HeaderView(forkedBuffer, ethernet, base = 0)

    // The fork sees the parent's state.
    assertEquals(BitVal(0x111111111111L, 48), forked["dstAddr"])
    assertTrue(forked.isValid)

    // Mutating the fork doesn't affect the parent.
    forked["dstAddr"] = BitVal(0x222222222222L, 48)
    assertEquals(BitVal(0x111111111111L, 48), h["dstAddr"])
    assertEquals(BitVal(0x222222222222L, 48), forked["dstAddr"])
  }
}
