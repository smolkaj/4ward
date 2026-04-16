package fourward.simulator

import java.math.BigInteger

private val TWO_TO_64: BigInteger = BigInteger.ONE.shiftLeft(Long.SIZE_BITS)

/**
 * A `MutableMap<String, Value>` whose entries live as bit ranges in a [PacketBuffer].
 *
 * Acts as a drop-in replacement for the `HashMap<String, Value>` that backs `HeaderVal.fields` /
 * `StructVal.fields`. Reads produce [BitVal] / [IntVal] / [BoolVal] / [ErrorVal] wrappers from the
 * underlying bit pattern; writes unwrap those into bits and write to the buffer.
 *
 * **Caching:** each slot's returned [Value] is cached. Subsequent reads of the same slot reuse the
 * cached object (zero-allocation), mirroring the legacy `HashMap.get` behaviour. A write
 * invalidates the cache entry by overwriting it.
 *
 * Primitive members only. Nested header/struct/stack members aren't addressable through this map —
 * callers use the view's dedicated `header(name)` / `struct(name)` / `stack(name)` accessors.
 *
 * See `designs/flat_packet_buffer.md`.
 */
class BufferBackedFieldMap(
  val buffer: PacketBuffer,
  val slots: Map<String, FieldSlot>,
  val base: Int,
) : AbstractMutableMap<String, Value>() {

  /**
   * Cache of last-returned values keyed by field name. Invalidated on write. Allocated lazily on
   * first insertion so a just-rewired map that is only read from once doesn't pay the HashMap
   * allocation cost.
   */
  private var cache: HashMap<String, Value>? = null

  private fun cacheOrCreate(): HashMap<String, Value> =
    cache ?: HashMap<String, Value>(slots.size).also { cache = it }

  /** Returns an independent copy: new buffer (copied), shared slots, fresh cache. */
  fun copy(): BufferBackedFieldMap = BufferBackedFieldMap(buffer.copyOf(), slots, base)

  /**
   * Returns a new map pointing at [newBuffer] (shared, not copied) with the same slots/base. The
   * new map inherits a *copy* of this map's value cache — safe because [newBuffer] carries
   * identical bits to this map's [buffer] at the relevant offsets (the caller copied the buffer
   * byte-for-byte), and subsequent writes through the new map invalidate per-slot cache entries.
   * Carrying the cache forward avoids re-allocating `BitVal` / `IntVal` / `BoolVal` wrappers for
   * fields that were already cached pre-fork — the primary allocation cost on fork-heavy workloads.
   */
  fun withBuffer(newBuffer: PacketBuffer): BufferBackedFieldMap {
    val copy = BufferBackedFieldMap(newBuffer, slots, base)
    cache?.let { copy.cache = HashMap(it) }
    return copy
  }

  override val size: Int
    get() = slots.size

  override fun containsKey(key: String): Boolean = key in slots

  override fun get(key: String): Value? {
    val slot = slots[key] ?: return null
    cache?.get(key)?.let {
      return it
    }
    val value =
      if (slot.width <= Long.SIZE_BITS) {
        val bits = buffer.readBits(base + slot.offset, slot.width)
        when (slot.kind) {
          PrimitiveKind.BIT -> BitVal(BitVector(unsignedBigInt(bits), slot.width))
          PrimitiveKind.INT ->
            IntVal(SignedBitVector.fromUnsignedBits(unsignedBigInt(bits), slot.width))
          PrimitiveKind.BOOL -> BoolVal(bits != 0L)
          PrimitiveKind.ERROR -> ErrorVal(ErrorCodes.decode(bits))
        }
      } else {
        val bits = buffer.readBigInt(base + slot.offset, slot.width)
        when (slot.kind) {
          PrimitiveKind.BIT -> BitVal(BitVector(bits, slot.width))
          PrimitiveKind.INT -> IntVal(SignedBitVector.fromUnsignedBits(bits, slot.width))
          else -> error("kind ${slot.kind} not supported at width ${slot.width}")
        }
      }
    cacheOrCreate()[key] = value
    return value
  }

  /**
   * Reinterprets [bits] (a [Long] that may be negative when the high bit is set, for 64-bit fields)
   * as a non-negative [BigInteger]. For widths < 64, [bits] is already non-negative.
   */
  private fun unsignedBigInt(bits: Long): BigInteger =
    if (bits >= 0L) BigInteger.valueOf(bits) else BigInteger.valueOf(bits).add(TWO_TO_64)

  override fun put(key: String, value: Value): Value? {
    val slot =
      slots[key]
        ?: throw IllegalArgumentException("no such field: '$key' (available: ${slots.keys})")
    val old = get(key)
    if (slot.width <= Long.SIZE_BITS) {
      buffer.writeBits(base + slot.offset, slot.width, encode(value, slot, key))
    } else {
      buffer.writeBigInt(base + slot.offset, slot.width, encodeBig(value, slot, key))
    }
    // Cache the written value so subsequent reads don't have to rematerialise.
    cacheOrCreate()[key] = value
    return old
  }

  /**
   * For a fixed-schema P4 field map, [clear] zeros each field's bit range in place — removing keys
   * isn't meaningful for a layout with declared fields. Matches `HeaderVal.setValid`'s `clear();
   * putAll(newFields)` pattern.
   */
  override fun clear() {
    for (slot in slots.values) {
      buffer.zeroRange(base + slot.offset, slot.width)
    }
    cache?.clear()
  }

  private fun encodeBig(value: Value, slot: FieldSlot, key: String): java.math.BigInteger =
    when (slot.kind) {
      PrimitiveKind.BIT -> {
        require(value is BitVal) {
          "$key expects a BitVal (bit<${slot.width}>), got ${value.javaClass.simpleName}"
        }
        require(value.bits.width == slot.width) {
          "$key expects bit<${slot.width}>, got bit<${value.bits.width}>"
        }
        value.bits.value
      }
      PrimitiveKind.INT -> {
        require(value is IntVal) {
          "$key expects an IntVal (int<${slot.width}>), got ${value.javaClass.simpleName}"
        }
        require(value.bits.width == slot.width) {
          "$key expects int<${slot.width}>, got int<${value.bits.width}>"
        }
        value.bits.toUnsigned().value
      }
      else -> error("kind ${slot.kind} not supported at width ${slot.width}")
    }

  private fun encode(value: Value, slot: FieldSlot, key: String): Long =
    when (slot.kind) {
      PrimitiveKind.BIT -> {
        require(value is BitVal) {
          "$key expects a BitVal (bit<${slot.width}>), got ${value.javaClass.simpleName}"
        }
        require(value.bits.width == slot.width) {
          "$key expects bit<${slot.width}>, got bit<${value.bits.width}>"
        }
        value.bits.value.toLong()
      }
      PrimitiveKind.INT -> {
        require(value is IntVal) {
          "$key expects an IntVal (int<${slot.width}>), got ${value.javaClass.simpleName}"
        }
        require(value.bits.width == slot.width) {
          "$key expects int<${slot.width}>, got int<${value.bits.width}>"
        }
        value.bits.toUnsigned().value.toLong()
      }
      PrimitiveKind.BOOL -> {
        require(value is BoolVal) { "$key expects a BoolVal, got ${value.javaClass.simpleName}" }
        if (value.value) 1L else 0L
      }
      PrimitiveKind.ERROR -> {
        require(value is ErrorVal) { "$key expects an ErrorVal, got ${value.javaClass.simpleName}" }
        ErrorCodes.encode(value.member)
      }
    }

  override val entries: MutableSet<MutableMap.MutableEntry<String, Value>>
    get() =
      object : AbstractMutableSet<MutableMap.MutableEntry<String, Value>>() {
        override val size: Int
          get() = slots.size

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Value>> {
          val keyIter = slots.keys.iterator()
          return object : MutableIterator<MutableMap.MutableEntry<String, Value>> {
            override fun hasNext(): Boolean = keyIter.hasNext()

            override fun next(): MutableMap.MutableEntry<String, Value> =
              BufferEntry(keyIter.next())

            override fun remove() {
              throw UnsupportedOperationException("cannot remove keys from a buffer-backed map")
            }
          }
        }

        override fun add(element: MutableMap.MutableEntry<String, Value>): Boolean {
          put(element.key, element.value)
          return true
        }
      }

  private inner class BufferEntry(override val key: String) :
    MutableMap.MutableEntry<String, Value> {
    override val value: Value
      get() = get(key)!!

    override fun setValue(newValue: Value): Value {
      val old = value
      put(key, newValue)
      return old
    }

    override fun toString(): String = "$key=$value"

    override fun equals(other: Any?): Boolean =
      other is Map.Entry<*, *> && other.key == key && other.value == value

    override fun hashCode(): Int = key.hashCode() xor value.hashCode()
  }
}
