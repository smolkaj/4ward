// Copyright 2026 4ward Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fourward.simulator

import fourward.ir.v1.BitType
import fourward.ir.v1.Expr
import fourward.ir.v1.FieldAccess
import fourward.ir.v1.FieldDecl
import fourward.ir.v1.HeaderDecl
import fourward.ir.v1.HeaderUnionDecl
import fourward.ir.v1.MethodCall
import fourward.ir.v1.NameRef
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.Type
import fourward.ir.v1.TypeDecl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Interpreter] extract and emit operations.
 *
 * Tests verify that header fields are correctly unpacked from packet bytes ([execExtract]) and
 * repacked into packet bytes ([execEmit]), with particular attention to sub-byte fields that share
 * a byte (e.g. IPv4's bit<4> version and ihl).
 */
class InterpreterPacketTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun bitType(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

  private fun nameRef(name: String): Expr =
    Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(name)).build()

  /** Builds an extract or emit call: `pkt.<method>(headerVarName)`. */
  private fun packetCall(method: String, headerVarName: String): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef("pkt"))
          .setMethod(method)
          .addArgs(nameRef(headerVarName))
      )
      .build()

  /** Builds a header TypeDecl from a list of (fieldName, bitwidth) pairs. */
  private fun headerType(typeName: String, vararg fields: Pair<String, Int>): TypeDecl =
    TypeDecl.newBuilder()
      .setName(typeName)
      .setHeader(
        HeaderDecl.newBuilder().also { hdr ->
          for ((name, width) in fields) {
            hdr.addFields(FieldDecl.newBuilder().setName(name).setType(bitType(width)))
          }
        }
      )
      .build()

  private fun interp(packetCtx: PacketContext, vararg types: TypeDecl): Interpreter {
    val config =
      P4BehavioralConfig.newBuilder().also { cfg -> types.forEach { cfg.addTypes(it) } }.build()
    return Interpreter(config, TableStore(), packetCtx)
  }

  // ---------------------------------------------------------------------------
  // missing PacketContext
  // ---------------------------------------------------------------------------

  @Test
  fun `extract without PacketContext gives a descriptive error`() {
    val type = headerType("h_t", "f" to 8)
    val config = P4BehavioralConfig.newBuilder().also { cfg -> cfg.addTypes(type) }.build()
    val interp = Interpreter(config, TableStore()) // no PacketContext
    val env = Environment()
    env.define("hdr", HeaderVal(typeName = "h_t", valid = false))

    val ex =
      assertThrows(IllegalStateException::class.java) {
        interp.evalExpr(packetCall("extract", "hdr"), env)
      }
    assertTrue(ex.message!!.contains("PacketContext"))
  }

  // ---------------------------------------------------------------------------
  // extract
  // ---------------------------------------------------------------------------

  @Test
  fun `extract byte-aligned 16-bit field`() {
    val type = headerType("h_t", "etherType" to 16)
    val pktCtx = PacketContext(byteArrayOf(0x08, 0x00))
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("extract", "hdr"), env)

    assertTrue(header.valid)
    assertEquals(BitVal(0x0800, 16), header.fields["etherType"])
  }

  @Test
  fun `extract two sub-byte fields from a shared byte`() {
    // 0x45: upper nibble = version=4, lower nibble = ihl=5
    val type = headerType("h_t", "version" to 4, "ihl" to 4)
    val pktCtx = PacketContext(byteArrayOf(0x45))
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("extract", "hdr"), env)

    assertTrue(header.valid)
    assertEquals(BitVal(4, 4), header.fields["version"])
    assertEquals(BitVal(5, 4), header.fields["ihl"])
  }

  @Test
  fun `extract mixed-width fields spanning four bytes`() {
    // bit<4>(4) + bit<4>(5) + bit<8>(0) + bit<16>(0x0800) = 0x45 0x00 0x08 0x00
    val type = headerType("h_t", "version" to 4, "ihl" to 4, "tos" to 8, "len" to 16)
    val pktCtx = PacketContext(byteArrayOf(0x45, 0x00, 0x08, 0x00))
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("extract", "hdr"), env)

    assertEquals(BitVal(4, 4), header.fields["version"])
    assertEquals(BitVal(5, 4), header.fields["ihl"])
    assertEquals(BitVal(0, 8), header.fields["tos"])
    assertEquals(BitVal(0x0800, 16), header.fields["len"])
  }

  @Test
  fun `extract advances the packet buffer cursor`() {
    // Two sequential extracts: first grabs bytes [0x08, 0x00], second gets [0x01, 0x00].
    val type = headerType("h_t", "f" to 16)
    val pktCtx = PacketContext(byteArrayOf(0x08, 0x00, 0x01, 0x00))
    val env = Environment()
    val h1 = HeaderVal(typeName = "h_t", valid = false)
    val h2 = HeaderVal(typeName = "h_t", valid = false)
    env.define("h1", h1)
    env.define("h2", h2)

    val interp = interp(pktCtx, type)
    interp.evalExpr(packetCall("extract", "h1"), env)
    interp.evalExpr(packetCall("extract", "h2"), env)

    assertEquals(BitVal(0x0800, 16), h1.fields["f"])
    assertEquals(BitVal(0x0100, 16), h2.fields["f"])
  }

  // ---------------------------------------------------------------------------
  // emit
  // ---------------------------------------------------------------------------

  @Test
  fun `emit valid 16-bit header produces correct bytes`() {
    val type = headerType("h_t", "etherType" to 16)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    val header =
      HeaderVal(
        typeName = "h_t",
        fields = mutableMapOf("etherType" to BitVal(0x0800, 16)),
        valid = true,
      )
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(byteArrayOf(0x08, 0x00), pktCtx.outputPayload())
  }

  @Test
  fun `emit invalid header produces no bytes`() {
    val type = headerType("h_t", "etherType" to 16)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(byteArrayOf(), pktCtx.outputPayload())
  }

  @Test
  fun `emit packs sub-byte fields into a single byte`() {
    // version=4, ihl=5 → both in the same byte → 0x45
    val type = headerType("h_t", "version" to 4, "ihl" to 4)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    val header =
      HeaderVal(
        typeName = "h_t",
        fields = mutableMapOf("version" to BitVal(4, 4), "ihl" to BitVal(5, 4)),
        valid = true,
      )
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(byteArrayOf(0x45), pktCtx.outputPayload())
  }

  @Test
  fun `extract then emit round-trips the original bytes`() {
    val type = headerType("h_t", "version" to 4, "ihl" to 4, "tos" to 8, "len" to 16)
    val input = byteArrayOf(0x45, 0x00, 0x08, 0x00)
    val pktCtx = PacketContext(input)
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    val interp = interp(pktCtx, type)
    interp.evalExpr(packetCall("extract", "hdr"), env)
    interp.evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(input, pktCtx.outputPayload())
  }

  // ---------------------------------------------------------------------------
  // extract into header union members (P4 §8.20)
  // ---------------------------------------------------------------------------

  private fun namedType(name: String): Type = Type.newBuilder().setNamed(name).build()

  private fun headerUnionType(typeName: String, vararg members: Pair<String, String>): TypeDecl =
    TypeDecl.newBuilder()
      .setName(typeName)
      .setHeaderUnion(
        HeaderUnionDecl.newBuilder().also { u ->
          for ((name, headerTypeName) in members) {
            u.addFields(FieldDecl.newBuilder().setName(name).setType(namedType(headerTypeName)))
          }
        }
      )
      .build()

  /** Builds an extract call whose argument is a field access: `pkt.extract(unionVar.member)`. */
  private fun unionExtractCall(unionVar: String, member: String, unionTypeName: String): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef("pkt"))
          .setMethod("extract")
          .addArgs(
            Expr.newBuilder()
              .setFieldAccess(
                FieldAccess.newBuilder()
                  .setExpr(
                    Expr.newBuilder()
                      .setNameRef(NameRef.newBuilder().setName(unionVar))
                      .setType(namedType(unionTypeName))
                  )
                  .setFieldName(member)
              )
          )
      )
      .build()

  @Test
  fun `extract into union member invalidates siblings`() {
    // Union with two 8-bit headers: extracting into member "a" should invalidate "b".
    val hdrA = headerType("a_t", "f" to 8)
    val hdrB = headerType("b_t", "f" to 8)
    val union = headerUnionType("u_t", "a" to "a_t", "b" to "b_t")

    val memberA = HeaderVal("a_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val memberB = HeaderVal("b_t", mutableMapOf("f" to BitVal(0x42, 8)), valid = true)
    val unionVal = StructVal("u_t", mutableMapOf("a" to memberA, "b" to memberB))

    val pktCtx = PacketContext(byteArrayOf(0x01))
    val env = Environment()
    env.define("u", unionVal)

    interp(pktCtx, hdrA, hdrB, union).evalExpr(unionExtractCall("u", "a", "u_t"), env)

    // Member "a" should be valid with extracted data.
    assertTrue(memberA.valid)
    assertEquals(BitVal(1, 8), memberA.fields["f"])
    // Member "b" should be invalidated (P4 §8.20: one-valid-at-a-time).
    assertFalse(memberB.valid)
  }

  @Test
  fun `extract into union member when no sibling was valid`() {
    // Both members start invalid; extract should still set the target valid.
    val hdrA = headerType("a_t", "f" to 8)
    val hdrB = headerType("b_t", "f" to 8)
    val union = headerUnionType("u_t", "a" to "a_t", "b" to "b_t")

    val memberA = HeaderVal("a_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val memberB = HeaderVal("b_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val unionVal = StructVal("u_t", mutableMapOf("a" to memberA, "b" to memberB))

    val pktCtx = PacketContext(byteArrayOf(0xFF.toByte()))
    val env = Environment()
    env.define("u", unionVal)

    interp(pktCtx, hdrA, hdrB, union).evalExpr(unionExtractCall("u", "a", "u_t"), env)

    assertTrue(memberA.valid)
    assertEquals(BitVal(0xFF, 8), memberA.fields["f"])
    assertFalse(memberB.valid)
  }

  @Test
  fun `sequential union extracts swap validity`() {
    // Extract "a", then extract "b" — only "b" should be valid at the end.
    val hdrA = headerType("a_t", "f" to 8)
    val hdrB = headerType("b_t", "f" to 8)
    val union = headerUnionType("u_t", "a" to "a_t", "b" to "b_t")

    val memberA = HeaderVal("a_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val memberB = HeaderVal("b_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val unionVal = StructVal("u_t", mutableMapOf("a" to memberA, "b" to memberB))

    val pktCtx = PacketContext(byteArrayOf(0x01, 0x02))
    val env = Environment()
    env.define("u", unionVal)

    val interp = interp(pktCtx, hdrA, hdrB, union)
    interp.evalExpr(unionExtractCall("u", "a", "u_t"), env)
    interp.evalExpr(unionExtractCall("u", "b", "u_t"), env)

    // After second extract, "a" is invalidated and "b" is valid.
    assertFalse(memberA.valid)
    assertTrue(memberB.valid)
    assertEquals(BitVal(2, 8), memberB.fields["f"])
  }
}
