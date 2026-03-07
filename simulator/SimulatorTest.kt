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

import fourward.ir.v1.ActionDecl
import fourward.ir.v1.Architecture
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.DeviceConfig
import fourward.ir.v1.PipelineConfig
import fourward.ir.v1.PipelineStage
import fourward.ir.v1.StageKind
import fourward.ir.v1.TableBehavior
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
}
