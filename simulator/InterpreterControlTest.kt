package fourward.simulator

import fourward.ir.v1.AssignmentStmt
import fourward.ir.v1.BitType
import fourward.ir.v1.BlockStmt
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.ExitStmt
import fourward.ir.v1.Expr
import fourward.ir.v1.FieldAccess
import fourward.ir.v1.IfStmt
import fourward.ir.v1.Literal
import fourward.ir.v1.NameRef
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.Stmt
import fourward.ir.v1.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [Interpreter] control and statement execution.
 *
 * Tests cover assignments, if/else branching, field mutation, and exit. Each test builds a minimal
 * [ControlDecl] proto and calls [Interpreter.runControl], observing side effects on the
 * [Environment].
 */
class InterpreterControlTest {

  private val emptyEnv
    get() = Environment(byteArrayOf())

  // ---------------------------------------------------------------------------
  // Expr / Stmt builder helpers
  // ---------------------------------------------------------------------------

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

  /** Builds a config with a single control named "MyControl" whose apply body is [stmts]. */
  private fun controlConfig(vararg stmts: Stmt): P4BehavioralConfig =
    P4BehavioralConfig.newBuilder()
      .addControls(ControlDecl.newBuilder().setName("MyControl").addAllApplyBody(stmts.toList()))
      .build()

  private fun interp(config: P4BehavioralConfig) = Interpreter(config, TableStore())

  /** Assignment: [varName] = [rhs]. */
  private fun assign(varName: String, rhs: Expr): Stmt =
    Stmt.newBuilder()
      .setAssignment(
        AssignmentStmt.newBuilder()
          .setLhs(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(varName)))
          .setRhs(rhs)
      )
      .build()

  private fun ifStmt(
    condition: Expr,
    thenStmts: List<Stmt>,
    elseStmts: List<Stmt> = emptyList(),
  ): Stmt =
    Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(condition)
          .setThenBlock(BlockStmt.newBuilder().addAllStmts(thenStmts))
          .setElseBlock(BlockStmt.newBuilder().addAllStmts(elseStmts))
      )
      .build()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `assignment updates variable in environment`() {
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(controlConfig(assign("x", bit(99, 8)))).runControl("MyControl", env)
    assertEquals(BitVal(99, 8), env.lookup("x"))
  }

  @Test
  fun `if-stmt takes then-block when condition is true`() {
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    val stmt = ifStmt(boolLit(true), listOf(assign("x", bit(1, 8))), listOf(assign("x", bit(2, 8))))
    interp(controlConfig(stmt)).runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `if-stmt takes else-block when condition is false`() {
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    val stmt =
      ifStmt(boolLit(false), listOf(assign("x", bit(1, 8))), listOf(assign("x", bit(2, 8))))
    interp(controlConfig(stmt)).runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `field assignment updates struct field`() {
    val env = emptyEnv
    val meta = StructVal("meta_t", mutableMapOf("count" to BitVal(0, 8)))
    env.define("meta", meta)
    val fieldLhs =
      Expr.newBuilder()
        .setFieldAccess(
          FieldAccess.newBuilder()
            .setExpr(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName("meta")))
            .setFieldName("count")
        )
        .build()
    val stmt =
      Stmt.newBuilder()
        .setAssignment(AssignmentStmt.newBuilder().setLhs(fieldLhs).setRhs(bit(7, 8)))
        .build()
    interp(controlConfig(stmt)).runControl("MyControl", env)
    assertEquals(BitVal(7, 8), meta.fields["count"])
  }

  @Test
  fun `exit statement propagates ExitException and halts execution`() {
    // Statements after exit must not run.
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    val exitStmt = Stmt.newBuilder().setExit(ExitStmt.getDefaultInstance()).build()
    assertThrows(ExitException::class.java) {
      interp(controlConfig(exitStmt, assign("x", bit(99, 8)))).runControl("MyControl", env)
    }
    assertEquals(BitVal(0, 8), env.lookup("x"))
  }
}
