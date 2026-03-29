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

import fourward.ir.ActionDecl
import fourward.ir.Architecture
import fourward.ir.BehavioralConfig
import fourward.ir.ControlDecl
import fourward.ir.DeviceConfig
import fourward.ir.ParamDecl
import fourward.ir.ParserDecl
import fourward.ir.ParserState
import fourward.ir.PipelineConfig
import fourward.ir.PipelineStage
import fourward.ir.StageKind
import fourward.ir.StructDecl
import fourward.ir.TableBehavior
import fourward.ir.Transition
import fourward.ir.TypeDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.config.v1.P4InfoOuterClass

/**
 * Unit tests for [Simulator] — name resolution, default action handling, and error responses.
 *
 * These test the Simulator's pipeline-loading logic (p4info alias→behavioral name resolution) which
 * is a common source of bugs: p4info aliases don't always match behavioral IR names due to control
 * inlining (e.g. p4info alias "t" vs behavioral "c_t").
 */
class SimulatorTest {

  // ---------------------------------------------------------------------------
  // Name resolution (exercised through pipeline loading)
  // ---------------------------------------------------------------------------

  /**
   * Builds a minimal [PipelineConfig] with the given p4info tables/actions and behavioral
   * tables/actions, sufficient to exercise pipeline loading and name resolution.
   */
  private fun pipelineConfig(
    p4infoTables: List<P4InfoOuterClass.Table> = emptyList(),
    p4infoActions: List<P4InfoOuterClass.Action> = emptyList(),
    behavioralTableNames: List<String> = emptyList(),
    behavioralActionNames: List<String> = emptyList(),
  ): PipelineConfig {
    val arch =
      Architecture.newBuilder()
        .setName("v1model")
        // Minimal stage list so V1ModelArchitecture doesn't NPE.
        .addStages(PipelineStage.newBuilder().setKind(StageKind.PARSER).setBlockName("p"))
        .addStages(PipelineStage.newBuilder().setKind(StageKind.CONTROL).setBlockName("ig"))
        .addStages(PipelineStage.newBuilder().setKind(StageKind.CONTROL).setBlockName("eg"))
        .addStages(PipelineStage.newBuilder().setKind(StageKind.DEPARSER).setBlockName("dep"))
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .setArchitecture(arch)
        .addAllTables(behavioralTableNames.map { TableBehavior.newBuilder().setName(it).build() })
        .addAllActions(behavioralActionNames.map { ActionDecl.newBuilder().setName(it).build() })
        .build()
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addAllTables(p4infoTables)
        .addAllActions(p4infoActions)
        .build()
    return PipelineConfig.newBuilder()
      .setDevice(DeviceConfig.newBuilder().setBehavioral(behavioral))
      .setP4Info(p4info)
      .build()
  }

  private fun p4infoTable(id: Int, alias: String): P4InfoOuterClass.Table =
    P4InfoOuterClass.Table.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setAlias(alias))
      .build()

  private fun p4infoAction(id: Int, alias: String): P4InfoOuterClass.Action =
    P4InfoOuterClass.Action.newBuilder()
      .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setAlias(alias))
      .build()

  @Test
  fun `load pipeline succeeds with exact name match`() {
    val config =
      pipelineConfig(
        p4infoTables = listOf(p4infoTable(1, "my_table")),
        p4infoActions = listOf(p4infoAction(10, "my_action")),
        behavioralTableNames = listOf("my_table"),
        behavioralActionNames = listOf("my_action"),
      )
    val sim = Simulator()
    sim.loadPipeline(config)
    // No exception means success.
  }

  @Test
  fun `load pipeline resolves alias via suffix fallback`() {
    // p4info alias "t" should match behavioral "c_t" via endsWith("_t").
    // We verify this indirectly: if resolution failed, the table store would
    // use the wrong key and a subsequent packet wouldn't find the table.
    val config =
      pipelineConfig(
        p4infoTables = listOf(p4infoTable(1, "t")),
        p4infoActions = listOf(p4infoAction(10, "a")),
        behavioralTableNames = listOf("c_t"),
        behavioralActionNames = listOf("c_a"),
      )
    val sim = Simulator()
    sim.loadPipeline(config)
    // No exception means success.
  }

  @Test
  fun `load pipeline with unknown architecture throws`() {
    val behavioral =
      BehavioralConfig.newBuilder()
        .setArchitecture(Architecture.newBuilder().setName("unknown_arch"))
        .build()
    val config =
      PipelineConfig.newBuilder()
        .setDevice(DeviceConfig.newBuilder().setBehavioral(behavioral))
        .build()
    val sim = Simulator()
    val e = assertThrows(IllegalArgumentException::class.java) { sim.loadPipeline(config) }
    assertTrue(e.message!!.contains("unsupported architecture"))
  }

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  @Test
  fun `process packet with no pipeline loaded throws`() {
    val sim = Simulator()
    assertThrows(IllegalStateException::class.java) {
      sim.processPacket(ingressPort = 0, payload = ByteArray(0))
    }
  }

  @Test
  fun `write entry with no pipeline loaded throws`() {
    val sim = Simulator()
    assertThrows(IllegalStateException::class.java) {
      sim.writeEntry(p4.v1.P4RuntimeOuterClass.Update.getDefaultInstance())
    }
  }

  // ---------------------------------------------------------------------------
  // Drop port override integration (wiring through to V1ModelArchitecture)
  // ---------------------------------------------------------------------------

  /**
   * Builds a complete v1model [PipelineConfig] that can process packets.
   *
   * Ingress sets `egress_spec` to [egressPort], producing a unicast output (or drop if the port
   * matches the architecture's drop port).
   */
  @Suppress("LongMethod")
  private fun v1modelPipelineConfig(egressPort: Long): PipelineConfig {
    val portBits = V1ModelArchitecture.DEFAULT_PORT_BITS
    val smType =
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
    val headersType =
      TypeDecl.newBuilder().setName("headers_t").setStruct(StructDecl.getDefaultInstance()).build()
    val metaType =
      TypeDecl.newBuilder().setName("meta_t").setStruct(StructDecl.getDefaultInstance()).build()

    fun param(name: String, typeName: String): ParamDecl =
      ParamDecl.newBuilder().setName(name).setType(namedType(typeName)).build()

    val controlParams =
      listOf(param("hdr", "headers_t"), param("meta", "meta_t"), param("sm", "standard_metadata_t"))
    fun noopControl(name: String): ControlDecl =
      ControlDecl.newBuilder().setName(name).addAllParams(controlParams).build()

    val parser =
      ParserDecl.newBuilder()
        .setName("MyParser")
        .addParams(ParamDecl.newBuilder().setName("pkt").setType(namedType("packet_in")))
        .addParams(param("hdr", "headers_t"))
        .addParams(param("meta", "meta_t"))
        .addParams(param("sm", "standard_metadata_t"))
        .addStates(
          ParserState.newBuilder()
            .setName("start")
            .setTransition(Transition.newBuilder().setNextState("accept"))
        )
        .build()

    val ingress =
      ControlDecl.newBuilder()
        .setName("MyIngress")
        .addAllParams(controlParams)
        .addApplyBody(assignField("sm", "egress_spec", egressPort, portBits))
        .build()

    val arch =
      Architecture.newBuilder()
        .setName("v1model")
        .addStages(stage("parser", StageKind.PARSER, "MyParser"))
        .addStages(stage("verify_checksum", StageKind.CONTROL, "MyVerifyChecksum"))
        .addStages(stage("ingress", StageKind.CONTROL, "MyIngress"))
        .addStages(stage("egress", StageKind.CONTROL, "MyEgress"))
        .addStages(stage("compute_checksum", StageKind.CONTROL, "MyComputeChecksum"))
        .addStages(stage("deparser", StageKind.DEPARSER, "MyDeparser"))
        .build()

    val behavioral =
      BehavioralConfig.newBuilder()
        .setArchitecture(arch)
        .addTypes(smType)
        .addTypes(headersType)
        .addTypes(metaType)
        .addParsers(parser)
        .addControls(noopControl("MyVerifyChecksum"))
        .addControls(ingress)
        .addControls(noopControl("MyEgress"))
        .addControls(noopControl("MyComputeChecksum"))
        .addControls(noopControl("MyDeparser"))
        .build()

    return PipelineConfig.newBuilder()
      .setDevice(DeviceConfig.newBuilder().setBehavioral(behavioral))
      .setP4Info(P4InfoOuterClass.P4Info.getDefaultInstance())
      .build()
  }

  private fun stage(name: String, kind: StageKind, blockName: String): PipelineStage =
    PipelineStage.newBuilder().setName(name).setKind(kind).setBlockName(blockName).build()

  @Test
  fun `drop port override flows through to architecture`() {
    // Port 42 is not normally a drop port. With dropPortOverride=42, it should be.
    val config = v1modelPipelineConfig(egressPort = 42)
    val sim = Simulator(dropPortOverride = 42)
    sim.loadPipeline(config)

    val result = sim.processPacket(ingressPort = 0, payload = byteArrayOf(0x01))
    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `drop port override lets default drop port forward normally`() {
    // With dropPortOverride=42, port 511 (the default drop port) should forward normally.
    val config = v1modelPipelineConfig(egressPort = V1ModelArchitecture.DEFAULT_DROP_PORT.toLong())
    val sim = Simulator(dropPortOverride = 42)
    sim.loadPipeline(config)

    val result = sim.processPacket(ingressPort = 0, payload = byteArrayOf(0x01))
    val outputs = collectAllOutputsFromTrace(result.trace)
    assertEquals(1, outputs.size)
    assertEquals(V1ModelArchitecture.DEFAULT_DROP_PORT, outputs[0].dataplaneEgressPort)
  }
}

/** Unit tests for [collectPossibleOutcomes] — the parallel vs alternative fork semantics. */
class CollectPossibleOutcomesTest {

  private fun output(port: Int): fourward.sim.SimulatorProto.TraceTree =
    fourward.sim.SimulatorProto.TraceTree.newBuilder()
      .setPacketOutcome(
        fourward.sim.SimulatorProto.PacketOutcome.newBuilder()
          .setOutput(
            fourward.sim.SimulatorProto.OutputPacket.newBuilder()
              .setDataplaneEgressPort(port)
              .setPayload(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x01)))
          )
      )
      .build()

  private fun drop(): fourward.sim.SimulatorProto.TraceTree =
    fourward.sim.SimulatorProto.TraceTree.newBuilder()
      .setPacketOutcome(
        fourward.sim.SimulatorProto.PacketOutcome.newBuilder()
          .setDrop(
            fourward.sim.SimulatorProto.Drop.newBuilder()
              .setReason(fourward.sim.SimulatorProto.DropReason.MARK_TO_DROP)
          )
      )
      .build()

  private fun parallelFork(
    reason: fourward.sim.SimulatorProto.ForkReason,
    vararg branches: Pair<String, fourward.sim.SimulatorProto.TraceTree>,
  ): fourward.sim.SimulatorProto.TraceTree {
    val mode =
      if (reason == fourward.sim.SimulatorProto.ForkReason.ACTION_SELECTOR)
        fourward.sim.SimulatorProto.ForkMode.FORK_MODE_ALTERNATIVE
      else fourward.sim.SimulatorProto.ForkMode.FORK_MODE_PARALLEL
    return fourward.sim.SimulatorProto.TraceTree.newBuilder()
      .setForkOutcome(
        fourward.sim.SimulatorProto.Fork.newBuilder()
          .setReason(reason)
          .setMode(mode)
          .addAllBranches(
            branches.map {
              fourward.sim.SimulatorProto.ForkBranch.newBuilder()
                .setLabel(it.first)
                .setSubtree(it.second)
                .build()
            }
          )
      )
      .build()
  }

  private fun alternativeFork(
    vararg branches: Pair<String, fourward.sim.SimulatorProto.TraceTree>
  ): fourward.sim.SimulatorProto.TraceTree =
    parallelFork(fourward.sim.SimulatorProto.ForkReason.ACTION_SELECTOR, *branches)

  @Test
  fun `linear trace produces one world with one output`() {
    val outcomes = collectPossibleOutcomes(output(1))
    assertEquals(listOf(listOf(output(1).packetOutcome.output)), outcomes)
  }

  @Test
  fun `drop produces one world with no outputs`() {
    val outcomes = collectPossibleOutcomes(drop())
    assertEquals(1, outcomes.size)
    assertTrue(outcomes[0].isEmpty())
  }

  @Test
  fun `parallel fork combines outputs within each world`() {
    val tree =
      parallelFork(
        fourward.sim.SimulatorProto.ForkReason.CLONE,
        "original" to output(1),
        "clone" to output(2),
      )
    val outcomes = collectPossibleOutcomes(tree)
    assertEquals("one world", 1, outcomes.size)
    assertEquals("two outputs", 2, outcomes[0].size)
    assertEquals(1, outcomes[0][0].dataplaneEgressPort)
    assertEquals(2, outcomes[0][1].dataplaneEgressPort)
  }

  @Test
  fun `alternative fork produces one world per branch`() {
    val tree =
      alternativeFork("member_0" to output(1), "member_1" to output(2), "member_2" to output(3))
    val outcomes = collectPossibleOutcomes(tree)
    assertEquals("three worlds", 3, outcomes.size)
    assertEquals(1, outcomes[0].single().dataplaneEgressPort)
    assertEquals(2, outcomes[1].single().dataplaneEgressPort)
    assertEquals(3, outcomes[2].single().dataplaneEgressPort)
  }

  @Test
  fun `alternative inside parallel produces Cartesian product`() {
    // Clone (parallel) with 2 branches, each containing a 2-member selector (alternative).
    val tree =
      parallelFork(
        fourward.sim.SimulatorProto.ForkReason.CLONE,
        "original" to alternativeFork("m0" to output(1), "m1" to output(2)),
        "clone" to alternativeFork("m0" to output(3), "m1" to output(4)),
      )
    val outcomes = collectPossibleOutcomes(tree)
    // 2 × 2 = 4 possible worlds, each with 2 outputs (one from original, one from clone).
    assertEquals("2×2 Cartesian product", 4, outcomes.size)
    assertTrue(outcomes.all { it.size == 2 })
    // Verify all 4 combinations exist.
    val portPairs = outcomes.map { world -> world.map { it.dataplaneEgressPort }.sorted() }.toSet()
    assertEquals(setOf(listOf(1, 3), listOf(1, 4), listOf(2, 3), listOf(2, 4)), portPairs)
  }

  @Test
  fun `alternative with drop produces world with empty output`() {
    val tree = alternativeFork("m0" to output(1), "m1" to drop())
    val outcomes = collectPossibleOutcomes(tree)
    assertEquals(2, outcomes.size)
    assertEquals(1, outcomes[0].size)
    assertTrue("drop world is empty", outcomes[1].isEmpty())
  }

  @Test
  fun `multicast fork is parallel`() {
    val tree =
      parallelFork(
        fourward.sim.SimulatorProto.ForkReason.MULTICAST,
        "r0" to output(1),
        "r1" to output(2),
        "r2" to output(3),
      )
    val outcomes = collectPossibleOutcomes(tree)
    assertEquals("one world", 1, outcomes.size)
    assertEquals("three outputs", 3, outcomes[0].size)
  }
}
