package fourward.simulator

import fourward.ir.v1.BlockStmt
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.Expr
import fourward.ir.v1.IfStmt
import fourward.ir.v1.Literal
import fourward.ir.v1.MethodCall
import fourward.ir.v1.MethodCallStmt
import fourward.ir.v1.NameRef
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.SourceInfo
import fourward.ir.v1.Stmt
import fourward.ir.v1.Type
import fourward.sim.v1.DropReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for trace event emission in the [Interpreter].
 *
 * Covers: MarkToDropEvent emission, BranchEvent.control_name, and SourceInfo propagation.
 */
class InterpreterTraceEventTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun boolLit(v: Boolean): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setBoolean(v))
      .setType(Type.newBuilder().setBoolean(true))
      .build()

  /** Builds a mark_to_drop(sm) method call statement. */
  private fun markToDropStmt(sourceInfo: SourceInfo? = null): Stmt {
    val call =
      Expr.newBuilder()
        .setMethodCall(
          MethodCall.newBuilder()
            .setTarget(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName("mark_to_drop")))
            .setMethod("__call__")
            .addArgs(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName("sm")))
        )
        .build()
    val builder = Stmt.newBuilder().setMethodCall(MethodCallStmt.newBuilder().setCall(call))
    if (sourceInfo != null) builder.sourceInfo = sourceInfo
    return builder.build()
  }

  /** Builds an if statement with optional source_info. */
  private fun ifStmt(
    condition: Expr,
    thenStmts: List<Stmt> = emptyList(),
    elseStmts: List<Stmt> = emptyList(),
    sourceInfo: SourceInfo? = null,
  ): Stmt {
    val builder =
      Stmt.newBuilder()
        .setIfStmt(
          IfStmt.newBuilder()
            .setCondition(condition)
            .setThenBlock(BlockStmt.newBuilder().addAllStmts(thenStmts))
            .setElseBlock(BlockStmt.newBuilder().addAllStmts(elseStmts))
        )
    if (sourceInfo != null) builder.sourceInfo = sourceInfo
    return builder.build()
  }

  private fun controlConfig(controlName: String, vararg stmts: Stmt): P4BehavioralConfig =
    P4BehavioralConfig.newBuilder()
      .addControls(
        ControlDecl.newBuilder().setName(controlName).addAllApplyBody(stmts.toList())
      )
      .build()

  private fun standardMetadataEnv(): Environment {
    val env = Environment()
    val sm =
      StructVal(
        "standard_metadata_t",
        mutableMapOf("egress_spec" to BitVal(0, V1ModelArchitecture.PORT_BITS)),
      )
    env.define("sm", sm)
    return env
  }

  // ---------------------------------------------------------------------------
  // Test 1: mark_to_drop emits MarkToDropEvent
  // ---------------------------------------------------------------------------

  @Test
  fun `mark_to_drop emits MarkToDropEvent with MARK_TO_DROP reason`() {
    val config = controlConfig("MyIngress", markToDropStmt())
    val env = standardMetadataEnv()
    val pktCtx = PacketContext(byteArrayOf())
    Interpreter(config, TableStore(), pktCtx).runControl("MyIngress", env)

    val markToDropEvents = pktCtx.getEvents().filter { it.hasMarkToDrop() }
    assertEquals("expected exactly one MarkToDropEvent", 1, markToDropEvents.size)
    assertEquals(DropReason.MARK_TO_DROP, markToDropEvents[0].markToDrop.reason)
  }

  // ---------------------------------------------------------------------------
  // Test 2: BranchEvent carries control_name
  // ---------------------------------------------------------------------------

  @Test
  fun `BranchEvent carries control name`() {
    val config = controlConfig("MyIngress", ifStmt(boolLit(true)))
    val env = Environment()
    val pktCtx = PacketContext(byteArrayOf())
    Interpreter(config, TableStore(), pktCtx).runControl("MyIngress", env)

    val branchEvents = pktCtx.getEvents().filter { it.hasBranch() }
    assertEquals(1, branchEvents.size)
    assertEquals("MyIngress", branchEvents[0].branch.controlName)
  }

  // ---------------------------------------------------------------------------
  // Test 3: trace events carry source_info from Stmt
  // ---------------------------------------------------------------------------

  @Test
  fun `trace events carry source info from Stmt`() {
    val si =
      SourceInfo.newBuilder()
        .setFile("test.p4")
        .setLine(42)
        .setColumn(4)
        .setSourceFragment("hdr.ipv4.isValid()")
        .build()
    val config = controlConfig("MyIngress", ifStmt(boolLit(true), sourceInfo = si))
    val env = Environment()
    val pktCtx = PacketContext(byteArrayOf())
    Interpreter(config, TableStore(), pktCtx).runControl("MyIngress", env)

    val branchEvents = pktCtx.getEvents().filter { it.hasBranch() }
    assertEquals(1, branchEvents.size)
    assertTrue(branchEvents[0].hasSourceInfo())
    assertEquals("test.p4", branchEvents[0].sourceInfo.file)
    assertEquals(42, branchEvents[0].sourceInfo.line)
    assertEquals(4, branchEvents[0].sourceInfo.column)
    assertEquals("hdr.ipv4.isValid()", branchEvents[0].sourceInfo.sourceFragment)
  }

  // ---------------------------------------------------------------------------
  // Test 4: source_info absent when Stmt has no source_info
  // ---------------------------------------------------------------------------

  @Test
  fun `source info absent when Stmt has no source_info`() {
    val config = controlConfig("MyIngress", ifStmt(boolLit(true)))
    val env = Environment()
    val pktCtx = PacketContext(byteArrayOf())
    Interpreter(config, TableStore(), pktCtx).runControl("MyIngress", env)

    val branchEvents = pktCtx.getEvents().filter { it.hasBranch() }
    assertEquals(1, branchEvents.size)
    assertFalse(branchEvents[0].hasSourceInfo())
  }
}
