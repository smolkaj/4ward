package fourward.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [StructLayout] and [StructView]. */
class StructViewTest {

  // A simplified standard_metadata_t with primitive fields only.
  private val stdMeta =
    StructLayout(
      typeName = "standard_metadata_t",
      members =
        linkedMapOf<String, StructMember>(
          "ingress_port" to PrimitiveField(offset = 0, width = 9),
          "egress_spec" to PrimitiveField(offset = 9, width = 9),
          "egress_port" to PrimitiveField(offset = 18, width = 9),
          "instance_type" to PrimitiveField(offset = 27, width = 32),
        ),
    )

  private fun newStruct() = StructView(PacketBuffer(stdMeta.totalBits), stdMeta, base = 0)

  @Test
  fun `totalBits equals the sum of primitive field widths`() {
    assertEquals(9 + 9 + 9 + 32, stdMeta.totalBits)
  }

  @Test
  fun `set and get individual primitive members`() {
    val s = newStruct()
    s["ingress_port"] = BitVal(1L, 9)
    s["egress_spec"] = BitVal(2L, 9)
    s["egress_port"] = BitVal(3L, 9)
    s["instance_type"] = BitVal(0x0DEADBEEL, 32)

    assertEquals(BitVal(1L, 9), s["ingress_port"])
    assertEquals(BitVal(2L, 9), s["egress_spec"])
    assertEquals(BitVal(3L, 9), s["egress_port"])
    assertEquals(BitVal(0x0DEADBEEL, 32), s["instance_type"])
  }

  @Test
  fun `unknown member name throws`() {
    val s = newStruct()
    assertThrows(IllegalArgumentException::class.java) { s["missing"] }
  }

  @Test
  fun `writing a value of the wrong width throws`() {
    val s = newStruct()
    assertThrows(IllegalArgumentException::class.java) {
      s["ingress_port"] = BitVal(0L, 8) // ingress_port is 9 bits
    }
  }

  @Test
  fun `views at different bases do not interfere`() {
    val buf = PacketBuffer(stdMeta.totalBits * 2)
    val first = StructView(buf, stdMeta, base = 0)
    val second = StructView(buf, stdMeta, base = stdMeta.totalBits)

    first["ingress_port"] = BitVal(5L, 9)
    second["ingress_port"] = BitVal(6L, 9)

    assertEquals(BitVal(5L, 9), first["ingress_port"])
    assertEquals(BitVal(6L, 9), second["ingress_port"])
  }

  // =====================================================================
  // Nested members (struct-of-header, struct-of-struct)
  // =====================================================================

  private val ethernet =
    HeaderLayout(
      typeName = "ethernet_t",
      fields =
        linkedMapOf(
          "dstAddr" to FieldSlot(0, 48),
          "srcAddr" to FieldSlot(48, 48),
          "etherType" to FieldSlot(96, 16),
        ),
      validBitOffset = 112,
    )

  private val headers =
    StructLayout(
      typeName = "headers_t",
      members =
        linkedMapOf<String, StructMember>(
          "ethernet" to NestedHeader(offset = 0, layout = ethernet),
          "meta" to NestedStruct(offset = ethernet.totalBits, layout = stdMeta),
        ),
    )

  @Test
  fun `struct view exposes nested header as HeaderView`() {
    val buf = PacketBuffer(headers.totalBits)
    val s = StructView(buf, headers)
    val eth = s.header("ethernet")
    eth["dstAddr"] = BitVal(0xAAAA_AAAA_AAAAL, 48)
    eth.isValid = true
    assertEquals(BitVal(0xAAAA_AAAA_AAAAL, 48), eth["dstAddr"])
    assertEquals(true, eth.isValid)
  }

  @Test
  fun `struct view exposes nested struct as StructView`() {
    val buf = PacketBuffer(headers.totalBits)
    val s = StructView(buf, headers)
    val meta = s.struct("meta")
    meta["ingress_port"] = BitVal(7L, 9)
    assertEquals(BitVal(7L, 9), meta["ingress_port"])
  }

  @Test
  fun `primitive access on a nested member throws`() {
    val buf = PacketBuffer(headers.totalBits)
    val s = StructView(buf, headers)
    assertThrows(IllegalArgumentException::class.java) { s["ethernet"] }
  }

  @Test
  fun `nested access on a primitive member throws`() {
    val s = newStruct()
    assertThrows(IllegalArgumentException::class.java) { s.header("ingress_port") }
    assertThrows(IllegalArgumentException::class.java) { s.struct("ingress_port") }
  }
}
