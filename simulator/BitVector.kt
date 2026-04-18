package fourward.simulator

import java.math.BigInteger

/**
 * A bit-precise unsigned integer of fixed width, corresponding to P4's `bit<N>` type.
 *
 * For widths ≤ 63 (the vast majority of P4 fields), the value is stored as a primitive [Long] in
 * [longValue] — no [BigInteger] allocation on construction or arithmetic. The [value] property
 * lazily reconstructs a [BigInteger] only when callers need it (byte serialization, trace output,
 * wide-operand interop). For widths > 63, [BigInteger] is the primary storage.
 *
 * All arithmetic is truncated to [width] bits, matching the P4 spec's overflow semantics.
 *
 * This is the single biggest hot-path optimization in the simulator: `BitVector.<init>` was 13.5%
 * of parallel CPU before the Long fast path, driven by `BigInteger.TWO.pow(width)` in the
 * validation and `BigInteger.valueOf` in every factory. See `designs/parallel_packet_scaling.md`.
 */
class BitVector
private constructor(
  val width: Int,
  /** For widths ≤ [LONG_WIDTH]: the unsigned value. For wider widths: 0 (use [value] instead). */
  val longValue: Long,
  /** For widths > [LONG_WIDTH]: the value. For narrower: null, reconstructed lazily by [value]. */
  @Volatile private var bigValue: BigInteger?,
) {

  /** The value as a non-negative [BigInteger]. Lazy for narrow widths (avoids allocation). */
  val value: BigInteger
    get() = bigValue ?: BigInteger.valueOf(longValue).also { bigValue = it }

  /**
   * Public constructor from [BigInteger]. Validates and stores as [Long] when [width] ≤ 63 to avoid
   * keeping the [BigInteger] reference.
   */
  constructor(
    value: BigInteger,
    width: Int,
  ) : this(
    width = width,
    longValue = if (width <= LONG_WIDTH) value.toLong() else 0L,
    bigValue = if (width > LONG_WIDTH) value else null,
  ) {
    require(width >= 0) { "width must be non-negative, got $width" }
    require(value.signum() >= 0) { "value must be non-negative, got $value" }
    require(value.bitLength() <= width) { "value $value does not fit in $width bits" }
  }

  // -------------------------------------------------------------------------
  // Arithmetic — Long fast path for widths ≤ 63, BigInteger for wider.
  // -------------------------------------------------------------------------

  operator fun plus(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue + other.longValue)
    else wide((value + other.value) and maskFor(width))
  }

  operator fun minus(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue - other.longValue)
    else wide((value - other.value) and maskFor(width))
  }

  operator fun times(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue * other.longValue)
    else wide((value * other.value) and maskFor(width))
  }

  operator fun div(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue / other.longValue)
    else wide(value / other.value)
  }

  operator fun rem(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue % other.longValue)
    else wide((value % other.value) and maskFor(width))
  }

  /** Saturating addition: clamps to 2^width - 1 on overflow. */
  fun addSat(other: BitVector): BitVector {
    requireSameWidth(other)
    if (width <= LONG_WIDTH) {
      val result = longValue + other.longValue
      val mask = longMaskFor(width)
      return narrow(if (result < 0 || result > mask) mask else result)
    }
    val result = value + other.value
    val max = maskFor(width)
    return if (result > max) wide(max) else wide(result)
  }

  /** Saturating subtraction: clamps to 0 on underflow. */
  fun subSat(other: BitVector): BitVector {
    requireSameWidth(other)
    if (width <= LONG_WIDTH) {
      val result = longValue - other.longValue
      return narrow(if (result < 0) 0L else result)
    }
    val result = value - other.value
    return if (result < BigInteger.ZERO) wide(BigInteger.ZERO) else wide(result)
  }

  // Bitwise operations — operands must have the same width.

  infix fun and(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue and other.longValue)
    else wide(value and other.value)
  }

  infix fun or(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue or other.longValue)
    else wide(value or other.value)
  }

  infix fun xor(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) narrow(longValue xor other.longValue)
    else wide(value xor other.value)
  }

  fun inv(): BitVector =
    if (width <= LONG_WIDTH) narrow(longValue.inv() and longMaskFor(width))
    else wide(value.not() and maskFor(width))

  // Shifts — the shift amount is an arbitrary non-negative integer.

  fun shl(amount: Int): BitVector =
    if (width <= LONG_WIDTH) narrow((longValue shl amount) and longMaskFor(width))
    else wide((value shl amount) and maskFor(width))

  fun shr(amount: Int): BitVector =
    if (width <= LONG_WIDTH) narrow(longValue ushr amount) else wide(value shr amount)

  // Comparisons — operands must have the same width.

  operator fun compareTo(other: BitVector): Int {
    requireSameWidth(other)
    return if (width <= LONG_WIDTH) longValue.compareTo(other.longValue)
    else value.compareTo(other.value)
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
    return if (width <= LONG_WIDTH && newWidth <= LONG_WIDTH) {
      BitVector(newWidth, (longValue ushr lo) and longMaskFor(newWidth), null)
    } else {
      BitVector((value shr lo) and maskFor(newWidth), newWidth)
    }
  }

  /**
   * Concatenates this (most-significant) with [other] (least-significant).
   *
   * Corresponds to P4's `++` operator: bit<m> ++ bit<n> produces bit<m+n>.
   */
  fun concat(other: BitVector): BitVector {
    val newWidth = width + other.width
    return if (newWidth <= LONG_WIDTH) {
      BitVector(newWidth, (longValue shl other.width) or other.longValue, null)
    } else {
      BitVector((value shl other.width) or other.value, newWidth)
    }
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

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BitVector) return false
    if (width != other.width) return false
    return if (width <= LONG_WIDTH) longValue == other.longValue else value == other.value
  }

  override fun hashCode(): Int =
    if (width <= LONG_WIDTH) 31 * width + longValue.hashCode() else 31 * width + value.hashCode()

  // -------------------------------------------------------------------------
  // Internal helpers
  // -------------------------------------------------------------------------

  /** Constructs a narrow BitVector from already-masked Long value. No validation. */
  private fun narrow(v: Long): BitVector = BitVector(width, v and longMaskFor(width), null)

  /** Constructs a wide BitVector. No validation (caller must ensure value fits). */
  private fun wide(v: BigInteger): BitVector = BitVector(width, 0L, v)

  private fun requireSameWidth(other: BitVector) {
    require(width == other.width) { "width mismatch: $width vs ${other.width}" }
  }

  companion object {
    const val BITS_PER_BYTE = 8
    // 63 not 64: Java Long is signed, so we reserve bit 63 to keep unsigned comparisons simple.
    const val LONG_WIDTH = 63

    /** Cache of `2^width - 1` BigIntegers keyed by width. Used by wide-path arithmetic. */
    private val maskByWidth: java.util.concurrent.ConcurrentHashMap<Int, BigInteger> =
      java.util.concurrent.ConcurrentHashMap()

    /** Returns `2^width - 1` as BigInteger, cached. */
    fun maskFor(width: Int): BigInteger =
      maskByWidth.computeIfAbsent(width) { BigInteger.TWO.pow(it).minus(BigInteger.ONE) }

    /** Long masks by width, precomputed for widths 0..63. */
    private val longMasks = LongArray(LONG_WIDTH + 1) { w -> if (w == 0) 0L else (1L shl w) - 1 }

    /** Returns `2^width - 1` as Long. Width must be in 0..LONG_WIDTH. */
    fun longMaskFor(width: Int): Long = longMasks[width]

    fun ofInt(value: Int, width: Int): BitVector = ofLong(value.toLong(), width)

    /**
     * Constructs from a non-negative Long — no BigInteger allocation. Width must be ≤ LONG_WIDTH.
     */
    fun ofLong(value: Long, width: Int): BitVector {
      require(value >= 0) { "value must be non-negative, got $value" }
      require(width in 0..LONG_WIDTH) { "ofLong requires width in 0..$LONG_WIDTH, got $width" }
      require(width == 0 || value ushr width == 0L) { "value $value does not fit in $width bits" }
      return BitVector(width, value, null)
    }

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
 * 2^(width-1)). Used for `int<N>` expressions in the interpreter. No Long fast path here — signed
 * integers are rare on the P4 hot path compared to unsigned `bit<N>`.
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
