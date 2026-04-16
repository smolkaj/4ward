package fourward.simulator

/**
 * A member of a [StructLayout]: either a primitive bit-field or a nested header/struct.
 *
 * Structs unlike headers can contain nested aggregate types (another struct, or a header).
 * [PrimitiveField] reads through a single `readBits` call; [NestedHeader] and [NestedStruct]
 * produce sub-views at the field's offset, with their own layouts.
 */
sealed interface StructMember {
  val offset: Int
  val widthBits: Int
}

/** A primitive bit-field: `bit<N>`, `int<N>`, `bool`, or enum, at a given bit offset. */
data class PrimitiveField(
  override val offset: Int,
  val width: Int,
  val kind: PrimitiveKind = PrimitiveKind.BIT,
) : StructMember {
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

  override val widthBits: Int
    get() = width
}

/** A nested header member — a whole [HeaderLayout] embedded at [offset] inside a struct. */
data class NestedHeader(override val offset: Int, val layout: HeaderLayout) : StructMember {
  init {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
  }

  override val widthBits: Int
    get() = layout.totalBits
}

/** A nested struct member — a whole [StructLayout] embedded at [offset] inside a struct. */
data class NestedStruct(override val offset: Int, val layout: StructLayout) : StructMember {
  init {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
  }

  override val widthBits: Int
    get() = layout.totalBits
}

/**
 * A nested header stack member — a whole [HeaderStackLayout] embedded at [offset] inside a struct.
 */
data class NestedStack(override val offset: Int, val layout: HeaderStackLayout) : StructMember {
  init {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
  }

  override val widthBits: Int
    get() = layout.totalBits
}

/**
 * Static layout of a P4 struct type: member name → location+kind within the struct's bit layout.
 *
 * Unlike [HeaderLayout], structs can contain nested headers/structs (P4 spec §8.11). Each struct
 * member is a [StructMember] variant carrying both the offset and the kind-specific payload
 * (primitive width, nested header layout, nested struct layout). Structs have no validity bit.
 */
data class StructLayout(val typeName: String, val members: Map<String, StructMember>) {
  /** Total width of this struct in bits. */
  val totalBits: Int = members.values.maxOfOrNull { it.offset + it.widthBits } ?: 0

  /**
   * Precomputed [FieldSlot]s for just the primitive members, in declaration order. Used by
   * [StructVal.bufferBacked] without per-call allocation.
   */
  val primitiveSlots: Map<String, FieldSlot> = buildMap {
    for ((name, m) in members) if (m is PrimitiveField)
      put(name, FieldSlot(m.offset, m.width, m.kind))
  }

  /**
   * True when every member is a primitive (no nested headers / structs / stacks). Such structs can
   * be constructed via [StructVal.bufferBacked] directly — see
   * [DefaultValues][fourward.simulator.defaultValue].
   */
  val allPrimitive: Boolean = members.values.all { it is PrimitiveField }
}

/**
 * A view onto a [StructLayout]'s members in a [PacketBuffer], anchored at a [base] bit offset.
 *
 * Primitive members are read/written with [get] / [set]. Nested members produce sub-views via
 * [header] / [struct] / [stack] — cheap wrappers that share the parent's buffer at the nested
 * offset.
 *
 * Extends [Value] so it can flow through the [Environment] alongside other runtime values once the
 * buffer-backed migration is complete. [deepCopy] returns a view over a fresh buffer copy.
 */
class StructView(val buffer: PacketBuffer, val layout: StructLayout, val base: Int = 0) : Value() {

  /** Returns a view over an independent copy of [buffer]. Used by the fork path. */
  override fun deepCopy(): StructView = StructView(buffer.copyOf(), layout, base)

  /** Reads a primitive member. Throws if [field] is nested or absent. */
  operator fun get(field: String): BitVal {
    val m = memberOf(field)
    require(m is PrimitiveField) {
      "${layout.typeName}.$field is a nested ${m::class.simpleName}; use header() or struct()"
    }
    val bits = buffer.readBits(base + m.offset, m.width)
    return BitVal(bits, m.width)
  }

  /** Writes a primitive member. Throws if [field] is nested, absent, or the width mismatches. */
  operator fun set(field: String, value: BitVal) {
    val m = memberOf(field)
    require(m is PrimitiveField) {
      "${layout.typeName}.$field is a nested ${m::class.simpleName}; use header() or struct()"
    }
    require(value.bits.width == m.width) {
      "${layout.typeName}.$field expects bit<${m.width}>, got bit<${value.bits.width}>"
    }
    buffer.writeBits(base + m.offset, m.width, value.bits.value.toLong())
  }

  /** Returns a view onto a nested header member. Throws if [field] is not a nested header. */
  fun header(field: String): HeaderView {
    val m = memberOf(field)
    require(m is NestedHeader) {
      "${layout.typeName}.$field is not a nested header (got ${m::class.simpleName})"
    }
    return HeaderView(buffer, m.layout, base + m.offset)
  }

  /** Returns a view onto a nested struct member. Throws if [field] is not a nested struct. */
  fun struct(field: String): StructView {
    val m = memberOf(field)
    require(m is NestedStruct) {
      "${layout.typeName}.$field is not a nested struct (got ${m::class.simpleName})"
    }
    return StructView(buffer, m.layout, base + m.offset)
  }

  /** Returns a view onto a nested header-stack member. Throws if [field] is not a nested stack. */
  fun stack(field: String): HeaderStackView {
    val m = memberOf(field)
    require(m is NestedStack) {
      "${layout.typeName}.$field is not a nested header stack (got ${m::class.simpleName})"
    }
    return HeaderStackView(buffer, m.layout, base + m.offset)
  }

  private fun memberOf(field: String): StructMember =
    layout.members[field]
      ?: throw IllegalArgumentException("${layout.typeName} has no member '$field'")
}
