package fourward.simulator

/**
 * Location of a primitive field within a [HeaderLayout] or [StructLayout]: bit offset from the
 * layout's base, bit width, and primitive kind (so readers can return the right [Value] subtype).
 */
data class FieldSlot(val offset: Int, val width: Int, val kind: PrimitiveKind = PrimitiveKind.BIT) {
  init {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    require(width >= 1) { "width must be positive, got $width" }
    if (kind == PrimitiveKind.BOOL) {
      require(width == 1) { "bool field width must be 1, got $width" }
    }
    if (kind == PrimitiveKind.ERROR) {
      require(width <= Long.SIZE_BITS) { "error field width must be ≤ 64, got $width" }
    }
  }
}

/**
 * The P4 primitive type kind a [FieldSlot] represents. Preserves enough of the source type so that
 * buffer reads can produce the correct [Value] subtype (`BitVal`, `IntVal`, `BoolVal`).
 *
 * Enums map to [BIT] (with their underlying width); errors are recorded with [ERROR] so readers can
 * look up the error name from the pipeline's error-code table.
 */
enum class PrimitiveKind {
  BIT,
  INT,
  BOOL,
  ERROR,
}

/**
 * Static layout of a P4 header type: where each field sits, how wide it is, and where the validity
 * bit lives. Computed once per header type at pipeline load, then immutable and shared across all
 * [HeaderView]s of that type.
 *
 * Field iteration order is the declaration order, preserved by passing a [LinkedHashMap] as
 * [fields]. The trace and serialization emit fields in declaration order.
 */
data class HeaderLayout(
  val typeName: String,
  val fields: Map<String, FieldSlot>,
  /** Bit offset within the layout where the validity bit lives. */
  val validBitOffset: Int,
) {
  /** Total width of this header in bits, including the validity bit. */
  val totalBits: Int =
    maxOf(validBitOffset + 1, fields.values.maxOfOrNull { it.offset + it.width } ?: 0)
}

/**
 * A view onto a [HeaderLayout]'s fields in a [PacketBuffer], anchored at a [base] bit offset.
 *
 * Multiple views can share a buffer at different bases (e.g., successive headers in a packet), or
 * share a layout at the same base after a fork copy. The view itself is cheap — it holds three
 * references and no mutable state.
 *
 * Extends [Value] so it can flow through the [Environment] alongside other runtime values once the
 * buffer-backed migration is complete. A view's [deepCopy] returns a view over a fresh buffer copy
 * — the optimal-design alternative to walking and copying an object tree.
 */
class HeaderView(val buffer: PacketBuffer, val layout: HeaderLayout, val base: Int = 0) : Value() {

  /** Returns a view over an independent copy of [buffer]. Used by the fork path. */
  override fun deepCopy(): HeaderView = HeaderView(buffer.copyOf(), layout, base)

  /** Whether the header is currently valid (the P4 validity bit). */
  var isValid: Boolean
    get() = buffer.readBits(base + layout.validBitOffset, 1) == 1L
    set(value) {
      if (!value) {
        // P4 spec section 8.17: setInvalid resets all fields to default values.
        // BMv2 treats fields of an invalid header as zero, so we zero the entire layout.
        buffer.zeroRange(base, layout.totalBits)
      }
      buffer.writeBits(base + layout.validBitOffset, 1, if (value) 1L else 0L)
    }

  /** Reads the named field. Throws if [field] is not declared in this header's layout. */
  operator fun get(field: String): BitVal {
    val slot = slotOf(field)
    val bits = buffer.readBits(base + slot.offset, slot.width)
    return BitVal(bits, slot.width)
  }

  /**
   * Writes the named field. Throws if [field] is not declared in this header's layout, or if
   * [value]'s width differs from the declared width.
   */
  operator fun set(field: String, value: BitVal) {
    val slot = slotOf(field)
    require(value.bits.width == slot.width) {
      "${layout.typeName}.$field expects bit<${slot.width}>, got bit<${value.bits.width}>"
    }
    buffer.writeBits(base + slot.offset, slot.width, value.bits.value.toLong())
  }

  private fun slotOf(field: String): FieldSlot =
    layout.fields[field]
      ?: throw IllegalArgumentException("${layout.typeName} has no field '$field'")
}
