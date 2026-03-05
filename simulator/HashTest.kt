package fourward.simulator

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class HashTest {

  private fun structOf(vararg fields: Pair<String, BitVal>): StructVal =
    StructVal("test", fields.toMap(mutableMapOf()))

  // ---------------------------------------------------------------------------
  // onesComplementChecksum (csum16)
  // ---------------------------------------------------------------------------

  @Test
  fun `csum16 single 16-bit field returns ones complement`() {
    // ~0x0001 = 0xFFFE
    val data = structOf("d" to BitVal(0x0001, 16))
    assertEquals(BigInteger.valueOf(0xFFFE), onesComplementChecksum(data))
  }

  @Test
  fun `csum16 complement of zero is all ones`() {
    val data = structOf("d" to BitVal(0x0000, 16))
    assertEquals(BigInteger.valueOf(0xFFFF), onesComplementChecksum(data))
  }

  @Test
  fun `csum16 complement of all ones is zero`() {
    val data = structOf("d" to BitVal(0xFFFF, 16))
    assertEquals(BigInteger.ZERO, onesComplementChecksum(data))
  }

  @Test
  fun `csum16 two 16-bit fields are summed with carry`() {
    // 0xFFFF + 0x0001 = 0x10000, fold carry → 0x0001, complement → 0xFFFE
    val data = structOf("a" to BitVal(0xFFFF, 16), "b" to BitVal(0x0001, 16))
    assertEquals(BigInteger.valueOf(0xFFFE), onesComplementChecksum(data))
  }

  @Test
  fun `csum16 IPv4 header round-trips to zero`() {
    val data =
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
      )
    val csum = onesComplementChecksum(data)
    // Adding csum back to the data should yield zero (valid checksum property).
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
  fun `csum16 non-aligned fields are padded to 16-bit boundary`() {
    // Single 8-bit field 0xAB → padded to 0xAB00 → complement = 0x54FF
    val data = structOf("x" to BitVal(0xAB, 8))
    assertEquals(BigInteger.valueOf(0x54FF), onesComplementChecksum(data))
  }

  @Test
  fun `csum16 mixed-width fields concatenate correctly`() {
    // 4-bit 0xA + 12-bit 0xBCD = 16-bit word 0xABCD → complement = 0x5432
    val data = structOf("hi" to BitVal(0xA, 4), "lo" to BitVal(0xBCD, 12))
    assertEquals(BigInteger.valueOf(0x5432), onesComplementChecksum(data))
  }

  @Test
  fun `csum16 empty struct returns zero`() {
    assertEquals(BigInteger.ZERO, onesComplementChecksum(structOf()))
  }

  // ---------------------------------------------------------------------------
  // computeHash (crc16, crc32, identity)
  // ---------------------------------------------------------------------------

  @Test
  fun `computeHash crc16 matches constant-in-calculation-bmv2 expectation`() {
    // hash(h.a, crc16, 10w0, { 16w1 }, 10w4) → result = 0 + crc16([0x00,0x01]) % 4 = 1
    val data = structOf("d" to BitVal(0x0001, 16))
    assertEquals(BigInteger.ONE, computeHash("crc16", data).mod(BigInteger.valueOf(4)))
  }

  @Test
  fun `computeHash crc16 matches issue1049-bmv2 expectation`() {
    // hash over { srcAddr=0x0c0c0c0c, dstAddr=0x14020202, protocol=0x00 }
    val data =
      structOf(
        "srcAddr" to BitVal(0x0c0c0c0c, 32),
        "dstAddr" to BitVal(0x14020202, 32),
        "protocol" to BitVal(0x00, 8),
      )
    val max = BigInteger.valueOf(0xFFFF)
    assertEquals(BigInteger.valueOf(0x8208), computeHash("crc16", data).mod(max))
  }

  @Test
  fun `computeHash crc32 matches standard check value`() {
    // CRC-32 of "123456789" = 0xCBF43926. Encode each ASCII char as 8-bit field.
    val fields = "123456789".mapIndexed { i, c -> "c$i" to BitVal(c.code, 8) }
    val data = StructVal("test", fields.toMap(mutableMapOf()))
    assertEquals(BigInteger.valueOf(0xCBF43926L), computeHash("crc32", data))
  }

  @Test
  fun `computeHash csum16 delegates to onesComplementChecksum`() {
    val data = structOf("d" to BitVal(0x0001, 16))
    assertEquals(BigInteger.valueOf(0xFFFE), computeHash("csum16", data))
  }

  @Test
  fun `computeHash identity returns raw concatenated value`() {
    val data = structOf("a" to BitVal(0xAB, 8), "b" to BitVal(0xCD, 8))
    assertEquals(BigInteger.valueOf(0xABCD), computeHash("identity", data))
  }

  @Test
  fun `computeHash identity of empty struct is zero`() {
    assertEquals(BigInteger.ZERO, computeHash("identity", structOf()))
  }
}
