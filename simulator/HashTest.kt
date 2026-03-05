package fourward.simulator

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class HashTest {

  private fun structOf(vararg fields: Pair<String, BitVal>): StructVal =
    StructVal("test", fields.toMap(mutableMapOf()))

  @Test
  fun `crc16 of empty input is zero`() {
    assertEquals(0, crc16(ByteArray(0)))
  }

  @Test
  fun `crc16 matches known value for single byte`() {
    // CRC-16/ARC of [0x01] = 0xC0C1
    assertEquals(0xC0C1, crc16(byteArrayOf(0x01)))
  }

  @Test
  fun `crc16 matches constant-in-calculation-bmv2 expectation`() {
    // hash(h.a, crc16, 10w0, { 16w1 }, 10w4) → result = 0 + crc16([0x00,0x01]) % 4 = 1
    val data = byteArrayOf(0x00, 0x01)
    assertEquals(1, crc16(data) % 4)
  }

  @Test
  fun `crc16 matches issue1049-bmv2 expectation`() {
    // hash over { srcAddr=0x0c0c0c0c, dstAddr=0x14020202, protocol=0x00 }
    // Expected result: 0x8208
    val data =
      byteArrayOf(
        0x0c, 0x0c, 0x0c, 0x0c, // srcAddr
        0x14, 0x02, 0x02, 0x02, // dstAddr
        0x00, // protocol
      )
    assertEquals(0x8208, crc16(data) % 0xFFFF)
  }

  @Test
  fun `crc32 of empty input is zero`() {
    assertEquals(0L, crc32(ByteArray(0)))
  }

  @Test
  fun `crc32 of check string matches standard`() {
    // CRC-32 of "123456789" = 0xCBF43926
    assertEquals(0xCBF43926L, crc32("123456789".toByteArray()))
  }

  @Test
  fun `structToBytes concatenates fields into big-endian bytes`() {
    val data = structOf("a" to BitVal(0xAB, 8), "b" to BitVal(0xCD, 8))
    val bytes = structToBytes(data)
    assertEquals(2, bytes.size)
    assertEquals(0xAB.toByte(), bytes[0])
    assertEquals(0xCD.toByte(), bytes[1])
  }

  @Test
  fun `structToBytes pads non-byte-aligned fields`() {
    // 4-bit 0xA → padded to 1 byte: 0xA0
    val data = structOf("x" to BitVal(0xA, 4))
    val bytes = structToBytes(data)
    assertEquals(1, bytes.size)
    assertEquals(0xA0.toByte(), bytes[0])
  }

  @Test
  fun `structToBytes handles empty struct`() {
    val data = structOf()
    assertEquals(0, structToBytes(data).size)
  }

  @Test
  fun `computeHash csum16 delegates to onesComplementChecksum`() {
    val data = structOf("d" to BitVal(0x0001, 16))
    assertEquals(BigInteger.valueOf(0xFFFE), computeHash("csum16", data))
  }

  @Test
  fun `computeHash crc16 returns expected value`() {
    val data = structOf("d" to BitVal(0x0001, 16))
    val expected = BigInteger.valueOf(crc16(byteArrayOf(0x00, 0x01)).toLong())
    assertEquals(expected, computeHash("crc16", data))
  }

  @Test
  fun `computeHash identity returns raw concatenated value`() {
    val data = structOf("a" to BitVal(0xAB, 8), "b" to BitVal(0xCD, 8))
    assertEquals(BigInteger.valueOf(0xABCD), computeHash("identity", data))
  }
}
