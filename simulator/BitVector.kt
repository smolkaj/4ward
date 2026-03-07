package fourward.simulator

import java.math.BigInteger

/**
 * A bit-precise unsigned integer of fixed width, corresponding to P4's `bit<N>` type.
 *
 * All arithmetic is performed with BigInteger and then masked to [width] bits, matching the P4
 * spec's truncating-on-overflow semantics. No operation ever produces a value outside [0, 2^width).
 */
data class BitVector(val value: BigInteger, val width: Int) {

  init {
    // Width 0 is valid for varbit fields with no runtime data (e.g. IPv4 options when IHL=5).
    require(width >= 0) { "width must be non-negative, got $width" }
    require(value >= BigInteger.ZERO) { "value must be non-negative, got $value" }
    if (width > 0) {
      require(value < BigInteger.TWO.pow(width)) { "value $value does not fit in $width bits" }
    } else {
      require(value == BigInteger.ZERO) { "zero-width BitVector must have value 0" }
    }
  }

  // Arithmetic — all results are truncated to [width] bits.

  operator fun plus(other: BitVector): BitVector = binaryOp(other) { a, b -> a + b }

  operator fun minus(other: BitVector): BitVector = binaryOp(other) { a, b -> a - b }

  operator fun times(other: BitVector): BitVector = binaryOp(other) { a, b -> a * b }

  operator fun div(other: BitVector): BitVector = binaryOp(other) { a, b -> a / b }

  operator fun rem(other: BitVector): BitVector = binaryOp(other) { a, b -> a % b }

  /** Saturating addition: clamps to 2^width - 1 on overflow. */
  fun addSat(other: BitVector): BitVector {
    requireSameWidth(other)
    val result = value + other.value
    return if (result >= max) BitVector(max, width) else BitVector(result, width)
  }

  /** Saturating subtraction: clamps to 0 on underflow. */
  fun subSat(other: BitVector): BitVector {
    requireSameWidth(other)
    val result = value - other.value
    return if (result < BigInteger.ZERO) BitVector(BigInteger.ZERO, width)
    else BitVector(result, width)
  }

  // Bitwise operations — operands must have the same width.

  infix fun and(other: BitVector): BitVector = binaryOp(other) { a, b -> a and b }

  infix fun or(other: BitVector): BitVector = binaryOp(other) { a, b -> a or b }

  infix fun xor(other: BitVector): BitVector = binaryOp(other) { a, b -> a xor b }

  fun inv(): BitVector = BitVector(value.not() and mask, width)

  // Shifts — the shift amount is an arbitrary non-negative integer.

  fun shl(amount: Int): BitVector = BitVector((value shl amount) and mask, width)

  fun shr(amount: Int): BitVector = BitVector(value shr amount, width) // logical shift

  // Comparisons — operands must have the same width.

  operator fun compareTo(other: BitVector): Int {
    requireSameWidth(other)
    return value.compareTo(other.value)
  }

  /**
   * Extracts bits [hi:lo] (inclusive, P4 slice notation).
   *
   * Example: for a bit<16> value 0xABCD, slice(11, 8) returns 0xB as bit<4>.
   */
  fun slice(hi: Int, lo: Int): BitVector {
    require(hi >= lo) { "hi ($hi) must be >= lo ($lo)" }
    require(hi < width) { "hi ($hi) out of range for width $width" }
    val newWidth = hi - lo + 1
    return BitVector(
      (value shr lo) and BigInteger.TWO.pow(newWidth).minus(BigInteger.ONE),
      newWidth,
    )
  }

  /**
   * Concatenates this (most-significant) with [other] (least-significant).
   *
   * Corresponds to P4's `++` operator: bit<m> ++ bit<n> produces bit<m+n>.
   */
  fun concat(other: BitVector): BitVector {
    val newWidth = width + other.width
    return BitVector((value shl other.width) or other.value, newWidth)
  }

  /** Converts to a signed [SignedBitVector] of the same width (reinterprets bits). */
  fun toSigned(): SignedBitVector = SignedBitVector.fromUnsignedBits(value, width)

  /** Returns the value as a big-endian byte array, padded to ceil(width/8) bytes. */
  fun toByteArray(): ByteArray {
    val byteLen = (width + BITS_PER_BYTE - 1) / BITS_PER_BYTE
    val raw = value.toByteArray()
    // BigInteger.toByteArray() may include a leading 0x00 sign byte.
    return ByteArray(byteLen) { i ->
      val srcIdx = raw.size - byteLen + i
      if (srcIdx < 0) 0 else raw[srcIdx]
    }
  }

  override fun toString(): String = "0x${value.toString(16)} : bit<$width>"

  private val mask: BigInteger
    get() = BigInteger.TWO.pow(width).minus(BigInteger.ONE)

  private val max: BigInteger
    get() = mask

  private fun binaryOp(other: BitVector, op: (BigInteger, BigInteger) -> BigInteger): BitVector {
    requireSameWidth(other)
    return BitVector(op(value, other.value).mod(BigInteger.TWO.pow(width)), width)
  }

  private fun requireSameWidth(other: BitVector) {
    require(width == other.width) { "width mismatch: $width vs ${other.width}" }
  }

  companion object {
    const val BITS_PER_BYTE = 8

    fun ofInt(value: Int, width: Int): BitVector =
      BitVector(BigInteger.valueOf(value.toLong()), width)

    fun ofLong(value: Long, width: Int): BitVector = BitVector(BigInteger.valueOf(value), width)

    /** Returns a bit<N> with all bits set (value = 2^width - 1). */
    fun allOnes(width: Int): BitVector =
      BitVector(BigInteger.TWO.pow(width).minus(BigInteger.ONE), width)

    /** Decodes a big-endian byte array into a bit<N> value. */
    fun ofBytes(bytes: ByteArray, width: Int): BitVector {
      val value = BigInteger(1, bytes) // 1 = positive sign
      return BitVector(value, width)
    }
  }
}

/**
 * A bit-precise signed integer of fixed width, corresponding to P4's `int<N>` type.
 *
 * Stored internally as a two's-complement signed BigInteger in the range [-2^(width-1),
 * 2^(width-1)).
 */
data class SignedBitVector(val value: BigInteger, val width: Int) {

  init {
    require(width > 0) { "width must be positive, got $width" }
    val min = BigInteger.TWO.pow(width - 1).negate()
    val max = BigInteger.TWO.pow(width - 1)
    require(value >= min && value < max) {
      "value $value does not fit in a signed $width-bit integer"
    }
  }

  /** Saturating addition: clamps to [minVal, maxVal] on overflow. */
  fun addSat(other: SignedBitVector): SignedBitVector {
    require(width == other.width)
    val result = value + other.value
    return SignedBitVector(result.coerceIn(minVal, maxVal), width)
  }

  /** Saturating subtraction: clamps to [minVal, maxVal] on underflow. */
  fun subSat(other: SignedBitVector): SignedBitVector {
    require(width == other.width)
    val result = value - other.value
    return SignedBitVector(result.coerceIn(minVal, maxVal), width)
  }

  private val minVal by lazy { BigInteger.TWO.pow(width - 1).negate() }
  private val maxVal by lazy { BigInteger.TWO.pow(width - 1) - BigInteger.ONE }

  /** Reinterprets the bits as an unsigned [BitVector]. */
  fun toUnsigned(): BitVector {
    val unsigned =
      if (value < BigInteger.ZERO) {
        value + BigInteger.TWO.pow(width)
      } else {
        value
      }
    return BitVector(unsigned, width)
  }

  companion object {
    /** Constructs from raw unsigned bits (performs sign extension). */
    fun fromUnsignedBits(bits: BigInteger, width: Int): SignedBitVector {
      val signBit = BigInteger.ONE.shiftLeft(width - 1)
      val value = if (bits >= signBit) bits - signBit.shiftLeft(1) else bits
      return SignedBitVector(value, width)
    }
  }
}
