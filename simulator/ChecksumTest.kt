package fourward.simulator

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumTest {

  private fun structOf(vararg fields: Pair<String, BitVal>): StructVal =
    StructVal("test", fields.toMap(mutableMapOf()))

  @Test
  fun `single 16-bit field returns ones complement`() {
    // ~0x0001 = 0xFFFE
    val data = structOf("d" to BitVal(0x0001, 16))
    assertEquals(BigInteger.valueOf(0xFFFE), onesComplementChecksum(data))
  }

  @Test
  fun `complement of zero is all ones`() {
    val data = structOf("d" to BitVal(0x0000, 16))
    assertEquals(BigInteger.valueOf(0xFFFF), onesComplementChecksum(data))
  }

  @Test
  fun `complement of all ones is zero`() {
    val data = structOf("d" to BitVal(0xFFFF, 16))
    assertEquals(BigInteger.ZERO, onesComplementChecksum(data))
  }

  @Test
  fun `two 16-bit fields are summed with carry`() {
    // 0xFFFF + 0x0001 = 0x10000, fold carry → 0x0001, complement → 0xFFFE
    val data = structOf("a" to BitVal(0xFFFF, 16), "b" to BitVal(0x0001, 16))
    assertEquals(BigInteger.valueOf(0xFFFE), onesComplementChecksum(data))
  }

  @Test
  fun `IPv4 header checksum from RFC 1071`() {
    // RFC 1071 example: 5 16-bit words summing to a known checksum.
    val data =
      structOf(
        "w0" to BitVal(0x4500, 16),
        "w1" to BitVal(0x0073, 16),
        "w2" to BitVal(0x0000, 16),
        "w3" to BitVal(0x4000, 16),
        "w4" to BitVal(0x4006, 16),
        // skip checksum field itself (would be 0x0000 for computation)
        "w5" to BitVal(0xAC10, 16), // 172.16.x.x
        "w6" to BitVal(0x0A63, 16),
        "w7" to BitVal(0xAC10, 16),
        "w8" to BitVal(0x0A0C, 16),
      )
    // Verify: sum of all words + checksum should fold to 0xFFFF.
    val csum = onesComplementChecksum(data)
    // Adding csum back to the data should yield 0xFFFF (valid checksum property).
    val verify =
      structOf(
        "w0" to BitVal(0x4500, 16),
        "w1" to BitVal(0x0073, 16),
        "w2" to BitVal(0x0000, 16),
        "w3" to BitVal(0x4000, 16),
        "w4" to BitVal(0x4006, 16),
        "w5" to BitVal(0xAC10, 16),
        "w6" to BitVal(0x0A63, 16),
        "w7" to BitVal(0xAC10, 16),
        "w8" to BitVal(0x0A0C, 16),
        "csum" to BitVal(csum.toLong(), 16),
      )
    assertEquals(BigInteger.ZERO, onesComplementChecksum(verify))
  }

  @Test
  fun `non-aligned fields are padded to 16-bit boundary`() {
    // Single 8-bit field 0xAB → padded to 0xAB00 → complement = 0x54FF
    val data = structOf("x" to BitVal(0xAB, 8))
    assertEquals(BigInteger.valueOf(0x54FF), onesComplementChecksum(data))
  }

  @Test
  fun `mixed-width fields concatenate correctly`() {
    // 4-bit 0xA + 12-bit 0xBCD = 16-bit word 0xABCD → complement = 0x5432
    val data = structOf("hi" to BitVal(0xA, 4), "lo" to BitVal(0xBCD, 12))
    assertEquals(BigInteger.valueOf(0x5432), onesComplementChecksum(data))
  }

  @Test
  fun `empty struct returns zero`() {
    val data = structOf()
    assertEquals(BigInteger.ZERO, onesComplementChecksum(data))
  }
}
