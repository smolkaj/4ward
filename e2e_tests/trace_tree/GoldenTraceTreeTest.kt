package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.e2e.SimulatorClient
import fourward.e2e.StfFile
import fourward.e2e.resolveStfTableEntry
import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.TraceTree
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
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
    val actual = captureTraceTree(r, configPath, stfPath)
    assertEquals(
      "Trace tree mismatch for $testName.\n" +
        "Expected:\n${TextFormat.printer().printToString(expected)}\n" +
        "Actual:\n${TextFormat.printer().printToString(actual)}",
      expected,
      actual,
    )
  }

  private fun loadGoldenTraceTree(path: Path): TraceTree {
    val builder = TraceTree.newBuilder()
    TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  /**
   * Launches the simulator, loads the pipeline, installs table entries from the STF file, sends the
   * first packet, and returns the TraceTree from the response.
   */
  private fun captureTraceTree(runfiles: String, configPath: Path, stfPath: Path): TraceTree {
    val config = loadConfig(configPath)
    val stf = StfFile.parse(stfPath)
    val simPath = Paths.get(runfiles, "_main/simulator/simulator")

    SimulatorClient(simPath).use { sim ->
      val loadResp = sim.loadPipeline(config)
      if (loadResp.hasError()) fail("LoadPipeline failed: ${loadResp.error.message}")

      for (directive in stf.tableEntries) {
        val writeResp = sim.writeEntry(resolveStfTableEntry(directive, config.p4Info))
        if (writeResp.hasError()) fail("WriteEntry failed: ${writeResp.error.message}")
      }

      val packet = stf.packets.first()
      val resp = sim.processPacket(packet.ingressPort, packet.payload)
      if (resp.hasError()) fail("ProcessPacket failed: ${resp.error.message}")
      return resp.processPacket.trace
    }
  }

  private fun loadConfig(path: Path): PipelineConfig {
    val builder = PipelineConfig.newBuilder()
    TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }
}
