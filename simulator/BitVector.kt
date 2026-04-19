package fourward.simulator

import java.math.BigInteger

/**
 * A bit-precise unsigned integer of fixed width, corresponding to P4's `bit<N>` type.
 *
 * For widths ≤ 63 (the vast majority of P4 fields), the value is stored as a primitive [Long] — no
 * [BigInteger] allocation on construction or arithmetic. The [value] property lazily reconstructs a
 * [BigInteger] only when callers need it (byte serialization, wide-operand interop).
 *
 * All arithmetic is truncated to [width] bits, matching the P4 spec's overflow semantics.
 */
class BitVector
private constructor(
  /** Bit width of this value. */
  val width: Int,
  /** The value as a non-negative Long. Meaningful for all widths ≤ [LONG_WIDTH]; zero for wider. */
  val longValue: Long,
  /** BigInteger representation. Stored eagerly for wide values; lazily reconstructed for narrow. */
  @Volatile private var bigVal: BigInteger?,
) {
  /** The value as a non-negative [BigInteger]. Lazy for narrow widths. */
  val value: BigInteger
    get() = bigVal ?: BigInteger.valueOf(longValue).also { bigVal = it }

  /** Public constructor from [BigInteger]. Validates, then stores as Long when narrow. */
  constructor(
    value: BigInteger,
    width: Int,
  ) : this(
    width = width,
    longValue = if (width <= LONG_WIDTH) value.toLong() else 0L,
    bigVal = if (width > LONG_WIDTH) value else null,
  ) {
    require(width >= 0) { "width must be non-negative, got $width" }
    require(value.signum() >= 0) { "value must be non-negative, got $value" }
    // Equivalent to `value < 2^width` without allocating a BigInteger from `TWO.pow(width)`.
    require(value.bitLength() <= width) { "value $value does not fit in $width bits" }
  }

  // Arithmetic — each operator is one line; dispatch lives in the helpers.

  operator fun plus(other: BitVector) = binaryOp(other, Long::plus, BigInteger::plus)

  operator fun minus(other: BitVector) = binaryOp(other, Long::minus, BigInteger::minus)

  operator fun times(other: BitVector) = binaryOp(other, Long::times, BigInteger::times)

  operator fun div(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (isNarrow) narrow(longValue / other.longValue)
    else wide((value / other.value) and maskFor(width))
  }

  operator fun rem(other: BitVector): BitVector {
    requireSameWidth(other)
    return if (isNarrow) narrow(longValue % other.longValue)
    else wide((value % other.value) and maskFor(width))
  }

  /** Saturating addition: clamps to 2^width - 1 on overflow. */
  fun addSat(other: BitVector): BitVector {
    requireSameWidth(other)
    if (isNarrow) {
      val r = longValue + other.longValue
      val max = longMaskFor(width)
      return narrow(if (r < 0 || r > max) max else r)
    }
    val r = value + other.value
    val max = maskFor(width)
    return if (r > max) wide(max) else wide(r)
  }

  /** Saturating subtraction: clamps to 0 on underflow. */
  fun subSat(other: BitVector): BitVector {
    requireSameWidth(other)
    if (isNarrow) return narrow(maxOf(0L, longValue - other.longValue))
    val r = value - other.value
    return if (r < BigInteger.ZERO) wide(BigInteger.ZERO) else wide(r)
  }

  // Bitwise — operands must have the same width.

  infix fun and(other: BitVector) = bitwiseOp(other, Long::and, BigInteger::and)

  infix fun or(other: BitVector) = bitwiseOp(other, Long::or, BigInteger::or)

  infix fun xor(other: BitVector) = bitwiseOp(other, Long::xor, BigInteger::xor)

  fun inv(): BitVector =
    if (isNarrow) narrow(longValue.inv() and longMaskFor(width))
    else wide(value.not() and maskFor(width))

  // Shifts

  fun shl(amount: Int): BitVector =
    if (isNarrow) narrow((longValue shl amount) and longMaskFor(width))
    else wide((value shl amount) and maskFor(width))

  // Logical shift — ushr for Long (unsigned), shr for BigInteger (non-negative, so equivalent).
  fun shr(amount: Int): BitVector =
    if (isNarrow) narrow(longValue ushr amount) else wide(value shr amount)

  // Comparisons — operands must have the same width.

  operator fun compareTo(other: BitVector): Int {
    requireSameWidth(other)
    return if (isNarrow) longValue.compareTo(other.longValue) else value.compareTo(other.value)
  }

  /** Extracts bits [hi:lo] (inclusive, P4 slice notation). */
  fun slice(hi: Int, lo: Int): BitVector {
    require(hi >= lo) { "hi ($hi) must be >= lo ($lo)" }
    require(hi < width) { "hi ($hi) out of range for width $width" }
    val w = hi - lo + 1
    return if (isNarrow && w <= LONG_WIDTH) narrowAt(w, (longValue ushr lo) and longMaskFor(w))
    else BitVector((value shr lo) and maskFor(w), w)
  }

  /** Concatenates this (most-significant) with [other] (least-significant). */
  fun concat(other: BitVector): BitVector {
    val w = width + other.width
    return if (w <= LONG_WIDTH) narrowAt(w, (longValue shl other.width) or other.longValue)
    else BitVector((value shl other.width) or other.value, w)
  }

  /** Converts to a signed [SignedBitVector] of the same width (reinterprets bits). */
  fun toSigned(): SignedBitVector = SignedBitVector.fromUnsignedBits(value, width)

  /** Returns the value as a big-endian byte array, padded to ceil(width/8) bytes. */
  fun toByteArray(): ByteArray {
    val byteLen = (width + BITS_PER_BYTE - 1) / BITS_PER_BYTE
    // BigInteger.toByteArray() may include a leading 0x00 sign byte, so raw can be longer than
    // byteLen. The srcIdx < 0 guard handles that by zero-padding the high bytes.
    val raw = value.toByteArray()
    return ByteArray(byteLen) { i ->
      val srcIdx = raw.size - byteLen + i
      if (srcIdx < 0) 0 else raw[srcIdx]
    }
  }

  override fun toString(): String = "0x${value.toString(16)} : bit<$width>"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BitVector || width != other.width) return false
    return if (isNarrow) longValue == other.longValue else value == other.value
  }

  override fun hashCode(): Int =
    if (isNarrow) 31 * width + longValue.hashCode() else 31 * width + value.hashCode()

  private val isNarrow: Boolean
    get() = width <= LONG_WIDTH

  /** Narrow result at this width, truncated to width bits. */
  private fun narrow(v: Long) = BitVector(width, v and longMaskFor(width), null)

  /** Narrow result at a different width — for slice/concat where the result width differs. */
  private fun narrowAt(w: Int, v: Long) = BitVector(w, v, null)

  /** Wide result at this width. */
  private fun wide(v: BigInteger) = BitVector(width, 0L, v)

  /**
   * Binary arithmetic op with truncation. For +/−/× the Long result may overflow or go negative;
   * masking handles both. Division and modulo have different overflow semantics and use their own
   * implementations above.
   */
  private inline fun binaryOp(
    other: BitVector,
    longOp: (Long, Long) -> Long,
    bigOp: (BigInteger, BigInteger) -> BigInteger,
  ): BitVector {
    requireSameWidth(other)
    return if (isNarrow) narrow(longOp(longValue, other.longValue))
    else wide(bigOp(value, other.value) and maskFor(width))
  }

  /** Bitwise op — no masking needed (inputs are in range and bitwise ops preserve width). */
  private inline fun bitwiseOp(
    other: BitVector,
    longOp: (Long, Long) -> Long,
    bigOp: (BigInteger, BigInteger) -> BigInteger,
  ): BitVector {
    requireSameWidth(other)
    return if (isNarrow) narrowAt(width, longOp(longValue, other.longValue))
    else wide(bigOp(value, other.value))
  }

  private fun requireSameWidth(other: BitVector) {
    require(width == other.width) { "width mismatch: $width vs ${other.width}" }
  }

  companion object {
    const val BITS_PER_BYTE = 8
    const val LONG_WIDTH = 63

    /** Cache of `2^width - 1` by width. */
    private val maskByWidth = java.util.concurrent.ConcurrentHashMap<Int, BigInteger>()

    /** Returns `2^width - 1` as BigInteger, cached. */
    fun maskFor(width: Int): BigInteger =
      maskByWidth.computeIfAbsent(width) { BigInteger.TWO.pow(it).minus(BigInteger.ONE) }

    /** Long masks by width, precomputed for widths 0..63. */
    private val longMasks = LongArray(LONG_WIDTH + 1) { w -> if (w == 0) 0L else (1L shl w) - 1 }

    /** Returns `2^width - 1` as Long. Width must be in 0..[LONG_WIDTH]. */
    fun longMaskFor(width: Int): Long = longMasks[width]

    fun ofInt(value: Int, width: Int): BitVector = ofLong(value.toLong(), width)

    /** Constructs from a non-negative Long. No BigInteger allocation. */
    fun ofLong(value: Long, width: Int): BitVector {
      require(value >= 0) { "value must be non-negative, got $value" }
      require(width in 0..LONG_WIDTH) { "ofLong requires width in 0..$LONG_WIDTH, got $width" }
      require(width == 0 || value ushr width == 0L) { "value $value does not fit in $width bits" }
      return BitVector(width, value, null)
    }

    /** Decodes a big-endian byte array into a bit<N> value. */
    fun ofBytes(bytes: ByteArray, width: Int): BitVector {
      val value = BigInteger(1, bytes)
      return BitVector(value, width)
    }
  }
}

/**
 * A bit-precise signed integer of fixed width, corresponding to P4's `int<N>` type.
 *
 * Stored internally as a two's-complement signed BigInteger in the range [-2^(width-1),
 * 2^(width-1)). No Long fast path — signed integers are rare on the P4 hot path.
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
