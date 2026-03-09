package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ir.v1.Architecture
import fourward.ir.v1.AssignmentStmt
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.BinaryOp
import fourward.ir.v1.BinaryOperator
import fourward.ir.v1.BlockStmt
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.ExitStmt
import fourward.ir.v1.Expr
import fourward.ir.v1.FieldAccess
import fourward.ir.v1.FieldDecl
import fourward.ir.v1.IfStmt
import fourward.ir.v1.Literal
import fourward.ir.v1.NameRef
import fourward.ir.v1.ParamDecl
import fourward.ir.v1.ParserDecl
import fourward.ir.v1.ParserState
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.StageKind
import fourward.ir.v1.Stmt
import fourward.ir.v1.StructDecl
import fourward.ir.v1.Transition
import fourward.ir.v1.Type
import fourward.ir.v1.TypeDecl
import fourward.ir.v1.VarDecl
import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.ForkReason
import fourward.sim.v1.SimulatorProto.PipelineStageEvent.Direction
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [V1ModelArchitecture].
 *
 * These exercise the pipeline orchestration — multicast replication, unicast routing, and drop
 * semantics — using minimal synthetic BehavioralConfig protos, without a full p4c compile.
 */
class V1ModelArchitectureTest {

  // ---------------------------------------------------------------------------
  // Helpers: minimal v1model config construction
  // ---------------------------------------------------------------------------

  private fun field(name: String, width: Int): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(bitType(width)).build()

  private fun param(name: String, typeName: String): ParamDecl =
    ParamDecl.newBuilder().setName(name).setType(namedType(typeName)).build()

  /** standard_metadata_t with the minimal fields V1ModelArchitecture reads/writes. */
  private val standardMetaType: TypeDecl =
    standardMetaTypeWithPortWidth(V1ModelArchitecture.DEFAULT_PORT_BITS)

  /** Empty headers struct (no parsed headers needed for these tests). */
  private val headersType: TypeDecl =
    TypeDecl.newBuilder().setName("headers_t").setStruct(StructDecl.getDefaultInstance()).build()

  /** Empty metadata struct. */
  private val metaType: TypeDecl =
    TypeDecl.newBuilder().setName("meta_t").setStruct(StructDecl.getDefaultInstance()).build()

  private val parserParams =
    listOf(
      ParamDecl.newBuilder().setName("pkt").setType(namedType("packet_in")).build(),
      param("hdr", "headers_t"),
      param("meta", "meta_t"),
      param("sm", "standard_metadata_t"),
    )

  private val controlParams =
    listOf(param("hdr", "headers_t"), param("meta", "meta_t"), param("sm", "standard_metadata_t"))

  private fun noopControl(name: String): ControlDecl =
    ControlDecl.newBuilder().setName(name).addAllParams(controlParams).build()

  private val noopParser: ParserDecl =
    ParserDecl.newBuilder()
      .setName("MyParser")
      .addAllParams(parserParams)
      .addStates(
        ParserState.newBuilder()
          .setName("start")
          .setTransition(Transition.newBuilder().setNextState("accept"))
      )
      .build()

  private val v1modelArch: Architecture =
    Architecture.newBuilder()
      .setName("v1model")
      .addStages(
        PipelineStage.newBuilder()
          .setName("parser")
          .setKind(StageKind.PARSER)
          .setBlockName("MyParser")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("verify_checksum")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyVerifyChecksum")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("ingress")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyIngress")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("egress")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyEgress")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("compute_checksum")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyComputeChecksum")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("deparser")
          .setKind(StageKind.DEPARSER)
          .setBlockName("MyDeparser")
      )
      .build()

  /** An assignment statement: target.fieldName = value (integer literal). */
  private fun assignField(target: String, fieldName: String, value: Long, width: Int): Stmt =
    Stmt.newBuilder()
      .setAssignment(
        AssignmentStmt.newBuilder()
          .setLhs(
            Expr.newBuilder()
              .setFieldAccess(
                FieldAccess.newBuilder()
                  .setExpr(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(target)))
                  .setFieldName(fieldName)
              )
              .setType(bitType(width))
          )
          .setRhs(
            Expr.newBuilder()
              .setLiteral(Literal.newBuilder().setInteger(value))
              .setType(bitType(width))
          )
      )
      .build()

  /** Wraps [body] in `if (target.fieldName == value) { body }`. */
  private fun ifFieldEquals(
    target: String,
    fieldName: String,
    value: Long,
    width: Int,
    body: Stmt,
  ): Stmt =
    Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(
            Expr.newBuilder()
              .setBinaryOp(
                BinaryOp.newBuilder()
                  .setOp(BinaryOperator.EQ)
                  .setLeft(
                    Expr.newBuilder()
                      .setFieldAccess(
                        FieldAccess.newBuilder()
                          .setExpr(
                            Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(target))
                          )
                          .setFieldName(fieldName)
                      )
                      .setType(bitType(width))
                  )
                  .setRight(
                    Expr.newBuilder()
                      .setLiteral(Literal.newBuilder().setInteger(value))
                      .setType(bitType(width))
                  )
              )
              .setType(Type.newBuilder().setBoolean(true))
          )
          .setThenBlock(BlockStmt.newBuilder().addStmts(body))
      )
      .build()

  private fun enumArg(member: String): Expr =
    Expr.newBuilder().setLiteral(Literal.newBuilder().setEnumMember(member)).build()

  private fun intArg(value: Long, width: Int): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setInteger(value))
      .setType(bitType(width))
      .build()

  /**
   * Builds a minimal v1model [BehavioralConfig].
   *
   * The pipeline has: parser -> verify_checksum -> ingress ([ingressStmts]) -> egress
   * ([egressStmts]) -> compute_checksum -> deparser. All stages default to no-op.
   */
  private fun v1modelConfig(
    ingressStmts: List<Stmt> = emptyList(),
    egressStmts: List<Stmt> = emptyList(),
    parser: ParserDecl = noopParser,
    smType: TypeDecl = standardMetaType,
    ingressLocalVars: List<VarDecl> = emptyList(),
  ): BehavioralConfig {
    fun control(name: String, stmts: List<Stmt>, localVars: List<VarDecl> = emptyList()) =
      ControlDecl.newBuilder()
        .setName(name)
        .addAllParams(controlParams)
        .addAllLocalVars(localVars)
        .addAllApplyBody(stmts)
        .build()

    return BehavioralConfig.newBuilder()
      .setArchitecture(v1modelArch)
      .addTypes(smType)
      .addTypes(headersType)
      .addTypes(metaType)
      .addParsers(parser)
      .addControls(noopControl("MyVerifyChecksum"))
      .addControls(control("MyIngress", ingressStmts, ingressLocalVars))
      .addControls(control("MyEgress", egressStmts))
      .addControls(noopControl("MyComputeChecksum"))
      .addControls(noopControl("MyDeparser"))
      .build()
  }

  /** Convenience overload: ingress-only statements. */
  private fun v1modelConfig(vararg stmts: Stmt): BehavioralConfig =
    v1modelConfig(ingressStmts = stmts.toList())

  private fun writeCloneSession(store: TableStore, sessionId: Int, egressPort: Int) {
    store.write(
      P4RuntimeOuterClass.Update.newBuilder()
        .setType(P4RuntimeOuterClass.Update.Type.INSERT)
        .setEntity(
          P4RuntimeOuterClass.Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setCloneSessionEntry(
                  P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                    .setSessionId(sessionId)
                    .addReplicas(P4RuntimeOuterClass.Replica.newBuilder().setEgressPort(egressPort))
                )
            )
        )
        .build()
    )
  }

  private fun writeMulticastGroup(store: TableStore, groupId: Int, replicas: List<Pair<Int, Int>>) {
    store.write(
      P4RuntimeOuterClass.Update.newBuilder()
        .setType(P4RuntimeOuterClass.Update.Type.INSERT)
        .setEntity(
          P4RuntimeOuterClass.Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setMulticastGroupEntry(
                  P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                    .setMulticastGroupId(groupId)
                    .addAllReplicas(
                      replicas.map { (rid, port) ->
                        P4RuntimeOuterClass.Replica.newBuilder()
                          .setInstance(rid)
                          .setEgressPort(port)
                          .build()
                      }
                    )
                )
            )
        )
        .build()
    )
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `multicast fork produces output packets for each replica`() {
    val config = v1modelConfig(assignField("sm", "mcast_grp", 1, 16))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 2, 0 to 3))

    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val result = V1ModelArchitecture().processPacket(0u, payload, config, tableStore)
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(2, outputs.size)
    assertEquals(2, outputs[0].egressPort)
    assertEquals(3, outputs[1].egressPort)
    // Payload should pass through unchanged (no parser extraction, no deparser emit).
    assertEquals(ByteString.copyFrom(payload), outputs[0].payload)
    assertEquals(ByteString.copyFrom(payload), outputs[1].payload)
  }

  @Test
  fun `unicast packet emits on egress_spec port`() {
    val config =
      v1modelConfig(assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val payload = byteArrayOf(0x01, 0x02, 0x03)
    val result = V1ModelArchitecture().processPacket(0u, payload, config, TableStore())
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].egressPort)
    assertEquals(ByteString.copyFrom(payload), outputs[0].payload)
  }

  @Test
  fun `mark_to_drop produces no output packets`() {
    val config =
      v1modelConfig(
        assignField(
          "sm",
          "egress_spec",
          V1ModelArchitecture.DEFAULT_DROP_PORT.toLong(),
          V1ModelArchitecture.DEFAULT_PORT_BITS,
        )
      )
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `traffic manager drop skips egress`() {
    // When egress_spec is the drop port after ingress, the traffic manager drops the packet
    // before egress runs. Verify that no egress/compute_checksum stage events appear.
    val config =
      v1modelConfig(
        assignField(
          "sm",
          "egress_spec",
          V1ModelArchitecture.DEFAULT_DROP_PORT.toLong(),
          V1ModelArchitecture.DEFAULT_PORT_BITS,
        )
      )
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    val stageNames =
      result.trace.eventsList.filter { it.hasPipelineStage() }.map { it.pipelineStage.stageName }
    assertTrue("egress should not appear in trace", "egress" !in stageNames)
    assertTrue("compute_checksum should not appear in trace", "compute_checksum" !in stageNames)
    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `execute_meter does not affect forwarding`() {
    // Meters always return GREEN in the simulator (no real packet rates).
    // Verify the pipeline still forwards normally after a meter call.
    val config =
      v1modelConfig(
        ingressLocalVars =
          listOf(VarDecl.newBuilder().setName("color").setType(bitType(8)).build()),
        ingressStmts =
          listOf(
            methodCallStmt("my_meter", "execute_meter", bit(0, 32), nameRef("color", bitType(8))),
            assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
      )
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].egressPort)
  }

  @Test
  fun `multicast fork trace tree has fork node`() {
    val config = v1modelConfig(assignField("sm", "mcast_grp", 1, 16))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 2, 0 to 3))

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.MULTICAST, result.trace.forkOutcome.reason)
    assertEquals(2, result.trace.forkOutcome.branchesCount)
  }

  @Test
  fun `I2E clone forks into original and clone branch`() {
    // Ingress calls clone(I2E, session=1), sets egress_spec=2 for the original.
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7)

    val payload = byteArrayOf(0xAA.toByte())
    val result = V1ModelArchitecture().processPacket(0u, payload, config, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(2, branches.size)
    assertEquals("original", branches[0].label)
    assertEquals("clone", branches[1].label)

    val outputs = collectOutputsFromTrace(result.trace)
    assertEquals(2, outputs.size)
    // Original branch uses egress_spec set by ingress.
    assertEquals(2, outputs[0].egressPort)
    // Clone branch uses the clone session's egress port.
    assertEquals(7, outputs[1].egressPort)
  }

  @Test
  fun `E2E clone forks into original and clone branch`() {
    // Egress calls clone(E2E, session=1); ingress sets egress_spec=3 for routing.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts = listOf(externCall("clone", enumArg("E2E"), intArg(1, 32))),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 8)

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(2, branches.size)
    assertEquals("original", branches[0].label)
    assertEquals("clone", branches[1].label)

    val outputs = collectOutputsFromTrace(result.trace)
    assertEquals(2, outputs.size)
    assertEquals(3, outputs[0].egressPort)
    assertEquals(8, outputs[1].egressPort)
  }

  @Test
  fun `resubmit forks and re-enters ingress`() {
    // Ingress calls resubmit() only on the first pass (instance_type == 0).
    // The resubmit branch gets instance_type=6 (RESUBMIT), so it won't re-trigger.
    val config =
      v1modelConfig(
        ifFieldEquals("sm", "instance_type", 0, 32, externCall("resubmit", intArg(0, 8)))
      )

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.RESUBMIT, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(1, branches.size)
    assertEquals("resubmit", branches[0].label)
  }

  @Test
  fun `recirculate forks after deparser`() {
    // Egress calls recirculate() only on the first pass (instance_type == 0).
    // The recirculated branch gets instance_type=4, so it won't re-trigger.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts =
          listOf(
            ifFieldEquals("sm", "instance_type", 0, 32, externCall("recirculate", intArg(0, 8)))
          ),
      )

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.RECIRCULATE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(1, branches.size)
    assertEquals("recirculate", branches[0].label)
  }

  @Test
  fun `I2E clone with missing session is silently ignored`() {
    // Clone session 99 is never installed — BMv2 silently ignores the clone.
    // No fork appears; the packet outputs normally.
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(99, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertFalse(result.trace.hasForkOutcome())
    val outputs = collectOutputsFromTrace(result.trace)
    assertEquals(1, outputs.size)
    assertEquals(2, outputs[0].egressPort)
  }

  @Test
  fun `E2E clone with missing session is silently ignored`() {
    // Clone session 99 is never installed — BMv2 silently ignores the clone.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts = listOf(externCall("clone", enumArg("E2E"), intArg(99, 32))),
      )

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertFalse(result.trace.hasForkOutcome())
    val outputs = collectOutputsFromTrace(result.trace)
    assertEquals(1, outputs.size)
    assertEquals(3, outputs[0].egressPort)
  }

  @Test
  fun `unknown multicast group falls through to unicast`() {
    // mcast_grp is set but the group isn't installed — BMv2 treats this as unicast/drop.
    val config =
      v1modelConfig(
        assignField("sm", "mcast_grp", 42, 16),
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    // No fork — falls through to unicast path.
    val outputs = collectOutputsFromTrace(result.trace)
    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].egressPort)
  }

  // ---------------------------------------------------------------------------
  // Stale egress_spec tests — verify mark_to_drop() from ingress or a prior
  // egress run doesn't leak through to replicas, I2E clones, or E2E clones.
  // ---------------------------------------------------------------------------

  /** mark_to_drop() call as an ingress statement. */
  private val markToDrop: Stmt =
    externCall(
      "mark_to_drop",
      Expr.newBuilder().setNameRef(NameRef.newBuilder().setName("sm")).build(),
    )

  @Test
  fun `multicast replicas survive ingress mark_to_drop`() {
    // Ingress calls mark_to_drop(), then sets mcast_grp. Replicas must still be
    // forwarded — the post-egress drop check should only trigger on mark_to_drop()
    // called during egress, not on stale ingress state.
    val config = v1modelConfig(markToDrop, assignField("sm", "mcast_grp", 1, 16))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 2, 0 to 3))

    val result =
      V1ModelArchitecture().processPacket(0u, byteArrayOf(0xAA.toByte()), config, tableStore)
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(2, outputs.size)
    assertEquals(2, outputs[0].egressPort)
    assertEquals(3, outputs[1].egressPort)
  }

  @Test
  fun `I2E clone survives ingress mark_to_drop on original`() {
    // Ingress calls clone(I2E) then mark_to_drop(). The original should be dropped
    // (egress_spec == drop port), but the clone branch should still forward.
    val config = v1modelConfig(externCall("clone", enumArg("I2E"), intArg(1, 32)), markToDrop)
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7)

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    val outputs = collectOutputsFromTrace(result.trace)
    // Original is dropped (mark_to_drop set egress_spec to drop port).
    // Clone survives on port 7.
    assertEquals(1, outputs.size)
    assertEquals(7, outputs[0].egressPort)
  }

  @Test
  fun `E2E clone survives egress mark_to_drop on original`() {
    // Egress calls clone(E2E) then mark_to_drop(). The original should be dropped,
    // but the clone's second egress run should start with a clean egress_spec.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts =
          listOf(
            // Only clone + drop on the first egress pass (instance_type == 0).
            ifFieldEquals(
              "sm",
              "instance_type",
              0,
              32,
              externCall("clone", enumArg("E2E"), intArg(1, 32)),
            ),
            ifFieldEquals("sm", "instance_type", 0, 32, markToDrop),
          ),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 8)

    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    val outputs = collectOutputsFromTrace(result.trace)
    // Original is dropped (egress mark_to_drop). Clone survives on port 8.
    assertEquals(1, outputs.size)
    assertEquals(8, outputs[0].egressPort)
  }

  // ---------------------------------------------------------------------------
  // Stage event tests
  // ---------------------------------------------------------------------------

  /** Extracts only PacketIngressEvent and PipelineStageEvent from a trace's events. */
  private fun stageEvents(tree: TraceTree): List<TraceEvent> =
    tree.eventsList.filter { it.hasPacketIngress() || it.hasPipelineStage() }

  @Test
  fun `trace starts with packet ingress and has enter-exit pairs for all stages`() {
    val config =
      v1modelConfig(assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val result = V1ModelArchitecture().processPacket(7u, byteArrayOf(0x01), config, TableStore())
    val events = stageEvents(result.trace)

    // First event: packet ingress with correct port.
    assertTrue(events[0].hasPacketIngress())
    assertEquals(7, events[0].packetIngress.ingressPort)

    // Remaining events: enter/exit pairs for parser, 4 controls, deparser.
    data class StageStep(val name: String, val kind: StageKind, val direction: Direction)

    val stages =
      events.drop(1).map {
        StageStep(
          it.pipelineStage.stageName,
          it.pipelineStage.stageKind,
          it.pipelineStage.direction,
        )
      }
    val expected =
      listOf(
        StageStep("parser", StageKind.PARSER, Direction.ENTER),
        StageStep("parser", StageKind.PARSER, Direction.EXIT),
        StageStep("verify_checksum", StageKind.CONTROL, Direction.ENTER),
        StageStep("verify_checksum", StageKind.CONTROL, Direction.EXIT),
        StageStep("ingress", StageKind.CONTROL, Direction.ENTER),
        StageStep("ingress", StageKind.CONTROL, Direction.EXIT),
        StageStep("egress", StageKind.CONTROL, Direction.ENTER),
        StageStep("egress", StageKind.CONTROL, Direction.EXIT),
        StageStep("compute_checksum", StageKind.CONTROL, Direction.ENTER),
        StageStep("compute_checksum", StageKind.CONTROL, Direction.EXIT),
        StageStep("deparser", StageKind.DEPARSER, Direction.ENTER),
        StageStep("deparser", StageKind.DEPARSER, Direction.EXIT),
      )
    assertEquals(expected, stages)
  }

  @Test
  fun `parser exit emits EXIT event before drop`() {
    // Parser with an exit statement — triggers ExitException in the parser.
    val exitParser =
      ParserDecl.newBuilder()
        .setName("MyParser")
        .addAllParams(parserParams)
        .addStates(
          ParserState.newBuilder()
            .setName("start")
            .addStmts(Stmt.newBuilder().setExit(ExitStmt.getDefaultInstance()))
            .setTransition(Transition.newBuilder().setNextState("accept"))
        )
        .build()

    val config = v1modelConfig(parser = exitParser)
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    // Should be a drop.
    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
    assertEquals(DropReason.MARK_TO_DROP, result.trace.packetOutcome.drop.reason)

    // Parser EXIT event must be present even though the parser exited early.
    val events = stageEvents(result.trace)
    val stages = events.drop(1).map { it.pipelineStage }
    assertEquals(
      listOf(StageKind.PARSER to Direction.ENTER, StageKind.PARSER to Direction.EXIT),
      stages.map { it.stageKind to it.direction },
    )
  }

  // ---------------------------------------------------------------------------
  // Wider port width tests (Track 5: architecture customization)
  // ---------------------------------------------------------------------------

  /** Builds a standard_metadata_t type with a custom port bit width. */
  private fun standardMetaTypeWithPortWidth(portBits: Int): TypeDecl =
    TypeDecl.newBuilder()
      .setName("standard_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("ingress_port", portBits))
          .addFields(field("egress_spec", portBits))
          .addFields(field("egress_port", portBits))
          .addFields(field("instance_type", 32))
          .addFields(field("packet_length", 32))
          .addFields(field("mcast_grp", 16))
          .addFields(field("egress_rid", 16))
          .addFields(field("checksum_error", 1))
          .addFields(field("parser_error", 32))
      )
      .build()

  /** Builds a v1model config with a custom port width and ingress statements. */
  private fun widePortConfig(portBits: Int, vararg stmts: Stmt): BehavioralConfig =
    v1modelConfig(ingressStmts = stmts.toList(), smType = standardMetaTypeWithPortWidth(portBits))

  @Test
  fun `wider port width unicast works with large port numbers`() {
    val portBits = 16
    val largePort = 1000L // beyond bit<9> range
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", largePort, portBits))
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(1, outputs.size)
    assertEquals(largePort.toInt(), outputs[0].egressPort)
  }

  @Test
  fun `wider port width drop uses correct all-ones value`() {
    val portBits = 16
    val dropPort = (1L shl portBits) - 1 // 65535, not 511
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", dropPort, portBits))
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `wider port width does not drop on standard drop port value`() {
    // Port 511 is NOT the drop port when port width is 16 bits — it's a valid port.
    val portBits = 16
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", 511, portBits))
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(1, outputs.size)
    assertEquals(511, outputs[0].egressPort)
  }

  @Test
  fun `wider port mark_to_drop sets correct all-ones value`() {
    // Exercises the Interpreter's mark_to_drop path (which derives drop value from the struct's
    // egress_spec width independently) and the Architecture's drop detection (which uses
    // PipelineState.dropPort derived from ingress_port width). Both must agree.
    val portBits = 16
    val markToDrop =
      externCall(
        "mark_to_drop",
        Expr.newBuilder().setNameRef(NameRef.newBuilder().setName("sm")).build(),
      )
    val config = widePortConfig(portBits, markToDrop)
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `32-bit port width works without overflow`() {
    val portBits = 32
    val largePort = 100_000L
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", largePort, portBits))
    val result = V1ModelArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(1, outputs.size)
    assertEquals(largePort.toInt(), outputs[0].egressPort)
  }
}
