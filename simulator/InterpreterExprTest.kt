package fourward.simulator

import fourward.ir.v1.BinaryOp
import fourward.ir.v1.BinaryOperator
import fourward.ir.v1.BitType
import fourward.ir.v1.Cast
import fourward.ir.v1.Expr
import fourward.ir.v1.FieldAccess
import fourward.ir.v1.Literal
import fourward.ir.v1.MethodCall
import fourward.ir.v1.NameRef
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.Type
import fourward.ir.v1.UnaryOp
import fourward.ir.v1.UnaryOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Interpreter] expression evaluation.
 *
 * Tests cover literal evaluation, name references, binary/unary operators, field access, casts, and
 * header validity methods. Each test builds a minimal [Expr] proto and calls [Interpreter.evalExpr]
 * directly.
 */
class InterpreterExprTest {

  private val emptyConfig = P4BehavioralConfig.getDefaultInstance()

  private val emptyEnv
    get() = Environment(byteArrayOf())

  private fun interp() = Interpreter(emptyConfig, TableStore())

  // ---------------------------------------------------------------------------
  // Expr builder helpers
  // ---------------------------------------------------------------------------

  /** Unsigned integer literal of [value] in [width] bits. */
  private fun bit(value: Long, width: Int): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setInteger(value))
      .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)))
      .build()

  private fun boolLit(v: Boolean): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setBoolean(v))
      .setType(Type.newBuilder().setBoolean(true))
      .build()

  /** Name reference — type annotation omitted since evalExpr doesn't use it for name refs. */
  private fun nameRef(name: String): Expr =
    Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(name)).build()

  private fun boolType(): Type = Type.newBuilder().setBoolean(true).build()

  private fun bitType(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

  // ---------------------------------------------------------------------------
  // Literal evaluation
  // ---------------------------------------------------------------------------

  @Test
  fun `integer literal evaluates to BitVal`() {
    assertEquals(BitVal(42, 8), interp().evalExpr(bit(42, 8), emptyEnv))
  }

  @Test
  fun `boolean literal true evaluates to BoolVal`() {
    assertEquals(BoolVal(true), interp().evalExpr(boolLit(true), emptyEnv))
  }

  @Test
  fun `boolean literal false evaluates to BoolVal`() {
    assertEquals(BoolVal(false), interp().evalExpr(boolLit(false), emptyEnv))
  }

  // ---------------------------------------------------------------------------
  // Name reference
  // ---------------------------------------------------------------------------

  @Test
  fun `name ref resolves variable from environment`() {
    val env = emptyEnv
    env.define("x", BitVal(7, 8))
    assertEquals(BitVal(7, 8), interp().evalExpr(nameRef("x"), env))
  }

  @Test
  fun `undefined name ref throws`() {
    assertThrows(IllegalStateException::class.java) {
      interp().evalExpr(nameRef("missing"), emptyEnv)
    }
  }

  // ---------------------------------------------------------------------------
  // Binary operations
  // ---------------------------------------------------------------------------

  @Test
  fun `ADD wraps at width boundary`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder().setOp(BinaryOperator.ADD).setLeft(bit(255, 8)).setRight(bit(1, 8))
        )
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(0, 8), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `SUB underflows with wrapping`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder().setOp(BinaryOperator.SUB).setLeft(bit(0, 8)).setRight(bit(1, 8))
        )
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(255, 8), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `EQ returns true for equal values`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder().setOp(BinaryOperator.EQ).setLeft(bit(5, 8)).setRight(bit(5, 8))
        )
        .setType(boolType())
        .build()
    assertEquals(BoolVal(true), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `EQ returns false for unequal values`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder().setOp(BinaryOperator.EQ).setLeft(bit(5, 8)).setRight(bit(6, 8))
        )
        .setType(boolType())
        .build()
    assertEquals(BoolVal(false), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `LT returns true when left is less than right`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder().setOp(BinaryOperator.LT).setLeft(bit(3, 8)).setRight(bit(5, 8))
        )
        .setType(boolType())
        .build()
    assertEquals(BoolVal(true), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `BIT_AND masks bits`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder()
            .setOp(BinaryOperator.BIT_AND)
            .setLeft(bit(0b1010_1010L, 8))
            .setRight(bit(0b1111_0000L, 8))
        )
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(0b1010_0000, 8), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `logical AND of two trues is true`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder()
            .setOp(BinaryOperator.AND)
            .setLeft(boolLit(true))
            .setRight(boolLit(true))
        )
        .setType(boolType())
        .build()
    assertEquals(BoolVal(true), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `logical AND with false is false`() {
    val expr =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder()
            .setOp(BinaryOperator.AND)
            .setLeft(boolLit(true))
            .setRight(boolLit(false))
        )
        .setType(boolType())
        .build()
    assertEquals(BoolVal(false), interp().evalExpr(expr, emptyEnv))
  }

  // ---------------------------------------------------------------------------
  // Unary operations
  // ---------------------------------------------------------------------------

  @Test
  fun `unary NOT negates boolean`() {
    val expr =
      Expr.newBuilder()
        .setUnaryOp(UnaryOp.newBuilder().setOp(UnaryOperator.NOT).setExpr(boolLit(true)))
        .setType(boolType())
        .build()
    assertEquals(BoolVal(false), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `unary BIT_NOT inverts all bits`() {
    val expr =
      Expr.newBuilder()
        .setUnaryOp(UnaryOp.newBuilder().setOp(UnaryOperator.BIT_NOT).setExpr(bit(0b1010_1010L, 8)))
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(0b0101_0101, 8), interp().evalExpr(expr, emptyEnv))
  }

  // ---------------------------------------------------------------------------
  // Field access
  // ---------------------------------------------------------------------------

  @Test
  fun `field access reads field from header`() {
    val env = emptyEnv
    env.define(
      "hdr",
      HeaderVal(
        typeName = "ethernet_t",
        fields = mutableMapOf("etherType" to BitVal(0x0800, 16)),
        valid = true,
      ),
    )
    val expr =
      Expr.newBuilder()
        .setFieldAccess(FieldAccess.newBuilder().setExpr(nameRef("hdr")).setFieldName("etherType"))
        .setType(bitType(16))
        .build()
    assertEquals(BitVal(0x0800, 16), interp().evalExpr(expr, env))
  }

  @Test
  fun `field access reads field from struct`() {
    val env = emptyEnv
    env.define(
      "meta",
      StructVal(typeName = "meta_t", fields = mutableMapOf("count" to BitVal(3, 8))),
    )
    val expr =
      Expr.newBuilder()
        .setFieldAccess(FieldAccess.newBuilder().setExpr(nameRef("meta")).setFieldName("count"))
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(3, 8), interp().evalExpr(expr, env))
  }

  // ---------------------------------------------------------------------------
  // Cast
  // ---------------------------------------------------------------------------

  @Test
  fun `cast truncates to narrower width`() {
    // 0xAB (8 bits) → 4 bits = 0xB
    val expr =
      Expr.newBuilder()
        .setCast(Cast.newBuilder().setTargetType(bitType(4)).setExpr(bit(0xABL, 8)))
        .setType(bitType(4))
        .build()
    assertEquals(BitVal(0xB, 4), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `cast widens to larger width`() {
    val expr =
      Expr.newBuilder()
        .setCast(Cast.newBuilder().setTargetType(bitType(16)).setExpr(bit(0xABL, 8)))
        .setType(bitType(16))
        .build()
    assertEquals(BitVal(0xAB, 16), interp().evalExpr(expr, emptyEnv))
  }

  // ---------------------------------------------------------------------------
  // Header validity method calls
  // ---------------------------------------------------------------------------

  @Test
  fun `isValid returns false for invalid header`() {
    val env = emptyEnv
    env.define("hdr", HeaderVal(typeName = "ethernet_t", valid = false))
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("isValid"))
        .setType(boolType())
        .build()
    assertEquals(BoolVal(false), interp().evalExpr(expr, env))
  }

  @Test
  fun `isValid returns true for valid header`() {
    val env = emptyEnv
    env.define("hdr", HeaderVal(typeName = "ethernet_t", valid = true))
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("isValid"))
        .setType(boolType())
        .build()
    assertEquals(BoolVal(true), interp().evalExpr(expr, env))
  }

  @Test
  fun `setValid marks header as valid`() {
    val env = emptyEnv
    val hdr = HeaderVal(typeName = "ethernet_t", valid = false)
    env.define("hdr", hdr)
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("setValid"))
        .setType(boolType())
        .build()
    interp().evalExpr(expr, env)
    assertTrue(hdr.valid)
  }

  @Test
  fun `setInvalid marks header as invalid and clears fields`() {
    val env = emptyEnv
    val hdr =
      HeaderVal(
        typeName = "ethernet_t",
        fields = mutableMapOf("etherType" to BitVal(0x0800, 16)),
        valid = true,
      )
    env.define("hdr", hdr)
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("setInvalid"))
        .setType(boolType())
        .build()
    interp().evalExpr(expr, env)
    assertFalse(hdr.valid)
    // setInvalid() clears fields per P4 spec §8.17
    assertTrue(hdr.fields.isEmpty())
  }
}
