package fourward.simulator

import fourward.ir.PipelineConfig
import fourward.ir.Type
import fourward.ir.TypeDecl

/**
 * Layouts for every header, struct, header union, and enum type in a pipeline.
 *
 * Computed once at pipeline-load time from the IR's [TypeDecl]s and then immutable. Every runtime
 * [HeaderView] and [StructView] refers to an entry here.
 */
data class PipelineLayouts(
  val headers: Map<String, HeaderLayout>,
  val structs: Map<String, StructLayout>,
  val stacks: Map<String, HeaderStackLayout>,
  /** Serializable enum underlying bit widths, by type name. */
  val enumWidths: Map<String, Int>,
)

/**
 * Computes layouts for all the types in a pipeline from its [TypeDecl] list.
 *
 * Input is a map from type name to declaration (as stored in the runtime's type table). The
 * computer handles nested named types by recursion and caches results so each type is computed at
 * most once. Cycles between mutable (header/struct) types throw.
 *
 * Types currently handled:
 * - [fourward.ir.HeaderDecl] with primitive fields (`bit<N>`, `int<N>`, `bool`)
 * - [fourward.ir.StructDecl] with primitive or nested-named fields (struct-of-header,
 *   struct-of-struct)
 * - Serializable [fourward.ir.EnumDecl] (non-zero width; used as a bit field)
 *
 * Not yet handled (will throw `IllegalArgumentException`):
 * - Header stacks, header unions, varbit, non-serializable enums, error types
 *
 * These gaps are filled in subsequent milestones. See `designs/flat_packet_buffer.md`.
 */
fun computeLayouts(typeDecls: Map<String, TypeDecl>): PipelineLayouts {
  val computer = LayoutComputation(typeDecls)
  for (name in typeDecls.keys) {
    computer.layoutFor(name)
  }
  return PipelineLayouts(
    headers = computer.headers.toMap(),
    structs = computer.structs.toMap(),
    stacks = computer.stacks.toMap(),
    enumWidths = computer.enumWidths.toMap(),
  )
}

/**
 * Lenient version of [computeLayouts]: types whose layout can't be computed yet (header stacks,
 * unions, varbits, error fields, non-serialisable enums) are silently skipped instead of throwing.
 * The returned [PipelineLayouts] contains layouts only for types the runtime can use buffer-backed.
 *
 * Use this from runtime paths (architectures, defaultValue) where partial coverage is fine — the
 * legacy HashMap representation handles types without a layout. Tests that assert specific layout
 * shapes should use the strict [computeLayouts] overload.
 */
fun tryComputeLayouts(typeDecls: Map<String, TypeDecl>): PipelineLayouts {
  val computer = LayoutComputation(typeDecls)
  for (name in typeDecls.keys) {
    runCatching { computer.layoutFor(name) }
  }
  return PipelineLayouts(
    headers = computer.headers.toMap(),
    structs = computer.structs.toMap(),
    stacks = computer.stacks.toMap(),
    enumWidths = computer.enumWidths.toMap(),
  )
}

/**
 * Convenience overload: computes layouts directly from a [PipelineConfig], extracting the type
 * declarations from `config.device.behavioral.typesList`.
 */
fun computeLayouts(config: PipelineConfig): PipelineLayouts {
  val types = config.device.behavioral.typesList.associateBy { it.name }
  return computeLayouts(types)
}

/** Lenient overload of [tryComputeLayouts] taking a [PipelineConfig]. */
fun tryComputeLayouts(config: PipelineConfig): PipelineLayouts {
  val types = config.device.behavioral.typesList.associateBy { it.name }
  return tryComputeLayouts(types)
}

/**
 * Internal state for a single layout computation run. Memoises resolved layouts and tracks the
 * in-progress set for cycle detection.
 */
private class LayoutComputation(private val typeDecls: Map<String, TypeDecl>) {
  val headers = mutableMapOf<String, HeaderLayout>()
  val structs = mutableMapOf<String, StructLayout>()
  val stacks = mutableMapOf<String, HeaderStackLayout>()
  val enumWidths = mutableMapOf<String, Int>()

  /** Names currently being resolved. If we see one twice, we've hit a cycle. */
  private val inProgress = mutableSetOf<String>()

  /**
   * Resolves the layout for [typeName], recursively computing dependencies as needed. Returns the
   * width in bits of the resolved type (for use in parent layouts).
   */
  fun layoutFor(typeName: String): Int {
    headers[typeName]?.let {
      return it.totalBits
    }
    structs[typeName]?.let {
      return it.totalBits
    }
    enumWidths[typeName]?.let {
      return it
    }

    val decl =
      typeDecls[typeName] ?: throw IllegalArgumentException("unknown type reference: '$typeName'")

    if (!inProgress.add(typeName)) {
      error("cycle detected in type layout computation: $typeName")
    }
    try {
      return when {
        decl.hasHeader() -> {
          val layout = computeHeader(decl)
          headers[typeName] = layout
          layout.totalBits
        }
        decl.hasStruct() -> {
          val layout = computeStruct(decl)
          structs[typeName] = layout
          layout.totalBits
        }
        decl.hasEnum() -> computeEnum(decl).also { enumWidths[typeName] = it }
        decl.hasHeaderUnion() ->
          throw IllegalArgumentException("header unions not yet supported: $typeName")
        else -> throw IllegalArgumentException("unrecognised TypeDecl kind for $typeName")
      }
    } finally {
      inProgress.remove(typeName)
    }
  }

  private fun computeHeader(decl: TypeDecl): HeaderLayout {
    val fields = linkedMapOf<String, FieldSlot>()
    var offset = 0
    for (field in decl.header.fieldsList) {
      val slot = primitiveSlot(field.type, field.name, offset)
      fields[field.name] = slot
      offset += slot.width
    }
    return HeaderLayout(typeName = decl.name, fields = fields, validBitOffset = offset)
  }

  private fun computeStruct(decl: TypeDecl): StructLayout {
    val members = linkedMapOf<String, StructMember>()
    var offset = 0
    for (field in decl.struct.fieldsList) {
      val member = structMember(field.type, field.name, offset)
      members[field.name] = member
      offset += member.widthBits
    }
    return StructLayout(typeName = decl.name, members = members)
  }

  /**
   * Builds a [StructMember] for a field: primitive slot, nested header, nested struct, or nested
   * header stack.
   */
  private fun structMember(type: Type, fieldName: String, offset: Int): StructMember =
    when {
      type.hasBit() ->
        PrimitiveField(offset, type.bit.width, primitiveKindOf(fieldName, type.bit.width))
      type.hasSignedInt() -> PrimitiveField(offset, type.signedInt.width, PrimitiveKind.INT)
      type.hasBoolean() -> PrimitiveField(offset, 1, PrimitiveKind.BOOL)
      // P4's built-in `error` type — p4c emits it either as the dedicated [Type.error] variant
      // or as a named reference to "error". Tag it as ERROR so the buffer-backed encoder
      // round-trips via [ErrorCodes].
      type.hasError() -> PrimitiveField(offset, ERROR_FIELD_WIDTH_BITS, PrimitiveKind.ERROR)
      type.hasNamed() && type.named == "error" ->
        PrimitiveField(offset, ERROR_FIELD_WIDTH_BITS, PrimitiveKind.ERROR)
      type.hasHeaderStack() -> {
        // The IR's HeaderStackType references the element type by name and carries the size.
        layoutFor(type.headerStack.elementType)
        val elementLayout =
          headers[type.headerStack.elementType]
            ?: throw IllegalArgumentException(
              "header stack '$fieldName' references non-header element type " +
                "'${type.headerStack.elementType}'"
            )
        val stackLayout =
          HeaderStackLayout(
            typeName = "${type.headerStack.elementType}[${type.headerStack.size}]",
            elementLayout = elementLayout,
            size = type.headerStack.size,
          )
        NestedStack(offset, stackLayout)
      }
      type.hasNamed() -> {
        // Resolve the nested type; dispatch on whether it's a header, struct, or enum.
        layoutFor(type.named)
        when {
          headers.containsKey(type.named) -> NestedHeader(offset, headers.getValue(type.named))
          structs.containsKey(type.named) -> NestedStruct(offset, structs.getValue(type.named))
          enumWidths.containsKey(type.named) ->
            PrimitiveField(offset, enumWidths.getValue(type.named), PrimitiveKind.BIT)
          else ->
            throw IllegalArgumentException(
              "type '${type.named}' has no layout (unsupported kind for field '$fieldName')"
            )
        }
      }
      else ->
        throw IllegalArgumentException(
          "unsupported type for struct field '$fieldName': ${type.kindCase.name}"
        )
    }

  private fun computeEnum(decl: TypeDecl): Int {
    require(decl.enum.width > 0) {
      "non-serializable enum '${decl.name}' not yet supported (requires integer code assignment)"
    }
    return decl.enum.width
  }

  /** Primitive slot for a header field — headers can't contain nested named types. */
  private fun primitiveSlot(type: Type, fieldName: String, offset: Int): FieldSlot =
    when {
      type.hasBit() -> FieldSlot(offset, type.bit.width, primitiveKindOf(fieldName, type.bit.width))
      type.hasSignedInt() -> FieldSlot(offset, type.signedInt.width, PrimitiveKind.INT)
      type.hasBoolean() -> FieldSlot(offset, 1, PrimitiveKind.BOOL)
      type.hasError() -> FieldSlot(offset, ERROR_FIELD_WIDTH_BITS, PrimitiveKind.ERROR)
      type.hasNamed() && type.named == "error" ->
        FieldSlot(offset, ERROR_FIELD_WIDTH_BITS, PrimitiveKind.ERROR)
      else ->
        throw IllegalArgumentException(
          "header field '$fieldName' has non-primitive type ${type.kindCase.name}; " +
            "headers may only contain bit<N>, int<N>, or bool"
        )
    }

  /**
   * p4c lowers P4 errors to `bit<32>` after the midend, losing the type-level signal. A `bit<32>`
   * field literally named `parser_error` is tagged [PrimitiveKind.ERROR] so [BufferBackedFieldMap]
   * can transparently encode/decode via [ErrorCodes].
   */
  private fun primitiveKindOf(fieldName: String, width: Int): PrimitiveKind =
    if (fieldName == "parser_error" && width == ERROR_FIELD_WIDTH_BITS) PrimitiveKind.ERROR
    else PrimitiveKind.BIT

  companion object {
    private const val ERROR_FIELD_WIDTH_BITS = 32
  }
}
