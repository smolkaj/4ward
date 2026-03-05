package fourward.simulator

import java.math.BigInteger

private const val CSUM_WORD_BITS = 16
private val CSUM_MASK = BigInteger.TWO.pow(CSUM_WORD_BITS).subtract(BigInteger.ONE)

/**
 * Ones' complement (csum16) checksum over the fields of a struct.
 *
 * Concatenates all fields into a bit string, pads to a 16-bit boundary, sums all 16-bit words with
 * end-around carry, and returns the complement. This is the standard Internet checksum (RFC 1071)
 * used by IPv4, TCP, and UDP.
 */
fun onesComplementChecksum(data: StructVal): BigInteger {
  val combined =
    data.fields.values.map { (it as BitVal).bits }.reduceOrNull { acc, bv -> acc.concat(bv) }
      ?: return BigInteger.ZERO
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
