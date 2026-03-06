package fourward.simulator

import fourward.ir.v1.ActionDecl
import fourward.ir.v1.AssignmentStmt
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.BitType
import fourward.ir.v1.BlockStmt
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.ExitStmt
import fourward.ir.v1.Expr
import fourward.ir.v1.FieldAccess
import fourward.ir.v1.MethodCallStmt
import fourward.ir.v1.NameRef
import fourward.ir.v1.Stmt
import fourward.ir.v1.SwitchCase
import fourward.ir.v1.SwitchStmt
import fourward.ir.v1.TableApplyExpr
import fourward.ir.v1.TableBehavior
import fourward.ir.v1.Type
import fourward.ir.v1.VarDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    get() = Environment()

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun interp(config: BehavioralConfig, tableStore: TableStore = TableStore()) =
    Interpreter(config, tableStore)

  /** Builds a `switch (tableName.apply().action_run)` statement. */
  private fun switchOn(
    tableName: String,
    cases: Map<String, List<Stmt>>,
    defaultStmts: List<Stmt> = emptyList(),
  ): Stmt =
    Stmt.newBuilder()
      .setSwitchStmt(
        SwitchStmt.newBuilder()
          .setSubject(
            Expr.newBuilder().setTableApply(TableApplyExpr.newBuilder().setTableName(tableName))
          )
          .addAllCases(
            cases.map { (actionName, stmts) ->
              SwitchCase.newBuilder()
                .setActionName(actionName)
                .setBlock(BlockStmt.newBuilder().addAllStmts(stmts))
                .build()
            }
          )
          .setDefaultBlock(BlockStmt.newBuilder().addAllStmts(defaultStmts))
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

  // ---------------------------------------------------------------------------
  // Switch statement
  // ---------------------------------------------------------------------------

  @Test
  fun `switch executes matching case block`() {
    // Table default action is "drop"; the switch case for "drop" sets x=1.
    val ts = TableStore().also { it.setDefaultAction("t", "drop") }
    val config =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(ActionDecl.newBuilder().setName("drop"))
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addApplyBody(
              switchOn(
                "t",
                mapOf("drop" to listOf(assign("x", bit(1, 8)))),
                listOf(assign("x", bit(2, 8))),
              )
            )
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(config, ts).runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `switch executes default block when no case matches action`() {
    // Table default action is "NoAction"; the only declared case is for "drop" (won't match).
    val ts = TableStore().also { it.setDefaultAction("t", "NoAction") }
    val config =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(ActionDecl.newBuilder().setName("NoAction"))
        .addActions(ActionDecl.newBuilder().setName("drop"))
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addApplyBody(
              switchOn(
                "t",
                mapOf("drop" to listOf(assign("x", bit(1, 8)))),
                listOf(assign("x", bit(2, 8))),
              )
            )
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(config, ts).runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  // ---------------------------------------------------------------------------
  // Local variable scoping
  // ---------------------------------------------------------------------------

  @Test
  fun `control local vars are not visible in outer scope after control returns`() {
    // "local" is declared as a local var in the control; the outer env must not see it.
    val config =
      BehavioralConfig.newBuilder()
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addLocalVars(
              VarDecl.newBuilder()
                .setName("local")
                .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(8)))
            )
        )
        .build()
    val env = emptyEnv
    interp(config).runControl("MyControl", env)
    assertNull(env.lookup("local"))
  }

  @Test
  fun `uninitialized local var defaults to zero`() {
    // Verifies that defaultValue() is called for vars with no explicit initializer.
    val config =
      BehavioralConfig.newBuilder()
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addLocalVars(
              VarDecl.newBuilder()
                .setName("x")
                .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(8)))
              // no initializer
            )
            .addApplyBody(assign("result", nameRef("x")))
        )
        .build()
    val env = emptyEnv
    env.define("result", BitVal(99, 8)) // sentinel
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(0, 8), env.lookup("result"))
  }

  @Test
  fun `local var initializer runs and is accessible in the apply body`() {
    // Declare local var "count" initialised to 42; the apply body copies it to "x".
    val config =
      BehavioralConfig.newBuilder()
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addLocalVars(
              VarDecl.newBuilder()
                .setName("count")
                .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(8)))
                .setInitializer(bit(42, 8))
            )
            .addApplyBody(assign("x", nameRef("count")))
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(42, 8), env.lookup("x"))
  }

  // ---------------------------------------------------------------------------
  // table.apply().hit / .miss
  // ---------------------------------------------------------------------------

  /** Builds `if (tableApplyExpr) { thenStmts } else { elseStmts }`. */
  private fun ifTableHit(
    tableName: String,
    accessKind: TableApplyExpr.AccessKind,
    thenStmts: List<Stmt>,
    elseStmts: List<Stmt>,
  ): Stmt {
    val subject =
      Expr.newBuilder()
        .setTableApply(
          TableApplyExpr.newBuilder().setTableName(tableName).setAccessKind(accessKind)
        )
        .setType(Type.newBuilder().setBoolean(true))
        .build()
    return ifStmt(subject, thenStmts, elseStmts)
  }

  /** Minimal config with table "t" and actions "fwd" and "NoAction". */
  private fun tableHitConfig(bodyStmt: Stmt): BehavioralConfig =
    BehavioralConfig.newBuilder()
      .addTables(TableBehavior.newBuilder().setName("t"))
      .addActions(ActionDecl.newBuilder().setName("fwd"))
      .addActions(ActionDecl.newBuilder().setName("NoAction"))
      .addControls(ControlDecl.newBuilder().setName("MyControl").addApplyBody(bodyStmt))
      .build()

  @Test
  fun `table apply hit returns true when entry matches`() {
    val ts = TableStore().also { it.setForcedHit("t", "fwd") }
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.HIT,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `table apply hit returns false when no entry matches`() {
    // TableStore with no entry for "t" — lookup misses (default action).
    val ts = TableStore()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.HIT,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `table apply miss returns true when no entry matches`() {
    val ts = TableStore()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.MISS,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `table apply miss returns false when entry matches`() {
    val ts = TableStore().also { it.setForcedHit("t", "fwd") }
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.MISS,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  // ---------------------------------------------------------------------------
  // Per-table action override resolution
  // ---------------------------------------------------------------------------

  /**
   * Config with table "t" having action_overrides "setbyte" → "setbyte_1". The first copy
   * ("setbyte") sets x=1; the second copy ("setbyte_1") sets x=2.
   */
  private fun actionOverrideConfig(controlBody: Stmt): BehavioralConfig =
    BehavioralConfig.newBuilder()
      .addTables(TableBehavior.newBuilder().setName("t").putActionOverrides("setbyte", "setbyte_1"))
      .addActions(ActionDecl.newBuilder().setName("setbyte").addBody(assign("x", bit(1, 8))))
      .addActions(
        ActionDecl.newBuilder()
          .setName("setbyte")
          .setCurrentName("setbyte_1")
          .addBody(assign("x", bit(2, 8)))
      )
      .addControls(ControlDecl.newBuilder().setName("MyControl").addApplyBody(controlBody))
      .build()

  @Test
  fun `action override resolves per-table specialized copy`() {
    // The p4info returns "setbyte" for all tables, but action_overrides maps it
    // to "setbyte_1" for table "t", so setbyte_1's body should execute (x=2).
    val ts = TableStore().also { it.setForcedHit("t", "setbyte") }
    val applyTable =
      Stmt.newBuilder()
        .setMethodCall(
          MethodCallStmt.newBuilder()
            .setCall(Expr.newBuilder().setTableApply(TableApplyExpr.newBuilder().setTableName("t")))
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(actionOverrideConfig(applyTable), ts).runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `action override returns original name for switch matching`() {
    // The override resolves to "setbyte_1" for execution, but the switch
    // statement should match on the original name "setbyte".
    val ts = TableStore().also { it.setForcedHit("t", "setbyte") }
    val config =
      actionOverrideConfig(
        switchOn(
          "t",
          mapOf("setbyte" to listOf(assign("y", bit(10, 8)))),
          listOf(assign("y", bit(20, 8))),
        )
      )
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    env.define("y", BitVal(0, 8))
    interp(config, ts).runControl("MyControl", env)
    // The override executed setbyte_1's body (x=2)
    assertEquals(BitVal(2, 8), env.lookup("x"))
    // The switch matched on the original name "setbyte" (y=10, not default 20)
    assertEquals(BitVal(10, 8), env.lookup("y"))
  }
}
