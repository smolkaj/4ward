// Copyright 2026 4ward Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0

package fourward.simulator

import fourward.ir.FieldDecl
import fourward.ir.TypeDecl

/**
 * Static pipeline-load plan for placing every top-level P4 type that a packet touches into a single
 * per-packet [PacketBuffer] at known absolute bit offsets. See the "Measured results and what's
 * left" section of `designs/flat_packet_buffer.md`.
 *
 * The plan is computed once per pipeline from the IR + [PipelineLayouts]. Each packet allocates one
 * buffer of [totalBits] bits and constructs a matching tree of [HeaderVal] / [StructVal] instances,
 * all of which share that single buffer at the right offsets. Fork copies one buffer and rewires
 * the value tree — no per-header `copyOf()`.
 */
class ConsolidatedPacketAllocator(
  /** Every requested top-level type, in input order. */
  private val topLevelTypeNames: List<String>,
  private val plan: List<TopLevelPlacement>,
  private val typesByName: Map<String, TypeDecl>,
  private val layouts: PipelineLayouts,
  /** Total packet-buffer size in bits. */
  val totalBits: Int,
) {
  /** Top-level type name → absolute offset in the packet buffer, for types actually placed. */
  val baseByType: Map<String, Int> = plan.associate { it.typeName to it.base }

  /**
   * Allocates a fresh per-packet [PacketBuffer] and constructs a [Value] for every top-level type.
   * Types placed in [plan] share the buffer; any type that couldn't be laid out (missing layout,
   * header union, varbit) falls back to the legacy per-instance [defaultValue] path.
   */
  fun allocate(): PerPacketValues {
    val buffer = PacketBuffer(totalBits)
    val byType = LinkedHashMap<String, Value>(topLevelTypeNames.size)
    for (name in topLevelTypeNames) {
      val placed = baseByType[name]
      byType[name] =
        if (placed != null) buildValue(name, buffer, placed)
        else defaultValue(name, typesByName, layouts)
    }
    return PerPacketValues(buffer, byType)
  }

  private fun buildValue(typeName: String, buffer: PacketBuffer, base: Int): Value {
    val decl = typesByName[typeName] ?: return UnitVal
    return when {
      decl.hasHeader() -> {
        val headerLayout =
          layouts.headers[typeName] ?: return defaultValue(typeName, typesByName, layouts)
        HeaderVal.bufferBacked(headerLayout, buffer, base)
      }
      decl.hasStruct() -> buildStruct(typeName, decl.struct.fieldsList, buffer, base)
      decl.hasHeaderUnion() -> buildStruct(typeName, decl.headerUnion.fieldsList, buffer, base)
      else -> defaultValue(typeName, typesByName, layouts)
    }
  }

  private fun buildStruct(
    typeName: String,
    fieldDecls: List<FieldDecl>,
    buffer: PacketBuffer,
    base: Int,
  ): StructVal {
    val structLayout = layouts.structs[typeName] ?: return legacyStruct(typeName, fieldDecls)
    if (structLayout.allPrimitive && allBufferSafe(fieldDecls)) {
      return StructVal.bufferBacked(structLayout, buffer, base)
    }
    // Mixed struct: keep the HashMap container, but place nested named members into the shared
    // buffer at their absolute offsets. Primitive members stay as independent Value objects (this
    // branch is only taken for structs that contain at least one nested header/struct/stack, which
    // is rare — the headers container typically).
    val fields = LinkedHashMap<String, Value>(structLayout.members.size)
    for ((name, member) in structLayout.members) {
      fields[name] =
        when (member) {
          is PrimitiveField -> primitiveDefault(member)
          is NestedHeader -> HeaderVal.bufferBacked(member.layout, buffer, base + member.offset)
          is NestedStruct -> buildNested(member.layout, buffer, base + member.offset)
          is NestedStack ->
            // Header stacks aren't yet consolidated — fall back to the legacy per-instance path.
            defaultValue(fieldByName(fieldDecls, name).type, typesByName, layouts)
        }
    }
    return StructVal(typeName, fields)
  }

  private fun buildNested(layout: StructLayout, buffer: PacketBuffer, base: Int): StructVal {
    if (layout.allPrimitive && typeAllBufferSafe(layout.typeName)) {
      return StructVal.bufferBacked(layout, buffer, base)
    }
    val fields = LinkedHashMap<String, Value>(layout.members.size)
    for ((name, member) in layout.members) {
      fields[name] =
        when (member) {
          is PrimitiveField -> primitiveDefault(member)
          is NestedHeader -> HeaderVal.bufferBacked(member.layout, buffer, base + member.offset)
          is NestedStruct -> buildNested(member.layout, buffer, base + member.offset)
          is NestedStack -> UnitVal // not reached for V1Model; nested stacks use legacy path above
        }
    }
    return StructVal(layout.typeName, fields)
  }

  private fun fieldByName(fields: List<FieldDecl>, name: String): FieldDecl =
    fields.first { it.name == name }

  /**
   * A struct is safe to route through the buffer-backed encoder iff every declared field has a type
   * whose encoder contract is unambiguous: primitives (bit/int/bool/error). Enum-typed fields
   * receive unconverted [InfIntVal]s in some interpreter paths, which the encoder rejects — keep
   * those on the HashMap path.
   */
  private fun allBufferSafe(fieldDecls: List<FieldDecl>): Boolean =
    fieldDecls.all { isBufferSafeType(it.type) }

  private fun typeAllBufferSafe(typeName: String): Boolean {
    val decl = typesByName[typeName] ?: return false
    return when {
      decl.hasStruct() -> allBufferSafe(decl.struct.fieldsList)
      decl.hasHeaderUnion() -> allBufferSafe(decl.headerUnion.fieldsList)
      else -> false
    }
  }

  private fun isBufferSafeType(type: fourward.ir.Type): Boolean =
    type.hasBit() ||
      type.hasSignedInt() ||
      type.hasBoolean() ||
      type.hasError() ||
      (type.hasNamed() && type.named == "error")

  private fun primitiveDefault(m: PrimitiveField): Value =
    when (m.kind) {
      PrimitiveKind.BIT -> BitVal(0L, m.width)
      PrimitiveKind.INT -> IntVal(SignedBitVector(java.math.BigInteger.ZERO, m.width))
      PrimitiveKind.BOOL -> BoolVal.FALSE
      PrimitiveKind.ERROR -> ErrorVal("NoError")
    }

  private fun legacyStruct(typeName: String, fieldDecls: List<FieldDecl>): StructVal =
    StructVal(
      typeName,
      fieldDecls.associateTo(mutableMapOf()) {
        it.name to defaultValue(it.type, typesByName, layouts)
      },
    )

  /** Planned placement of one top-level type in the packet buffer. */
  data class TopLevelPlacement(val typeName: String, val base: Int, val widthBits: Int)

  /** Output of [allocate]. */
  class PerPacketValues(val buffer: PacketBuffer, val byType: Map<String, Value>)

  companion object {
    /**
     * Builds a [ConsolidatedPacketAllocator] for the given set of top-level type names. Types that
     * can't be laid out (missing header/struct layout, header unions, unresolved) are silently
     * skipped and fall back to the legacy per-instance path on allocation — callers get null back
     * from [ConsolidatedPacketAllocator.baseByType] for those names.
     */
    fun build(
      topLevelTypeNames: List<String>,
      layouts: PipelineLayouts,
      typesByName: Map<String, TypeDecl>,
    ): ConsolidatedPacketAllocator {
      val plan = mutableListOf<TopLevelPlacement>()
      var cursor = 0
      for (name in topLevelTypeNames) {
        val width = layouts.headers[name]?.totalBits ?: layouts.structs[name]?.totalBits ?: continue
        plan += TopLevelPlacement(name, cursor, width)
        cursor += width
      }
      return ConsolidatedPacketAllocator(topLevelTypeNames, plan, typesByName, layouts, cursor)
    }
  }
}
