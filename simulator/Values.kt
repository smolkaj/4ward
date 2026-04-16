package fourward.simulator

import java.util.concurrent.atomic.LongAdder

/**
 * Runtime value types for the 4ward simulator.
 *
 * Every P4 runtime value is one of these. The sealed hierarchy maps to P4's type system: bit<N> ->
 * BitVal, int<N> -> IntVal, bool -> BoolVal, header fields -> aggregated in HeaderVal, etc.
 *
 * The simulator's interpreter produces and consumes these values; they never escape to the trace
 * (traces use byte-encoded representations for portability).
 */
sealed class Value {
  /** Returns an independent deep copy. Immutable leaf types return `this`. */
  open fun deepCopy(): Value = this

  /**
   * Returns a copy-on-write wrapper if this value is mutable, or `null` if immutable. COW copies
   * share the underlying field map with the original — writes go to a per-instance overlay, reads
   * of mutable children are recursively COW-wrapped on first access.
   */
  internal open fun cowCopy(): Value? = null
}

/**
 * Diagnostic counters for deep-copy field access patterns. Measures what fraction of deep-copied
 * HeaderVal/StructVal fields are actually read or written by fork branches, to evaluate the ceiling
 * for a copy-on-write optimization. See `designs/parallel_packet_scaling.md`.
 *
 * Enabled by `-Dfourward.simulator.trackFieldReads=true`. When disabled (default), deep copies use
 * plain HashMaps with zero overhead.
 */
object DeepCopyFieldStats {
  val totalFieldsCopied = LongAdder()
  val rawFieldReads = LongAdder()
  val rawFieldWrites = LongAdder()

  fun reset() {
    totalFieldsCopied.reset()
    rawFieldReads.reset()
    rawFieldWrites.reset()
  }

  fun report(): String {
    val copied = totalFieldsCopied.sum()
    if (copied == 0L) return "DeepCopyFieldStats: no deep copies tracked."
    val reads = rawFieldReads.sum()
    val writes = rawFieldWrites.sum()
    return buildString {
      appendLine("=== Deep Copy Field Stats ===")
      appendLine("Total fields copied: $copied")
      appendLine(
        "Raw field reads on copies: $reads (%.2f per copied field)"
          .format(reads.toDouble() / copied)
      )
      appendLine(
        "Raw field writes on copies: $writes (%.2f per copied field)"
          .format(writes.toDouble() / copied)
      )
      val neverAccessed = copied - minOf(reads + writes, copied)
      appendLine(
        "Lower bound on fields never accessed: $neverAccessed (%.1f%%)"
          .format(100.0 * neverAccessed / copied)
      )
    }
  }
}

/**
 * A [MutableMap] wrapper that counts [get] and [put] calls, funneling into [DeepCopyFieldStats].
 * Used only on deep-copied instances when tracking is enabled.
 */
internal class TrackingMutableMap(private val delegate: MutableMap<String, Value>) :
  MutableMap<String, Value> by delegate {
  override fun get(key: String): Value? {
    DeepCopyFieldStats.rawFieldReads.add(1)
    return delegate[key]
  }

  override fun put(key: String, value: Value): Value? {
    DeepCopyFieldStats.rawFieldWrites.add(1)
    return delegate.put(key, value)
  }

  override fun putAll(from: Map<out String, Value>) {
    DeepCopyFieldStats.rawFieldWrites.add(from.size.toLong())
    delegate.putAll(from)
  }

  override fun replaceAll(function: java.util.function.BiFunction<in String, in Value, out Value>) {
    DeepCopyFieldStats.rawFieldWrites.add(delegate.size.toLong())
    delegate.replaceAll(function)
  }
}

/** Wraps [fields] in a [TrackingMutableMap] if tracking is enabled; returns [fields] otherwise. */
internal fun trackIfEnabled(fields: MutableMap<String, Value>): MutableMap<String, Value> {
  if (System.getProperty("fourward.simulator.trackFieldReads") != "true") return fields
  DeepCopyFieldStats.totalFieldsCopied.add(fields.size.toLong())
  return TrackingMutableMap(fields)
}

/**
 * A copy-on-write field map for [HeaderVal] and [StructVal] deep copies.
 *
 * Wraps a shared (read-only) parent map. Reads of immutable values return the parent's reference
 * directly. Reads of mutable values (HeaderVal, StructVal) lazily create a COW-wrapped child and
 * cache it in a per-instance overlay. Writes always go to the overlay. The parent map is never
 * mutated.
 *
 * This avoids the O(N) eager deep-copy of all fields at fork time. Only fields that are actually
 * written by a fork branch pay for their own copy. Fields that are only read share the parent's
 * data. Fields that are never accessed cost nothing. See `designs/parallel_packet_scaling.md`.
 */
internal class CopyOnWriteFieldMap(private val shared: Map<String, Value>) :
  AbstractMutableMap<String, Value>() {

  init {
    if (System.getProperty("fourward.simulator.trackFieldReads") == "true") {
      DeepCopyFieldStats.totalFieldsCopied.add(shared.size.toLong())
    }
  }

  /** Overlay of values that differ from [shared]: writes and COW-wrapped mutable children. */
  private var overlay: MutableMap<String, Value>? = null

  /**
   * Set to true by [clear]. When true, [shared] is logically empty and only [overlay] matters. This
   * supports [HeaderVal.setValid] which calls `fields.clear(); fields.putAll(newFields)`.
   */
  private var cleared = false

  override val size: Int
    get() =
      if (cleared) {
        overlay?.size ?: 0
      } else {
        // P4 field maps don't add new keys, so shared.size is the base.
        shared.size
      }

  override fun get(key: String): Value? {
    if (System.getProperty("fourward.simulator.trackFieldReads") == "true") {
      DeepCopyFieldStats.rawFieldReads.add(1)
    }
    overlay?.get(key)?.let {
      return it
    }
    if (cleared) return null
    val v = shared[key] ?: return null
    // Immutable values are safe to share. Mutable values need COW wrapping
    // so that mutations to the child don't affect the parent.
    val cow = v.cowCopy() ?: return v
    val o = overlay ?: HashMap<String, Value>(4).also { overlay = it }
    o[key] = cow
    return cow
  }

  override fun put(key: String, value: Value): Value? {
    if (System.getProperty("fourward.simulator.trackFieldReads") == "true") {
      DeepCopyFieldStats.rawFieldWrites.add(1)
    }
    val old = get(key)
    val o = overlay ?: HashMap<String, Value>(4).also { overlay = it }
    o[key] = value
    return old
  }

  override fun clear() {
    overlay?.clear()
    cleared = true
  }

  override fun containsKey(key: String): Boolean {
    if (overlay?.containsKey(key) == true) return true
    return !cleared && shared.containsKey(key)
  }

  override fun isEmpty(): Boolean = size == 0

  /**
   * Materializes the merged view of shared + overlay. Each call creates a new snapshot — callers
   * that iterate should capture the result rather than calling repeatedly.
   */
  override val entries: MutableSet<MutableMap.MutableEntry<String, Value>>
    get() {
      val merged = LinkedHashMap<String, Value>(size)
      if (!cleared) {
        for ((k, v) in shared) {
          val overlayVal = overlay?.get(k)
          merged[k] = overlayVal ?: (v.cowCopy() ?: v)
        }
      }
      // Add overlay-only entries (if any — shouldn't happen for P4 fields, but be safe).
      overlay?.forEach { (k, v) -> if (k !in merged) merged[k] = v }
      return merged.entries
    }
}

/** A bit<N> value. */
data class BitVal(val bits: BitVector) : Value() {
  constructor(value: Long, width: Int) : this(BitVector.ofLong(value, width))

  constructor(value: Int, width: Int) : this(BitVector.ofInt(value, width))
}

/**
 * A compile-time integer with infinite precision (P4 spec §8.1).
 *
 * InfInt values arise from untyped integer literals in the IR (e.g. shift amounts, constants). They
 * adopt the width of the other operand when used in a binary operation.
 */
data class InfIntVal(val value: java.math.BigInteger) : Value() {
  /** Coerce to a fixed-width unsigned [BitVal]. */
  fun toBitVal(width: Int): BitVal =
    BitVal(BitVector(value.mod(java.math.BigInteger.TWO.pow(width)), width))

  /** Coerce to a fixed-width signed [IntVal]. */
  fun toIntVal(width: Int): IntVal =
    IntVal(SignedBitVector.fromUnsignedBits(value.mod(java.math.BigInteger.TWO.pow(width)), width))
}

/** An int<N> value (two's complement). */
data class IntVal(val bits: SignedBitVector) : Value()

/** A bool value. */
data class BoolVal(val value: Boolean) : Value() {
  companion object {
    val TRUE = BoolVal(true)
    val FALSE = BoolVal(false)
  }
}

/** A P4 error value (one of the named error members, e.g. "NoError"). */
data class ErrorVal(val member: String) : Value()

/** A plain (non-serializable) P4 enum value, e.g. HashAlgorithm.crc16. */
data class EnumVal(val member: String) : Value()

/** A string value, used by log_msg format strings. */
data class StringVal(val value: String) : Value()

/**
 * A header instance.
 *
 * [valid] reflects the P4 validity bit. Fields are stored by name. Extracting a header sets valid =
 * true; calling setInvalid() sets valid = false and zeros all fields.
 */
data class HeaderVal(
  val typeName: String,
  val fields: MutableMap<String, Value> = mutableMapOf(),
  var valid: Boolean = false,
) : Value() {

  /** Sets the header valid and stores new field values. */
  fun setValid(newFields: Map<String, Value>) {
    valid = true
    fields.clear()
    fields.putAll(newFields)
  }

  /**
   * Sets the header invalid and zeros all fields, as required by the P4 spec (section 8.17:
   * setInvalid resets all fields to their default values).
   */
  fun setInvalid() {
    valid = false
    // Zero all fields (P4 spec §8.17). BMv2 treats fields of an invalid
    // header as zero, so we reset them in place rather than clearing.
    fields.replaceAll { _, v ->
      when (v) {
        is BitVal -> BitVal(0L, v.bits.width)
        is BoolVal -> BoolVal(false)
        else -> v
      }
    }
  }

  fun copy(): HeaderVal = HeaderVal(typeName, fields.toMutableMap(), valid)

  override fun deepCopy(): HeaderVal = cowCopy()

  internal override fun cowCopy(): HeaderVal =
    HeaderVal(typeName, CopyOnWriteFieldMap(fields), valid)
}

/**
 * A struct instance (non-header). Structs have no validity bit. Used for P4's `struct` type,
 * including standard_metadata_t.
 */
data class StructVal(val typeName: String, val fields: MutableMap<String, Value> = mutableMapOf()) :
  Value() {
  fun copy(): StructVal = StructVal(typeName, fields.toMutableMap())

  override fun deepCopy(): StructVal = cowCopy()

  internal override fun cowCopy(): StructVal = StructVal(typeName, CopyOnWriteFieldMap(fields))

  /** P4 spec §8.20: a header union is valid if any member header is valid. */
  fun isUnionValid(): Boolean = fields.values.any { it is HeaderVal && it.valid }

  /** P4 spec §8.20: invalidating a header union invalidates all member headers. */
  fun invalidateUnion() {
    fields.values.forEach { if (it is HeaderVal) it.setInvalid() }
  }

  /** P4 spec §8.20: setting a union member valid invalidates all other members. */
  fun invalidateUnionExcept(keep: HeaderVal) {
    fields.values.forEach { if (it is HeaderVal && it !== keep) it.setInvalid() }
  }

  /** Overwrites a bit-valued field, preserving its IR-defined width. */
  fun setBitField(name: String, value: Long) {
    val existing = checkNotNull(fields[name] as? BitVal) { "$typeName.$name is not a bit field" }
    fields[name] = BitVal(value, existing.bits.width)
  }

  /** Returns the bit width of a bit-valued field, as defined by the IR. */
  fun bitWidth(name: String): Int {
    val field = checkNotNull(fields[name] as? BitVal) { "$typeName.$name is not a bit field" }
    return field.bits.width
  }
}

/**
 * A header stack (fixed-size array of headers with a next/last pointer).
 *
 * Elements are typically [HeaderVal] but may be [StructVal] for header-union stacks (P4 spec
 * §8.18).
 */
data class HeaderStackVal(
  val elementTypeName: String,
  val headers: MutableList<Value>,
  var nextIndex: Int = 0,
) : Value() {
  val size: Int
    get() = headers.size

  override fun deepCopy(): HeaderStackVal =
    HeaderStackVal(elementTypeName, headers.mapTo(mutableListOf()) { it.deepCopy() }, nextIndex)
}

/** Sentinel for void returns and uninitialised variables. */
object UnitVal : Value()

/**
 * Views a [HeaderVal] or [StructVal] as a [StructVal] for read-only field-based operations
 * (hashing, checksums). Headers have the same `fields` map but carry an extra validity bit that
 * these operations don't need.
 *
 * The returned [StructVal] shares the original [HeaderVal]'s mutable field map — callers must not
 * mutate the returned struct's fields.
 */
fun Value.asStructVal(): StructVal =
  when (this) {
    is StructVal -> this
    is HeaderVal -> StructVal(typeName, fields)
    else -> error("expected struct or header, got ${this::class.simpleName}")
  }
