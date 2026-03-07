package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.sim.v1.SimulatorProto.PacketOutcome
import fourward.sim.v1.SimulatorProto.ProcessPacketResponse
import fourward.sim.v1.SimulatorProto.TraceTree
import fourward.simulator.Simulator
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Verifies that output packets from [ProcessPacketResponse] are consistent with the leaf outcomes
 * in the trace tree.
 *
 * For non-forking programs (deterministic), the output_packets list should exactly match the
 * packet_outcome leaves. For forking programs (non-deterministic), output_packets is empty (no
 * branch is chosen) but every leaf must carry a packet_outcome.
 */
@RunWith(Parameterized::class)
class TraceTreeConsistencyTest(private val testName: String) {

  companion object {
    private const val PKG = "e2e_tests/trace_tree"

    @JvmStatic
    @Parameters(name = "{0}")
    fun testCases(): List<Array<String>> {
      val r = System.getenv("JAVA_RUNFILES") ?: "."
      val dir = Paths.get(r, "_main/$PKG").toFile()
      return dir
        .listFiles { f -> f.name.endsWith(".stf") }
        ?.map { arrayOf(it.name.removeSuffix(".stf")) }
        ?.sortedBy { it[0] } ?: emptyList()
    }
  }

  @Test
  fun `output packets match trace tree leaves`() {
    val r = System.getenv("JAVA_RUNFILES") ?: "."
    val configPath = Paths.get(r, "_main/$PKG/$testName.txtpb")
    val stfPath = Paths.get(r, "_main/$PKG/$testName.stf")

    val config = loadPipelineConfig(configPath)
    val stf = StfFile.parse(stfPath)

    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, stf, config.p4Info)

    for (packet in stf.packets) {
      verifyConsistency(sim.processPacket(packet.ingressPort, packet.payload))
    }
  }

  private fun verifyConsistency(response: ProcessPacketResponse) {
    val trace = response.trace
    val leafOutcomes = collectLeafOutcomes(trace)

    assertTrue("Trace tree for $testName has no leaf outcomes", leafOutcomes.isNotEmpty())

    val outputsFromResponse = response.outputPacketsList.map { it.egressPort to it.payload }.toSet()

    val outputsFromTree =
      leafOutcomes
        .filter { it.hasOutput() }
        .map { it.output.egressPort to it.output.payload }
        .toSet()

    val hasDrops = leafOutcomes.any { it.hasDrop() }

    if (trace.hasForkOutcome()) {
      // Forking: output_packets is empty (no branch chosen).
      assertEquals(
        "Forking program $testName should have empty output_packets",
        emptySet<Any>(),
        outputsFromResponse,
      )
      assertTrue(
        "Forking program $testName has no leaf outcomes",
        outputsFromTree.isNotEmpty() || hasDrops,
      )
    } else {
      // Non-forking: output_packets must match trace tree leaves.
      assertEquals(
        "Output packets vs trace tree mismatch for $testName.\n" +
          "Trace:\n${TextFormat.printer().printToString(trace)}",
        outputsFromResponse,
        outputsFromTree,
      )
      if (hasDrops) {
        assertTrue(
          "Drop outcome in $testName but output_packets is non-empty",
          outputsFromResponse.isEmpty(),
        )
      }
    }
  }

  /** Recursively collects all leaf [PacketOutcome]s from a trace tree. */
  private fun collectLeafOutcomes(tree: TraceTree): List<PacketOutcome> =
    when {
      tree.hasPacketOutcome() -> listOf(tree.packetOutcome)
      tree.hasForkOutcome() ->
        tree.forkOutcome.branchesList.flatMap { collectLeafOutcomes(it.subtree) }
      else -> emptyList()
    }
}
