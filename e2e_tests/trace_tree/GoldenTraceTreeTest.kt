package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.e2e.StfFile
import fourward.e2e.assertMatchesGoldenFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.e2e.runfilePath
import fourward.sim.SimulatorProto.TraceTree
import fourward.simulator.Simulator
import java.nio.file.Path
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
 * To update golden files after an intentional change: bazel run
 * //e2e_tests/trace_tree:golden_trace_tree_test -- --update
 */
@RunWith(Parameterized::class)
class GoldenTraceTreeTest(private val testName: String) {

  companion object {
    private const val PKG = "e2e_tests/trace_tree"

    @JvmStatic
    @Parameters(name = "{0}")
    fun testCases(): List<Array<String>> {
      val dir = runfilePath(PKG, "").toFile()
      return dir
        .listFiles { f -> f.name.endsWith(".golden.txtpb") }
        ?.map { arrayOf(it.name.removeSuffix(".golden.txtpb")) }
        ?.sortedBy { it[0] } ?: emptyList()
    }
  }

  @Test
  fun `trace tree matches golden file`() {
    val configPath = runfilePath(PKG, "$testName.txtpb")
    val stfPath = runfilePath(PKG, "$testName.stf")
    val actual = captureTraceTree(configPath, stfPath)

    assertMatchesGoldenFile(
      goldenFileName = "$testName.golden.txtpb",
      pkg = PKG,
      actual = TextFormat.printer().printToString(actual),
    )
  }

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
