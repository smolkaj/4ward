package fourward.simulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/** Unit tests for [PacketBuffer]. */
class PacketBufferTest {

  // =====================================================================
  // Aligned reads/writes
  // =====================================================================

  @Test
  fun `read and write an 8-bit value at byte boundary`() {
    val buf = PacketBuffer(64)
    buf.writeBits(0, 8, 0xABL)
    assertEquals(0xABL, buf.readBits(0, 8))
  }

  @Test
  fun `read and write a 16-bit value at byte boundary`() {
    val buf = PacketBuffer(32)
    buf.writeBits(0, 16, 0x1234L)
    assertEquals(0x1234L, buf.readBits(0, 16))
  }

  @Test
  fun `read and write a 32-bit value at byte boundary`() {
    val buf = PacketBuffer(32)
    buf.writeBits(0, 32, 0xDEADBEEFL)
    assertEquals(0xDEADBEEFL, buf.readBits(0, 32))
  }

  @Test
  fun `read and write a 48-bit MAC address at byte boundary`() {
    val buf = PacketBuffer(64)
    buf.writeBits(0, 48, 0x0011_2233_4455L)
    assertEquals(0x0011_2233_4455L, buf.readBits(0, 48))
  }

  @Test
  fun `read and write a 64-bit value at byte boundary`() {
    val buf = PacketBuffer(64)
    val v = 0x0123_4567_89AB_CDEFL
    buf.writeBits(0, 64, v)
    assertEquals(v, buf.readBits(0, 64))
  }

  // =====================================================================
  // Unaligned reads/writes (the interesting cases)
  // =====================================================================

  @Test
  fun `read and write a 1-bit flag`() {
    val buf = PacketBuffer(8)
    buf.writeBits(0, 1, 1L)
    assertEquals(1L, buf.readBits(0, 1))
    buf.writeBits(0, 1, 0L)
    assertEquals(0L, buf.readBits(0, 1))
  }

  @Test
  fun `read and write a 9-bit value at non-byte offset`() {
    val buf = PacketBuffer(32)
    // P4 port numbers are typically 9 bits wide.
    buf.writeBits(3, 9, 0x1ABL)
    assertEquals(0x1ABL, buf.readBits(3, 9))
  }

  @Test
  fun `read and write adjacent narrow fields share bytes`() {
    val buf = PacketBuffer(16)
    // Two 4-bit fields packed into one byte.
    buf.writeBits(0, 4, 0xA)
    buf.writeBits(4, 4, 0xB)
    assertEquals(0xAL, buf.readBits(0, 4))
    assertEquals(0xBL, buf.readBits(4, 4))
    // And the underlying byte is 0xAB.
    assertEquals(0xABL, buf.readBits(0, 8))
  }

  @Test
  fun `writes do not corrupt neighbouring bits`() {
    val buf = PacketBuffer(32)
    // Fill with all ones.
    buf.writeBits(0, 32, 0xFFFFFFFFL)
    // Overwrite a 4-bit field in the middle.
    buf.writeBits(10, 4, 0x0)
    // Everything else should still be 1.
    assertEquals(0x3FFL, buf.readBits(0, 10))
    assertEquals(0x0L, buf.readBits(10, 4))
    assertEquals(0x3FFFFL, buf.readBits(14, 18))
  }

  @Test
  fun `read and write a value spanning multiple bytes at an unaligned offset`() {
    val buf = PacketBuffer(64)
    // 40 bits (5 bytes) starting at bit 3.
    val v = 0xAB_CD_EF_01_23L
    buf.writeBits(3, 40, v)
    assertEquals(v, buf.readBits(3, 40))
  }

  @Test
  fun `writing a narrow value masks the upper bits`() {
    val buf = PacketBuffer(16)
    // Writing 0xFF into a 4-bit slot should store only 0xF.
    buf.writeBits(0, 4, 0xFFL)
    assertEquals(0xFL, buf.readBits(0, 4))
    // And the adjacent 4-bit slot should remain 0.
    assertEquals(0x0L, buf.readBits(4, 4))
  }

  // =====================================================================
  // Bulk operations
  // =====================================================================

  @Test
  fun `zeroRange clears exactly the specified bits`() {
    val buf = PacketBuffer(32)
    buf.writeBits(0, 32, 0xFFFFFFFFL)
    buf.zeroRange(8, 16)
    assertEquals(0xFFL, buf.readBits(0, 8))
    assertEquals(0x0L, buf.readBits(8, 16))
    assertEquals(0xFFL, buf.readBits(24, 8))
  }

  @Test
  fun `zeroRange works at unaligned boundaries`() {
    val buf = PacketBuffer(32)
    buf.writeBits(0, 32, 0xFFFFFFFFL)
    // Zero 5 bits starting at bit 3.
    buf.zeroRange(3, 5)
    assertEquals(0xE0FFFFFFL.toInt().toLong() and 0xFFFFFFFFL, buf.readBits(0, 32))
  }

  @Test
  fun `copyOf produces an independent buffer`() {
    val buf = PacketBuffer(32)
    buf.writeBits(0, 32, 0x12345678L)
    val copy = buf.copyOf()

    // Same contents.
    assertEquals(0x12345678L, copy.readBits(0, 32))
    // Different underlying array.
    assertNotSame(buf.bytes, copy.bytes)

    // Mutation in one doesn't affect the other.
    copy.writeBits(0, 32, 0xDEADBEEFL)
    assertEquals(0x12345678L, buf.readBits(0, 32))
    assertEquals(0xDEADBEEFL, copy.readBits(0, 32))
  }

  @Test
  fun `fromBytes constructs a buffer with given contents`() {
    val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
    val buf = PacketBuffer.fromBytes(bytes)
    assertEquals(0x12345678L, buf.readBits(0, 32))
  }

  @Test
  fun `fromBytes does not share the input array`() {
    val bytes = byteArrayOf(0x12, 0x34)
    val buf = PacketBuffer.fromBytes(bytes)
    bytes[0] = 0x00
    // Buffer should still see the original value.
    assertEquals(0x1234L, buf.readBits(0, 16))
  }

  @Test
  fun `toBytes returns a snapshot copy`() {
    val buf = PacketBuffer(16)
    buf.writeBits(0, 16, 0xABCDL)
    val out = buf.toBytes()
    assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), out)
    // Mutating the snapshot doesn't change the buffer.
    out[0] = 0x00
    assertEquals(0xABCDL, buf.readBits(0, 16))
  }

  // =====================================================================
  // Bounds checking
  // =====================================================================

  @Test(expected = IllegalArgumentException::class)
  fun `read beyond buffer end throws`() {
    val buf = PacketBuffer(16)
    buf.readBits(8, 16) // would read bits 8..23, but buffer is only 16 bits
  }

  @Test(expected = IllegalArgumentException::class)
  fun `write beyond buffer end throws`() {
    val buf = PacketBuffer(16)
    buf.writeBits(8, 16, 0L)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `negative offset throws`() {
    val buf = PacketBuffer(32)
    buf.readBits(-1, 8)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `width greater than 64 throws`() {
    val buf = PacketBuffer(16)
    buf.readBits(0, 65)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `zero width throws`() {
    val buf = PacketBuffer(32)
    buf.readBits(0, 0)
  }
}
