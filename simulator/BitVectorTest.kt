package fourward.simulator

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BitVectorTest {

  @Test
  fun `addition wraps at width boundary`() {
    val a = BitVector.ofInt(0xFF, 8)
    val b = BitVector.ofInt(1, 8)
    assertEquals(BitVector.ofInt(0, 8), a + b)
  }

  @Test
  fun `saturating addition clamps to max`() {
    val a = BitVector.ofInt(0xFE, 8)
    val b = BitVector.ofInt(5, 8)
    assertEquals(BitVector.ofInt(0xFF, 8), a.addSat(b))
  }

  @Test
  fun `saturating subtraction clamps to zero`() {
    val a = BitVector.ofInt(2, 8)
    val b = BitVector.ofInt(5, 8)
    assertEquals(BitVector.ofInt(0, 8), a.subSat(b))
  }

  @Test
  fun `slice extracts correct bits`() {
    val v = BitVector(BigInteger.valueOf(0xABCDL), 16)
    // bits [11:8] of 0xABCD = 0xB
    assertEquals(BitVector.ofInt(0xB, 4), v.slice(11, 8))
  }

  @Test
  fun `concat produces correct width and value`() {
    val hi = BitVector.ofInt(0xAB, 8)
    val lo = BitVector.ofInt(0xCD, 8)
    assertEquals(BitVector(BigInteger.valueOf(0xABCDL), 16), hi.concat(lo))
  }

  @Test
  fun `toByteArray produces big-endian bytes`() {
    val v = BitVector(BigInteger.valueOf(0xABCDL), 16)
    assertEquals(listOf(0xAB.toByte(), 0xCD.toByte()), v.toByteArray().toList())
  }

  @Test
  fun `ofBytes round-trips with toByteArray`() {
    val original = BitVector(BigInteger.valueOf(0x1234L), 16)
    val bytes = original.toByteArray()
    assertEquals(original, BitVector.ofBytes(bytes, 16))
  }

  @Test
  fun `width mismatch throws on arithmetic`() {
    val a = BitVector.ofInt(1, 8)
    val b = BitVector.ofInt(1, 16)
    assertThrows(IllegalArgumentException::class.java) { a + b }
  }

  @Test
  fun `signed bit vector sign-extends correctly`() {
    // 0b10000000 = 128 unsigned = -128 signed in 8 bits
    val s = SignedBitVector.fromUnsignedBits(BigInteger.valueOf(128), 8)
    assertEquals(BigInteger.valueOf(-128), s.value)
  }

  @Test
  fun `signed to unsigned reinterprets bits`() {
    val s = SignedBitVector(BigInteger.valueOf(-1), 8)
    assertEquals(BitVector.ofInt(0xFF, 8), s.toUnsigned())
  }
}
