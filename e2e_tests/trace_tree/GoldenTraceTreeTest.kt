package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.sim.SimulatorProto.TraceTree
import fourward.simulator.Simulator
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Parameterized golden test for trace trees.
 *
 * Each test case consists of:
 * - A compiled PipelineConfig (.txtpb)
 * - An STF file (.stf) with optional table entries and at least one packet
 * - An expected TraceTree (.golden.txtpb)
 *
 * The test sends the first packet through the simulator and compares the resulting TraceTree
 * against the golden file. This is the TDD harness for Track 3 (trace trees): all tests are written
 * up front and expected to fail until the corresponding feature is implemented.
 */
@RunWith(Parameterized::class)
class GoldenTraceTreeTest(private val testName: String) {

  companion object {
    private const val PKG = "e2e_tests/trace_tree"

    @JvmStatic
    @Parameters(name = "{0}")
    fun testCases(): List<Array<String>> {
      val r = System.getenv("JAVA_RUNFILES") ?: "."
      val dir = Paths.get(r, "_main/$PKG").toFile()
      return dir
        .listFiles { f -> f.name.endsWith(".golden.txtpb") }
        ?.map { arrayOf(it.name.removeSuffix(".golden.txtpb")) }
        ?.sortedBy { it[0] } ?: emptyList()
    }
  }

  @Test
  fun `trace tree matches golden file`() {
    val r = System.getenv("JAVA_RUNFILES") ?: "."
    val configPath = Paths.get(r, "_main/$PKG/$testName.txtpb")
    val stfPath = Paths.get(r, "_main/$PKG/$testName.stf")
    val goldenPath = Paths.get(r, "_main/$PKG/$testName.golden.txtpb")

    val expected = loadGoldenTraceTree(goldenPath)
    val actual = captureTraceTree(configPath, stfPath)
    if (System.getenv("PRINT_TRACE") != null) {
      println("--- Trace tree for $testName ---")
      print(TextFormat.printer().printToString(actual))
      println("--- End trace tree ---")
    }
    if (expected != actual) {
      fail(
        "Trace tree mismatch for $testName.\n" +
          "Expected:\n${TextFormat.printer().printToString(expected)}\n" +
          "Actual:\n${TextFormat.printer().printToString(actual)}"
      )
    }
  }

  private fun loadGoldenTraceTree(path: Path): TraceTree {
    val builder = TraceTree.newBuilder()
    TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  /**
   * Creates a simulator, loads the pipeline, installs table entries from the STF file, sends the
   * first packet, and returns the TraceTree from the response.
   */
  private fun captureTraceTree(configPath: Path, stfPath: Path): TraceTree {
    val config = loadPipelineConfig(configPath)
    val stf = StfFile.parse(stfPath)

    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, stf, config.p4Info)

    val packet = stf.packets.first()
    return sim.processPacket(packet.ingressPort, packet.payload).trace
  }
}
