package fourward.e2e.tracetree

import com.google.protobuf.TextFormat
import fourward.e2e.TestResult
import fourward.e2e.resolveStfTableEntry
import fourward.e2e.runStf
import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.LoadPipelineRequest
import fourward.sim.v1.ProcessPacketRequest
import fourward.sim.v1.SimRequest
import fourward.sim.v1.SimResponse
import fourward.sim.v1.TraceTree
import java.io.DataInputStream
import java.io.DataOutputStream
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
 * - An STF file (.stf) with optional table entries and at least one packet directive
 * - An expected TraceTree (.golden.txtpb)
 *
 * The test sends the first packet through the simulator and compares the
 * resulting TraceTree against the golden file.  This is the TDD harness for
 * Track 3 (trace trees): all tests are written up front and expected to fail
 * until the corresponding feature is implemented.
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
        ?.sortedBy { it[0] }
        ?: emptyList()
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
    val stf = fourward.e2e.StfFile.parse(stfPath)

    val simPath = Paths.get(runfiles, "_main/simulator/simulator")
    val process = ProcessBuilder(simPath.toString()).redirectErrorStream(false).start()
    val input = DataInputStream(process.inputStream.buffered())
    val output = DataOutputStream(process.outputStream.buffered())

    try {
      // Load pipeline.
      sendRequest(
        output,
        SimRequest.newBuilder()
          .setLoadPipeline(LoadPipelineRequest.newBuilder().setConfig(config))
          .build(),
      )
      val loadResp = readResponse(input)
      if (loadResp.hasError()) fail("LoadPipeline failed: ${loadResp.error.message}")

      // Install table entries.
      for (directive in stf.tableEntries) {
        val writeReq = resolveStfTableEntry(directive, config.p4Info)
        sendRequest(output, SimRequest.newBuilder().setWriteEntry(writeReq).build())
        val writeResp = readResponse(input)
        if (writeResp.hasError()) fail("WriteEntry failed: ${writeResp.error.message}")
      }

      // Send the first packet.
      val packet = stf.packets.first()
      sendRequest(
        output,
        SimRequest.newBuilder()
          .setProcessPacket(
            ProcessPacketRequest.newBuilder()
              .setIngressPort(packet.ingressPort)
              .setPayload(com.google.protobuf.ByteString.copyFrom(packet.payload))
          )
          .build(),
      )
      val resp = readResponse(input)
      if (resp.hasError()) fail("ProcessPacket failed: ${resp.error.message}")
      return resp.processPacket.trace
    } finally {
      process.destroy()
    }
  }

  private fun loadConfig(path: Path): PipelineConfig {
    val builder = PipelineConfig.newBuilder()
    TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  private fun sendRequest(output: DataOutputStream, request: SimRequest) {
    val bytes = request.toByteArray()
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
  }

  private fun readResponse(input: DataInputStream): SimResponse {
    val length = input.readInt()
    val bytes = ByteArray(length)
    input.readFully(bytes)
    return SimResponse.parseFrom(bytes)
  }
}
