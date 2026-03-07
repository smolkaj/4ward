package fourward.simulator

import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.Expr
import fourward.ir.v1.MethodCall
import fourward.ir.v1.MethodCallStmt
import fourward.ir.v1.ParserDecl
import fourward.ir.v1.ParserState
import fourward.ir.v1.SourceInfo
import fourward.ir.v1.Stmt
import fourward.ir.v1.Transition
import fourward.sim.v1.SimulatorProto.DropReason
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

  /** Builds a mark_to_drop(sm) method call statement. */
  private fun markToDropStmt(sourceInfo: SourceInfo? = null): Stmt {
    val call =
      Expr.newBuilder()
        .setMethodCall(
          MethodCall.newBuilder()
            .setTarget(nameRef("mark_to_drop"))
            .setMethod("__call__")
            .addArgs(nameRef("sm"))
        )
        .build()
    val builder = Stmt.newBuilder().setMethodCall(MethodCallStmt.newBuilder().setCall(call))
    if (sourceInfo != null) builder.sourceInfo = sourceInfo
    return builder.build()
  }

  private fun standardMetadataEnv(): Environment {
    val env = Environment()
    val sm =
      StructVal(
        "standard_metadata_t",
        mutableMapOf("egress_spec" to BitVal(0, V1ModelArchitecture.DEFAULT_PORT_BITS)),
      )
    env.define("sm", sm)
    return env
  }

  private fun sourceInfo(file: String, line: Int, fragment: String): SourceInfo =
    SourceInfo.newBuilder().setFile(file).setLine(line).setSourceFragment(fragment).build()

  private fun parserState(
    name: String,
    nextState: String,
    sourceInfo: SourceInfo? = null,
  ): ParserState {
    val builder =
      ParserState.newBuilder()
        .setName(name)
        .setTransition(Transition.newBuilder().setNextState(nextState))
    if (sourceInfo != null) builder.sourceInfo = sourceInfo
    return builder.build()
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

  // ---------------------------------------------------------------------------
  // Test 5: parser transitions carry source_info from ParserState
  // ---------------------------------------------------------------------------

  @Test
  fun `parser transition carries source_info from ParserState`() {
    val si = sourceInfo("test.p4", 10, "start")
    val parser =
      ParserDecl.newBuilder().setName("P").addStates(parserState("start", "accept", si)).build()
    val config = BehavioralConfig.newBuilder().addParsers(parser).build()
    val pktCtx = PacketContext(byteArrayOf())
    Interpreter(config, TableStore(), pktCtx).runParser("P", Environment())

    val transitions = pktCtx.getEvents().filter { it.hasParserTransition() }
    assertEquals(1, transitions.size)
    assertTrue(transitions[0].hasSourceInfo())
    assertEquals("test.p4", transitions[0].sourceInfo.file)
    assertEquals(10, transitions[0].sourceInfo.line)
    assertEquals("start", transitions[0].sourceInfo.sourceFragment)
  }

  // ---------------------------------------------------------------------------
  // Test 6: multi-state parser — each transition gets its own source_info
  // ---------------------------------------------------------------------------

  @Test
  fun `multi-state parser transitions each carry their own source_info`() {
    val si1 = sourceInfo("test.p4", 10, "start")
    val si2 = sourceInfo("test.p4", 20, "parse_header")
    val parser =
      ParserDecl.newBuilder()
        .setName("P")
        .addStates(parserState("start", "parse_header", si1))
        .addStates(parserState("parse_header", "accept", si2))
        .build()
    val config = BehavioralConfig.newBuilder().addParsers(parser).build()
    val pktCtx = PacketContext(byteArrayOf())
    Interpreter(config, TableStore(), pktCtx).runParser("P", Environment())

    val transitions = pktCtx.getEvents().filter { it.hasParserTransition() }
    assertEquals(2, transitions.size)
    assertEquals("start", transitions[0].sourceInfo.sourceFragment)
    assertEquals(10, transitions[0].sourceInfo.line)
    assertEquals("parse_header", transitions[1].sourceInfo.sourceFragment)
    assertEquals(20, transitions[1].sourceInfo.line)
  }

  // ---------------------------------------------------------------------------
  // Test 7: sequential statements each carry their own source_info
  // ---------------------------------------------------------------------------

  @Test
  fun `sequential statements carry independent source_info`() {
    val si1 = sourceInfo("test.p4", 10, "if (true)")
    val si2 = sourceInfo("test.p4", 20, "mark_to_drop(sm)")
    val config =
      controlConfig("MyIngress", ifStmt(boolLit(true), sourceInfo = si1), markToDropStmt(si2))
    val env = standardMetadataEnv()
    val pktCtx = PacketContext(byteArrayOf())
    Interpreter(config, TableStore(), pktCtx).runControl("MyIngress", env)

    val events = pktCtx.getEvents()
    val branchEvent = events.first { it.hasBranch() }
    val markToDropEvent = events.first { it.hasMarkToDrop() }
    assertEquals("if (true)", branchEvent.sourceInfo.sourceFragment)
    assertEquals(10, branchEvent.sourceInfo.line)
    assertEquals("mark_to_drop(sm)", markToDropEvent.sourceInfo.sourceFragment)
    assertEquals(20, markToDropEvent.sourceInfo.line)
  }
}
