package fourward.simulator

import java.math.BigInteger
import java.util.zip.CRC32

// CRC-16/ARC reflected polynomial (0x8005 reflected = 0xA001).
// This matches boost::crc_16_type used by BMv2.
private const val CRC16_POLY = 0xA001
private const val BYTE_MASK = 0xFF
private const val CRC16_MASK = 0xFFFF

private const val CSUM_WORD_BITS = 16
private val CSUM_MASK = BigInteger.TWO.pow(CSUM_WORD_BITS).subtract(BigInteger.ONE)

/** Concatenates all [BitVal] fields in a [StructVal] into a single [BitVector]. */
private fun concatFields(data: StructVal): BitVector? =
  data.fields.values.map { (it as BitVal).bits }.reduceOrNull { acc, bv -> acc.concat(bv) }

/**
 * Ones' complement (csum16) checksum over the fields of a struct.
 *
 * Concatenates all fields into a bit string, pads to a 16-bit boundary, sums all 16-bit words with
 * end-around carry, and returns the complement. This is the standard Internet checksum (RFC 1071)
 * used by IPv4, TCP, and UDP.
 */
fun onesComplementChecksum(data: StructVal): BigInteger {
  val combined = concatFields(data) ?: return BigInteger.ZERO
  val totalWidth = combined.width
  val padded = ((totalWidth + CSUM_WORD_BITS - 1) / CSUM_WORD_BITS) * CSUM_WORD_BITS
  var bits = combined.value.shiftLeft(padded - totalWidth)
  var sum = BigInteger.ZERO
  for (i in 0 until padded / CSUM_WORD_BITS) {
    val word = bits.shiftRight(padded - (i + 1) * CSUM_WORD_BITS).and(CSUM_MASK)
    sum = sum.add(word)
  }
  while (sum > CSUM_MASK) {
    sum = sum.and(CSUM_MASK).add(sum.shiftRight(CSUM_WORD_BITS))
  }
  return CSUM_MASK.subtract(sum)
}

/**
 * CRC-16/ARC (IBM/LHA) — the variant used by BMv2's `HashAlgorithm.crc16`.
 *
 * Parameters: poly=0x8005, init=0, reflect_in=true, reflect_out=true, xor_out=0. Uses the reflected
 * algorithm (shift right with reflected polynomial) to avoid per-byte bit reversal.
 */
private fun crc16(data: ByteArray): Int {
  var crc = 0
  for (byte in data) {
    crc = crc xor (byte.toInt() and BYTE_MASK)
    repeat(Byte.SIZE_BITS) { crc = if (crc and 1 != 0) (crc ushr 1) xor CRC16_POLY else crc ushr 1 }
  }
  return crc and CRC16_MASK
}

/**
 * CRC-32 (ISO-HDLC) — the variant used by BMv2's `HashAlgorithm.crc32`.
 *
 * Delegates to [java.util.zip.CRC32] which implements the same algorithm (poly=0x04C11DB7,
 * init=0xFFFFFFFF, reflect_in=true, reflect_out=true, xor_out=0xFFFFFFFF).
 */
private fun crc32(data: ByteArray): Long {
  val crc = CRC32()
  crc.update(data)
  return crc.value
}

/** Converts a [StructVal]'s fields to a big-endian byte array for hashing. */
private fun structToBytes(data: StructVal): ByteArray =
  concatFields(data)?.toByteArray() ?: ByteArray(0)

/**
 * Computes a hash over [data] using the specified [algorithm].
 *
 * Supports csum16 (ones' complement), crc16, and crc32 — the algorithms used by BMv2's
 * simple_switch. Returns the raw hash value as a [BigInteger].
 */
fun computeHash(algorithm: String, data: StructVal): BigInteger =
  when (algorithm) {
    "csum16" -> onesComplementChecksum(data)
    "crc16" -> BigInteger.valueOf(crc16(structToBytes(data)).toLong())
    "crc32" -> BigInteger.valueOf(crc32(structToBytes(data)))
    "identity" -> {
      val bytes = structToBytes(data)
      if (bytes.isEmpty()) BigInteger.ZERO else BigInteger(1, bytes)
    }
    else -> error("unsupported hash algorithm: $algorithm")
  }
