package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.sim.SimulatorProto.PacketOutcome
import fourward.sim.SimulatorProto.TraceTree
import fourward.simulator.ProcessPacketResult
import fourward.simulator.Simulator
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Verifies that output packets from [ProcessPacketResult] are consistent with the leaf outcomes in
 * the trace tree.
 *
 * The possible outcomes should be consistent with the trace tree's leaf outcomes, whether the trace
 * forks (multicast, clone, action selectors) or not.
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

  private fun verifyConsistency(result: ProcessPacketResult) {
    val trace = result.trace
    val leafOutcomes = collectLeafOutcomes(trace)

    assertTrue("Trace tree for $testName has no leaf outcomes", leafOutcomes.isNotEmpty())

    // Verify possibleOutcomes is consistent with the trace tree: flattening all worlds
    // should produce the same outputs as collecting all leaf outputs from the tree.
    val outputsFromPossibleOutcomes =
      result.possibleOutcomes.flatten().map { it.dataplaneEgressPort to it.payload }

    val outputsFromTree =
      leafOutcomes
        .filter { it.hasOutput() }
        .map { it.output.dataplaneEgressPort to it.output.payload }

    // For trees without nested alternative-inside-parallel forks, these match exactly.
    // For trees with such nesting, possibleOutcomes may have duplicates from the Cartesian
    // product, so we compare as sets.
    assertEquals(
      "Output packets vs trace tree mismatch for $testName.\n" +
        "Trace:\n${TextFormat.printer().printToString(trace)}",
      outputsFromPossibleOutcomes.toSet(),
      outputsFromTree.toSet(),
    )
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
