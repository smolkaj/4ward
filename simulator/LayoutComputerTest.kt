package fourward.simulator

import fourward.ir.FieldDecl
import fourward.ir.HeaderDecl
import fourward.ir.StructDecl
import fourward.ir.Type
import fourward.ir.TypeDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [computeLayouts]. */
class LayoutComputerTest {

  // =====================================================================
  // Headers with primitive fields
  // =====================================================================

  @Test
  fun `header with bit fields computes sequential offsets`() {
    val ethernet =
      headerDecl("ethernet_t", "dstAddr" to bit(48), "srcAddr" to bit(48), "etherType" to bit(16))
    val layouts = computeLayouts(mapOf("ethernet_t" to ethernet))

    val h = layouts.headers.getValue("ethernet_t")
    assertEquals(FieldSlot(0, 48), h.fields["dstAddr"])
    assertEquals(FieldSlot(48, 48), h.fields["srcAddr"])
    assertEquals(FieldSlot(96, 16), h.fields["etherType"])
    // Validity bit sits right after the last field.
    assertEquals(112, h.validBitOffset)
    assertEquals(113, h.totalBits)
  }

  @Test
  fun `header with mixed bit int and bool fields`() {
    val decl = headerDecl("mixed_t", "a" to bit(8), "b" to signedInt(16), "c" to bool())
    val layouts = computeLayouts(mapOf("mixed_t" to decl))
    val h = layouts.headers.getValue("mixed_t")
    assertEquals(FieldSlot(0, 8, PrimitiveKind.BIT), h.fields["a"])
    assertEquals(FieldSlot(8, 16, PrimitiveKind.INT), h.fields["b"])
    assertEquals(FieldSlot(24, 1, PrimitiveKind.BOOL), h.fields["c"])
    assertEquals(25, h.validBitOffset)
  }

  @Test
  fun `empty header has only the validity bit`() {
    val decl = headerDecl("empty_t")
    val layouts = computeLayouts(mapOf("empty_t" to decl))
    val h = layouts.headers.getValue("empty_t")
    assertEquals(0, h.validBitOffset)
    assertEquals(1, h.totalBits)
  }

  // =====================================================================
  // Structs with primitive fields
  // =====================================================================

  @Test
  fun `struct with primitive fields gets sequential offsets and no validity bit`() {
    val decl =
      structDecl(
        "stdmeta_t",
        "ingress_port" to bit(9),
        "egress_spec" to bit(9),
        "instance_type" to bit(32),
      )
    val layouts = computeLayouts(mapOf("stdmeta_t" to decl))
    val s = layouts.structs.getValue("stdmeta_t")
    assertEquals(PrimitiveField(0, 9), s.members["ingress_port"])
    assertEquals(PrimitiveField(9, 9), s.members["egress_spec"])
    assertEquals(PrimitiveField(18, 32), s.members["instance_type"])
    assertEquals(50, s.totalBits)
  }

  @Test
  fun `empty struct has zero total bits`() {
    val decl = structDecl("empty_t")
    val layouts = computeLayouts(mapOf("empty_t" to decl))
    assertEquals(0, layouts.structs.getValue("empty_t").totalBits)
  }

  // =====================================================================
  // Nested named types (struct-of-header, struct-of-struct)
  // =====================================================================

  @Test
  fun `struct can contain a named header`() {
    val ethernet = headerDecl("ethernet_t", "dstAddr" to bit(48), "etherType" to bit(16))
    val headers = structDecl("headers_t", "ethernet" to named("ethernet_t"))
    val layouts = computeLayouts(mapOf("ethernet_t" to ethernet, "headers_t" to headers))

    val ethLayout = layouts.headers.getValue("ethernet_t")
    assertEquals(48 + 16 + 1, ethLayout.totalBits)

    val hdrsLayout = layouts.structs.getValue("headers_t")
    // The nested ethernet becomes a NestedHeader member with the ethernet layout attached.
    assertEquals(NestedHeader(offset = 0, layout = ethLayout), hdrsLayout.members["ethernet"])
    assertEquals(ethLayout.totalBits, hdrsLayout.totalBits)
  }

  @Test
  fun `struct can contain nested structs`() {
    val inner = structDecl("inner_t", "x" to bit(16), "y" to bit(16))
    val outer =
      structDecl("outer_t", "head" to bit(8), "body" to named("inner_t"), "tail" to bit(8))
    val layouts = computeLayouts(mapOf("inner_t" to inner, "outer_t" to outer))

    val innerLayout = layouts.structs.getValue("inner_t")
    val out = layouts.structs.getValue("outer_t")
    assertEquals(PrimitiveField(0, 8), out.members["head"])
    assertEquals(NestedStruct(offset = 8, layout = innerLayout), out.members["body"])
    assertEquals(PrimitiveField(40, 8), out.members["tail"])
    assertEquals(48, out.totalBits)
  }

  @Test
  fun `layouts are computed in dependency order regardless of input order`() {
    // 'outer' references 'inner', but the map iteration order might not be dependency order.
    val inner = structDecl("inner_t", "x" to bit(8))
    val outer = structDecl("outer_t", "nested" to named("inner_t"))
    // Intentionally list outer first.
    val layouts = computeLayouts(linkedMapOf("outer_t" to outer, "inner_t" to inner))
    assertEquals(8, layouts.structs.getValue("inner_t").totalBits)
    assertEquals(8, layouts.structs.getValue("outer_t").totalBits)
  }

  // =====================================================================
  // Serializable enums
  // =====================================================================

  @Test
  fun `serializable enum has its underlying bit width`() {
    val e = enumDecl("MyEnum", listOf("A", "B", "C"), width = 8)
    val layouts = computeLayouts(mapOf("MyEnum" to e))
    assertEquals(8, layouts.enumWidths["MyEnum"])
  }

  @Test
  fun `struct can contain a serializable enum field`() {
    val e = enumDecl("Color", listOf("RED", "GREEN"), width = 4)
    val s = structDecl("thing_t", "c" to named("Color"), "extra" to bit(4))
    val layouts = computeLayouts(mapOf("Color" to e, "thing_t" to s))
    val t = layouts.structs.getValue("thing_t")
    assertEquals(PrimitiveField(0, 4), t.members["c"])
    assertEquals(PrimitiveField(4, 4), t.members["extra"])
  }

  // =====================================================================
  // Error handling
  // =====================================================================

  @Test
  fun `unknown named type reference throws`() {
    val decl = structDecl("bad_t", "field" to named("nonexistent"))
    assertThrows(IllegalArgumentException::class.java) { computeLayouts(mapOf("bad_t" to decl)) }
  }

  @Test
  fun `direct cycle between struct types throws`() {
    val a = structDecl("A", "b" to named("B"))
    val b = structDecl("B", "a" to named("A"))
    assertThrows(IllegalStateException::class.java) { computeLayouts(mapOf("A" to a, "B" to b)) }
  }

  // =====================================================================
  // Helpers — tiny DSL for constructing IR proto messages.
  // =====================================================================

  private fun headerDecl(name: String, vararg fields: Pair<String, Type>): TypeDecl =
    TypeDecl.newBuilder()
      .setName(name)
      .setHeader(
        HeaderDecl.newBuilder().addAllFields(fields.map { fieldDecl(it.first, it.second) })
      )
      .build()

  private fun structDecl(name: String, vararg fields: Pair<String, Type>): TypeDecl =
    TypeDecl.newBuilder()
      .setName(name)
      .setStruct(
        StructDecl.newBuilder().addAllFields(fields.map { fieldDecl(it.first, it.second) })
      )
      .build()

  private fun enumDecl(name: String, members: List<String>, width: Int): TypeDecl =
    TypeDecl.newBuilder()
      .setName(name)
      .setEnum(fourward.ir.EnumDecl.newBuilder().addAllMembers(members).setWidth(width).build())
      .build()

  private fun fieldDecl(name: String, type: Type): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(type).build()

  private fun bit(width: Int): Type =
    Type.newBuilder().setBit(fourward.ir.BitType.newBuilder().setWidth(width)).build()

  private fun signedInt(width: Int): Type =
    Type.newBuilder().setSignedInt(fourward.ir.IntType.newBuilder().setWidth(width)).build()

  private fun bool(): Type = Type.newBuilder().setBoolean(true).build()

  private fun named(name: String): Type = Type.newBuilder().setNamed(name).build()
}
