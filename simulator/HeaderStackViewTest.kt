package fourward.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [HeaderStackLayout] and [HeaderStackView]. */
class HeaderStackViewTest {

  // A tiny header type for the stack's elements: 16-bit value plus a validity bit.
  private val element =
    HeaderLayout(
      typeName = "elt_t",
      fields = linkedMapOf("v" to FieldSlot(0, 16, PrimitiveKind.BIT)),
      validBitOffset = 16,
    )

  private val stack3 = HeaderStackLayout(typeName = "elt_t[3]", elementLayout = element, size = 3)

  private fun newStack(): HeaderStackView {
    val buffer = PacketBuffer(stack3.totalBits)
    return HeaderStackView(buffer, stack3)
  }

  @Test
  fun `totalBits equals counter plus size times element bits`() {
    val expected = HeaderStackLayout.NEXT_INDEX_WIDTH + 3 * element.totalBits
    assertEquals(expected, stack3.totalBits)
  }

  @Test
  fun `nextIndex round-trips through the buffer`() {
    val s = newStack()
    assertEquals(0, s.nextIndex)
    s.nextIndex = 2
    assertEquals(2, s.nextIndex)
  }

  @Test
  fun `nextIndex of size is allowed, out-of-range throws`() {
    val s = newStack()
    s.nextIndex = stack3.size // valid: stack is full
    assertEquals(stack3.size, s.nextIndex)
    assertThrows(IllegalArgumentException::class.java) { s.nextIndex = stack3.size + 1 }
    assertThrows(IllegalArgumentException::class.java) { s.nextIndex = -1 }
  }

  @Test
  fun `get returns a HeaderView at the right offset`() {
    val s = newStack()
    s[0]["v"] = BitVal(0xAAAAL, 16)
    s[1]["v"] = BitVal(0xBBBBL, 16)
    s[2]["v"] = BitVal(0xCCCCL, 16)
    s[1].isValid = true

    assertEquals(BitVal(0xAAAAL, 16), s[0]["v"])
    assertEquals(BitVal(0xBBBBL, 16), s[1]["v"])
    assertEquals(BitVal(0xCCCCL, 16), s[2]["v"])
    assertTrue(s[1].isValid)
    assertFalse(s[0].isValid)
    assertFalse(s[2].isValid)
  }

  @Test
  fun `out-of-range index throws`() {
    val s = newStack()
    assertThrows(IllegalArgumentException::class.java) { s[stack3.size] }
    assertThrows(IllegalArgumentException::class.java) { s[-1] }
  }

  @Test
  fun `pushFront shifts elements up and zeros the low slots`() {
    val s = newStack()
    s[0]["v"] = BitVal(0x1111L, 16)
    s[0].isValid = true
    s[1]["v"] = BitVal(0x2222L, 16)
    s[1].isValid = true
    s.nextIndex = 2

    s.pushFront(1)

    assertEquals(BitVal(0L, 16), s[0]["v"])
    assertFalse(s[0].isValid)
    assertEquals(BitVal(0x1111L, 16), s[1]["v"])
    assertTrue(s[1].isValid)
    assertEquals(BitVal(0x2222L, 16), s[2]["v"])
    assertTrue(s[2].isValid)
    assertEquals(3, s.nextIndex)
  }

  @Test
  fun `pushFront greater than size clamps and zeros everything`() {
    val s = newStack()
    s[0]["v"] = BitVal(0x1111L, 16)
    s[1]["v"] = BitVal(0x2222L, 16)

    s.pushFront(stack3.size + 5)

    assertEquals(BitVal(0L, 16), s[0]["v"])
    assertEquals(BitVal(0L, 16), s[1]["v"])
    assertEquals(BitVal(0L, 16), s[2]["v"])
  }

  @Test
  fun `popFront shifts elements down and zeros the high slots`() {
    val s = newStack()
    s[0]["v"] = BitVal(0x1111L, 16)
    s[0].isValid = true
    s[1]["v"] = BitVal(0x2222L, 16)
    s[1].isValid = true
    s[2]["v"] = BitVal(0x3333L, 16)
    s[2].isValid = true
    s.nextIndex = 3

    s.popFront(1)

    assertEquals(BitVal(0x2222L, 16), s[0]["v"])
    assertTrue(s[0].isValid)
    assertEquals(BitVal(0x3333L, 16), s[1]["v"])
    assertTrue(s[1].isValid)
    assertEquals(BitVal(0L, 16), s[2]["v"])
    assertFalse(s[2].isValid)
    assertEquals(2, s.nextIndex)
  }

  @Test
  fun `pushFront then popFront preserves the original middle elements`() {
    val s = newStack()
    s[0]["v"] = BitVal(0xAAAAL, 16)
    s[1]["v"] = BitVal(0xBBBBL, 16)

    s.pushFront(1)
    s.popFront(1)

    assertEquals(BitVal(0xAAAAL, 16), s[0]["v"])
    assertEquals(BitVal(0xBBBBL, 16), s[1]["v"])
  }

  @Test
  fun `nested stack via StructView`() {
    val outer =
      StructLayout(
        typeName = "outer_t",
        members = linkedMapOf<String, StructMember>("stk" to NestedStack(0, stack3)),
      )
    val buffer = PacketBuffer(outer.totalBits)
    val view = StructView(buffer, outer)
    val s = view.stack("stk")
    s[0]["v"] = BitVal(0xCAFEL, 16)
    assertEquals(BitVal(0xCAFEL, 16), s[0]["v"])
  }
}
