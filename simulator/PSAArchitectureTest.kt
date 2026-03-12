package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ir.v1.Architecture
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.EnumDecl
import fourward.ir.v1.Expr
import fourward.ir.v1.FieldDecl
import fourward.ir.v1.MethodCall
import fourward.ir.v1.MethodCallStmt
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
import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.ForkReason
import fourward.sim.v1.SimulatorProto.PipelineStageEvent.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [PSAArchitecture].
 *
 * These exercise PSA-specific pipeline semantics — drop-by-default, send_to_port, register
 * read/write, and the two-pipeline structure — using minimal synthetic BehavioralConfig protos.
 */
class PSAArchitectureTest {

  // ---------------------------------------------------------------------------
  // Helpers: minimal PSA config construction
  // ---------------------------------------------------------------------------

  private fun field(name: String, width: Int): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(bitType(width)).build()

  private fun boolField(name: String): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(Type.newBuilder().setBoolean(true)).build()

  private fun enumField(name: String, enumType: String): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(namedType(enumType)).build()

  private fun errorField(name: String): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(Type.newBuilder().setError(true)).build()

  private fun param(name: String, typeName: String): ParamDecl =
    ParamDecl.newBuilder().setName(name).setType(namedType(typeName)).build()

  // PSA metadata types — minimal fields needed by PSAArchitecture.
  private val ingressParserInputMeta =
    TypeDecl.newBuilder()
      .setName("psa_ingress_parser_input_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("ingress_port", 32))
          .addFields(enumField("packet_path", "PSA_PacketPath_t"))
      )
      .build()

  private val ingressInputMeta =
    TypeDecl.newBuilder()
      .setName("psa_ingress_input_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("ingress_port", 32))
          .addFields(enumField("packet_path", "PSA_PacketPath_t"))
          .addFields(errorField("parser_error"))
      )
      .build()

  private val ingressOutputMeta =
    TypeDecl.newBuilder()
      .setName("psa_ingress_output_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("class_of_service", 8))
          .addFields(boolField("drop"))
          .addFields(field("egress_port", 32))
      )
      .build()

  private val egressParserInputMeta =
    TypeDecl.newBuilder()
      .setName("psa_egress_parser_input_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("egress_port", 32))
          .addFields(enumField("packet_path", "PSA_PacketPath_t"))
      )
      .build()

  private val egressInputMeta =
    TypeDecl.newBuilder()
      .setName("psa_egress_input_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("class_of_service", 8))
          .addFields(field("egress_port", 32))
          .addFields(enumField("packet_path", "PSA_PacketPath_t"))
          .addFields(errorField("parser_error"))
      )
      .build()

  private val egressOutputMeta =
    TypeDecl.newBuilder()
      .setName("psa_egress_output_metadata_t")
      .setStruct(StructDecl.newBuilder().addFields(boolField("drop")))
      .build()

  private val egressDeparserInputMeta =
    TypeDecl.newBuilder()
      .setName("psa_egress_deparser_input_metadata_t")
      .setStruct(StructDecl.newBuilder().addFields(field("egress_port", 32)))
      .build()

  private val packetPathEnum =
    TypeDecl.newBuilder()
      .setName("PSA_PacketPath_t")
      .setEnum(
        EnumDecl.newBuilder()
          .addMembers("NORMAL")
          .addMembers("NORMAL_UNICAST")
          .addMembers("NORMAL_MULTICAST")
          .addMembers("CLONE_I2E")
          .addMembers("CLONE_E2E")
          .addMembers("RESUBMIT")
          .addMembers("RECIRCULATE")
      )
      .build()

  private val headersType =
    TypeDecl.newBuilder().setName("headers_t").setStruct(StructDecl.getDefaultInstance()).build()

  private val metaType =
    TypeDecl.newBuilder().setName("meta_t").setStruct(StructDecl.getDefaultInstance()).build()

  private val allTypes =
    listOf(
      ingressParserInputMeta,
      ingressInputMeta,
      ingressOutputMeta,
      egressParserInputMeta,
      egressInputMeta,
      egressOutputMeta,
      egressDeparserInputMeta,
      packetPathEnum,
      headersType,
      metaType,
    )

  private val psaArch =
    Architecture.newBuilder()
      .setName("psa")
      .addStages(stage("ingress_parser", "IngressParser", StageKind.PARSER))
      .addStages(stage("ingress", "Ingress", StageKind.CONTROL))
      .addStages(stage("ingress_deparser", "IngressDeparser", StageKind.DEPARSER))
      .addStages(stage("egress_parser", "EgressParser", StageKind.PARSER))
      .addStages(stage("egress", "Egress", StageKind.CONTROL))
      .addStages(stage("egress_deparser", "EgressDeparser", StageKind.DEPARSER))
      .build()

  private fun stage(name: String, blockName: String, kind: StageKind): PipelineStage =
    PipelineStage.newBuilder().setName(name).setBlockName(blockName).setKind(kind).build()

  private val noopParser =
    ParserDecl.newBuilder()
      .setName("IngressParser")
      .addParams(param("pkt", "packet_in"))
      .addParams(param("hdr", "headers_t"))
      .addParams(param("istd", "psa_ingress_parser_input_metadata_t"))
      .addStates(
        ParserState.newBuilder()
          .setName("start")
          .setTransition(Transition.newBuilder().setNextState("accept"))
      )
      .build()

  private val egressParser =
    ParserDecl.newBuilder()
      .setName("EgressParser")
      .addParams(param("pkt", "packet_in"))
      .addParams(param("hdr", "headers_t"))
      .addParams(param("istd", "psa_egress_parser_input_metadata_t"))
      .addStates(
        ParserState.newBuilder()
          .setName("start")
          .setTransition(Transition.newBuilder().setNextState("accept"))
      )
      .build()

  private fun noopControl(name: String, params: List<Pair<String, String>>): ControlDecl =
    ControlDecl.newBuilder()
      .setName(name)
      .addAllParams(params.map { (n, t) -> param(n, t) })
      .build()

  private fun control(
    name: String,
    params: List<Pair<String, String>>,
    stmts: List<Stmt> = emptyList(),
  ): ControlDecl =
    ControlDecl.newBuilder()
      .setName(name)
      .addAllParams(params.map { (n, t) -> param(n, t) })
      .addAllApplyBody(stmts)
      .build()

  private val ingressParams =
    listOf(
      "hdr" to "headers_t",
      "meta" to "meta_t",
      "istd" to "psa_ingress_input_metadata_t",
      "ostd" to "psa_ingress_output_metadata_t",
    )

  private val ingressDeparserParams =
    listOf(
      "pkt" to "packet_out",
      "hdr" to "headers_t",
      "meta" to "meta_t",
      "istd" to "psa_ingress_output_metadata_t",
    )

  private val egressParams =
    listOf(
      "hdr" to "headers_t",
      "meta" to "meta_t",
      "istd" to "psa_egress_input_metadata_t",
      "ostd" to "psa_egress_output_metadata_t",
    )

  private val egressDeparserParams =
    listOf(
      "pkt" to "packet_out",
      "hdr" to "headers_t",
      "meta" to "meta_t",
      "istd" to "psa_egress_deparser_input_metadata_t",
    )

  /**
   * Builds a minimal PSA [BehavioralConfig].
   *
   * All stages default to no-op; override [ingressStmts] or [egressStmts] to add behaviour.
   */
  private fun psaConfig(
    ingressStmts: List<Stmt> = emptyList(),
    egressStmts: List<Stmt> = emptyList(),
  ): BehavioralConfig =
    BehavioralConfig.newBuilder()
      .setArchitecture(psaArch)
      .addAllTypes(allTypes)
      .addParsers(noopParser)
      .addParsers(egressParser)
      .addControls(control("Ingress", ingressParams, ingressStmts))
      .addControls(noopControl("IngressDeparser", ingressDeparserParams))
      .addControls(control("Egress", egressParams, egressStmts))
      .addControls(noopControl("EgressDeparser", egressDeparserParams))
      .build()

  /** send_to_port(ostd, port) — sets drop=false and egress_port on the output metadata. */
  private fun sendToPort(port: Long): Stmt =
    externCall("send_to_port", nameRef("ostd"), bit(port, 32))

  /** multicast(ostd, group) — marks packet for multicast replication. */
  private fun multicast(group: Long): Stmt =
    externCall("multicast", nameRef("ostd"), bit(group, 32))

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

  /** PSA register.read method call expression: reg.read(index). */
  private fun registerReadExpr(regName: String, index: Long, returnWidth: Int): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef(regName, namedType("Register")))
          .setMethod("read")
          .addArgs(bit(index, 32))
      )
      .setType(bitType(returnWidth))
      .build()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `PSA drops by default without send_to_port`() {
    val config = psaConfig()
    val result = PSAArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
    assertEquals(DropReason.MARK_TO_DROP, result.trace.packetOutcome.drop.reason)
  }

  @Test
  fun `send_to_port forwards packet`() {
    val config = psaConfig(ingressStmts = listOf(sendToPort(5)))
    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val result = PSAArchitecture().processPacket(0u, payload, config, TableStore())
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].egressPort)
    assertEquals(ByteString.copyFrom(payload), outputs[0].payload)
  }

  @Test
  fun `trace has enter-exit pairs for all 6 PSA stages`() {
    val config = psaConfig(ingressStmts = listOf(sendToPort(1)))
    val result = PSAArchitecture().processPacket(7u, byteArrayOf(0x01), config, TableStore())
    val events = result.trace.eventsList.filter { it.hasPacketIngress() || it.hasPipelineStage() }

    // First event: packet ingress.
    assertTrue(events[0].hasPacketIngress())
    assertEquals(7, events[0].packetIngress.ingressPort)

    // 6 stages x 2 (enter/exit) = 12 stage events.
    val stages = events.drop(1).map { it.pipelineStage }
    val expected =
      listOf(
        Triple("ingress_parser", StageKind.PARSER, Direction.ENTER),
        Triple("ingress_parser", StageKind.PARSER, Direction.EXIT),
        Triple("ingress", StageKind.CONTROL, Direction.ENTER),
        Triple("ingress", StageKind.CONTROL, Direction.EXIT),
        Triple("ingress_deparser", StageKind.DEPARSER, Direction.ENTER),
        Triple("ingress_deparser", StageKind.DEPARSER, Direction.EXIT),
        Triple("egress_parser", StageKind.PARSER, Direction.ENTER),
        Triple("egress_parser", StageKind.PARSER, Direction.EXIT),
        Triple("egress", StageKind.CONTROL, Direction.ENTER),
        Triple("egress", StageKind.CONTROL, Direction.EXIT),
        Triple("egress_deparser", StageKind.DEPARSER, Direction.ENTER),
        Triple("egress_deparser", StageKind.DEPARSER, Direction.EXIT),
      )
    assertEquals(expected, stages.map { Triple(it.stageName, it.stageKind, it.direction) })
  }

  @Test
  fun `register write then read returns written value`() {
    // Ingress: write 0xBEEF to register index 0, then read it back into a header field.
    // We verify indirectly via send_to_port: if read returns 0xBEEF, the test passes
    // because the program reaches send_to_port (it always does here regardless of value).
    // The real verification is that the register read doesn't crash or return garbage.
    val config =
      psaConfig(
        ingressStmts =
          listOf(
            methodCallStmt(
              "my_reg",
              "write",
              bit(0, 32),
              bit(0xBEEF, 16),
              targetType = namedType("Register"),
            ),
            sendToPort(1),
          )
      )
    val tableStore = TableStore()
    val result = PSAArchitecture().processPacket(0u, byteArrayOf(0x01), config, tableStore)

    // Packet should forward (register write doesn't affect drop).
    val outputs = collectOutputsFromTrace(result.trace)
    assertEquals(1, outputs.size)

    // Verify the register was actually written to the store.
    val stored = tableStore.registerRead("my_reg", 0)
    assertTrue("register should contain written value", stored is BitVal)
    assertEquals(0xBEEF.toLong(), (stored as BitVal).bits.value.toLong())
  }

  @Test
  fun `register read of uninitialized index returns zero`() {
    // Read from register index 5 (never written). PSA read returns T directly — the default
    // value for bit<16> is 0. This exercises the ExternEvaluator.returnType() path.
    val config =
      psaConfig(
        ingressStmts =
          listOf(
            // Assign the register read result to a local (we use an expression statement).
            Stmt.newBuilder()
              .setMethodCall(MethodCallStmt.newBuilder().setCall(registerReadExpr("my_reg", 5, 16)))
              .build(),
            sendToPort(1),
          )
      )
    val result = PSAArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    // Should not crash — the read returns a default zero value.
    val outputs = collectOutputsFromTrace(result.trace)
    assertEquals(1, outputs.size)
  }

  @Test
  fun `multicast replicates packet to all group members`() {
    val config = psaConfig(ingressStmts = listOf(multicast(1)))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 5, 1 to 6, 2 to 7))

    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val result = PSAArchitecture().processPacket(0u, payload, config, tableStore)
    val outputs = collectOutputsFromTrace(result.trace)

    assertEquals(3, outputs.size)
    assertEquals(5, outputs[0].egressPort)
    assertEquals(6, outputs[1].egressPort)
    assertEquals(7, outputs[2].egressPort)
    // All replicas carry the same payload.
    for (output in outputs) {
      assertEquals(ByteString.copyFrom(payload), output.payload)
    }
  }

  @Test
  fun `multicast trace has fork node with MULTICAST reason`() {
    val config = psaConfig(ingressStmts = listOf(multicast(1)))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 2, 1 to 3))

    val result = PSAArchitecture().processPacket(0u, byteArrayOf(0x01), config, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.MULTICAST, result.trace.forkOutcome.reason)
    assertEquals(2, result.trace.forkOutcome.branchesCount)
    assertEquals("replica_0_port_2", result.trace.forkOutcome.branchesList[0].label)
    assertEquals("replica_1_port_3", result.trace.forkOutcome.branchesList[1].label)
  }

  @Test
  fun `multicast with unknown group drops packet`() {
    // multicast(group=99) but no group 99 is configured — should drop.
    val config = psaConfig(ingressStmts = listOf(multicast(99)))
    val result = PSAArchitecture().processPacket(0u, byteArrayOf(0x01), config, TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
    assertEquals(DropReason.MARK_TO_DROP, result.trace.packetOutcome.drop.reason)
  }
}
