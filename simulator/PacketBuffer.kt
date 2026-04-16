package fourward.simulator

import java.math.BigInteger

/**
 * A fixed-size byte buffer with bit-level read/write access.
 *
 * This is the storage layer for the flat packet-state representation: all P4 header and struct
 * fields for a single packet live as bit ranges in one of these buffers, at offsets determined by
 * the pipeline's static layout (see [HeaderLayout], [StructLayout]).
 *
 * The buffer uses big-endian bit ordering: bit 0 is the most significant bit of byte 0. This
 * matches the wire-order convention used by P4's `extract` / `emit` operations and by the existing
 * `BitVector` / `SignedBitVector` representations. A `bit<N>` field at offset `o` with value `v`
 * occupies bits `o..o+N-1`, stored MSB-first.
 *
 * See `designs/flat_packet_buffer.md`.
 */
class PacketBuffer internal constructor(internal val bytes: ByteArray) {

  /** Creates a zero-filled buffer of the given bit size (rounded up to byte alignment). */
  constructor(bitSize: Int) : this(ByteArray((bitSize + 7) / 8))

  /** Total size of the buffer in bits. */
  val bitSize: Int
    get() = bytes.size * 8

  /**
   * Reads [width] bits starting at bit [offset] (big-endian). Returns the value as an unsigned
   * integer in the low [width] bits of the result.
   */
  fun readBits(offset: Int, width: Int): Long {
    checkRange(offset, width)
    var result = 0L
    var bitsRemaining = width
    var bitIndex = offset
    while (bitsRemaining > 0) {
      val byteIndex = bitIndex ushr 3
      val bitInByte = bitIndex and 7
      val bitsAvailableInByte = 8 - bitInByte
      val bitsToRead = minOf(bitsAvailableInByte, bitsRemaining)
      val byteValue = bytes[byteIndex].toInt() and 0xFF
      // The slice we want is bits [bitInByte .. bitInByte + bitsToRead - 1] of the byte,
      // with the high bit of that slice being the MSB.
      val shift = bitsAvailableInByte - bitsToRead
      val mask = (1 shl bitsToRead) - 1
      val slice = (byteValue ushr shift) and mask
      result = (result shl bitsToRead) or slice.toLong()
      bitIndex += bitsToRead
      bitsRemaining -= bitsToRead
    }
    return result
  }

  /**
   * Writes the low [width] bits of [value] to bit [offset] (big-endian). Upper bits of [value] are
   * ignored — `writeBits(o, 4, 0xFF)` writes `0xF`.
   */
  fun writeBits(offset: Int, width: Int, value: Long) {
    checkRange(offset, width)
    // Mask `value` to the low `width` bits to avoid trampling neighbours.
    val masked = if (width == Long.SIZE_BITS) value else value and ((1L shl width) - 1)
    var bitsRemaining = width
    var bitIndex = offset
    while (bitsRemaining > 0) {
      val byteIndex = bitIndex ushr 3
      val bitInByte = bitIndex and 7
      val bitsAvailableInByte = 8 - bitInByte
      val bitsToWrite = minOf(bitsAvailableInByte, bitsRemaining)
      val shift = bitsAvailableInByte - bitsToWrite
      // Slice of `masked` corresponding to this byte's portion of the field.
      val slice =
        ((masked ushr (bitsRemaining - bitsToWrite)) and ((1L shl bitsToWrite) - 1)).toInt()
      val mask = ((1 shl bitsToWrite) - 1) shl shift
      val old = bytes[byteIndex].toInt() and 0xFF
      bytes[byteIndex] = ((old and mask.inv()) or (slice shl shift)).toByte()
      bitIndex += bitsToWrite
      bitsRemaining -= bitsToWrite
    }
  }

  /** Zeros [width] bits starting at bit [offset]. */
  fun zeroRange(offset: Int, width: Int) {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    require(width >= 0) { "width must be non-negative, got $width" }
    require(offset + width <= bitSize) { "zeroRange $offset..${offset + width} exceeds $bitSize" }
    if (width == 0) return
    // Do it in chunks of up to Long.SIZE_BITS to reuse writeBits's correctness.
    var remaining = width
    var bitIndex = offset
    while (remaining > 0) {
      val chunk = minOf(Long.SIZE_BITS, remaining)
      writeBits(bitIndex, chunk, 0L)
      bitIndex += chunk
      remaining -= chunk
    }
  }

  /**
   * Reads [width] bits starting at bit [offset] into a non-negative [BigInteger]. Used for fields
   * wider than 64 bits (e.g. `bit<128>` IPv6 addresses). For widths ≤ 64 bits, [readBits] is
   * faster.
   */
  fun readBigInt(offset: Int, width: Int): BigInteger {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    require(width >= 0) { "width must be non-negative, got $width" }
    require(offset + width <= bitSize) {
      "field at $offset..${offset + width} exceeds buffer size $bitSize"
    }
    if (width == 0) return BigInteger.ZERO
    var result = BigInteger.ZERO
    var bitIndex = offset
    var remaining = width
    while (remaining > 0) {
      val chunk = minOf(Long.SIZE_BITS, remaining)
      val value = readBits(bitIndex, chunk)
      val unsigned =
        if (value >= 0L) BigInteger.valueOf(value)
        else BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(Long.SIZE_BITS))
      result = result.shiftLeft(chunk).or(unsigned)
      bitIndex += chunk
      remaining -= chunk
    }
    return result
  }

  /**
   * Writes the low [width] bits of [value] to bit [offset] in big-endian order (most significant
   * chunk first). Used for fields wider than 64 bits; for widths ≤ 64, [writeBits] is faster.
   */
  fun writeBigInt(offset: Int, width: Int, value: BigInteger) {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    require(width >= 0) { "width must be non-negative, got $width" }
    require(offset + width <= bitSize) {
      "field at $offset..${offset + width} exceeds buffer size $bitSize"
    }
    if (width == 0) return
    val mask = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE)
    val masked = value.and(mask)
    var bitIndex = offset
    var remaining = width
    while (remaining > 0) {
      val chunk = minOf(Long.SIZE_BITS, remaining)
      val chunkMask = BigInteger.ONE.shiftLeft(chunk).subtract(BigInteger.ONE)
      // Extract the `chunk` most-significant remaining bits (big-endian chunk order).
      val chunkBits = masked.shiftRight(remaining - chunk).and(chunkMask).toLong()
      writeBits(bitIndex, chunk, chunkBits)
      bitIndex += chunk
      remaining -= chunk
    }
  }

  /** Returns an independent copy. Mutations to the copy do not affect the original. */
  fun copyOf(): PacketBuffer = PacketBuffer(bytes.copyOf())

  /** Returns a defensive copy of the underlying bytes. */
  fun toBytes(): ByteArray = bytes.copyOf()

  private fun checkRange(offset: Int, width: Int) {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    require(width in 1..Long.SIZE_BITS) { "width must be in 1..${Long.SIZE_BITS}, got $width" }
    require(offset + width <= bitSize) {
      "field at $offset..${offset + width} exceeds buffer size $bitSize"
    }
  }

  companion object {
    /** Constructs a buffer from the given byte array (defensively copied). */
    fun fromBytes(bytes: ByteArray): PacketBuffer = PacketBuffer(bytes.copyOf())
  }
}
