package fourward.cli

import com.google.protobuf.TextFormat
import fourward.e2e.StfFile
import fourward.e2e.collectOutputsFromTrace
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.e2e.matchesMasked
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `4ward sim pipeline.txtpb test.stf` — run an STF test against a pre-compiled pipeline.
 *
 * Loads the pipeline, installs table entries, sends packets, verifies expectations, and prints
 * the trace tree for each packet.
 */
fun simulate(pipelinePath: Path, stfPath: Path, format: OutputFormat) {
  val config = loadPipelineConfig(pipelinePath)
  val stf = StfFile.parse(stfPath)
  val sim = InProcessSimulator()

  val loadResp = sim.loadPipeline(config)
  if (loadResp.hasError()) {
    System.err.println("error: failed to load pipeline: ${loadResp.error.message}")
    exitProcess(ExitCode.INTERNAL_ERROR)
  }

  try {
    installStfEntries(sim, stf, config.p4Info)
  } catch (e: IllegalStateException) {
    System.err.println("error: ${e.message}")
    exitProcess(ExitCode.INTERNAL_ERROR)
  }

  val failures = mutableListOf<String>()
  data class Output(val port: Int, val payload: ByteArray)

  val outputQueue = mutableListOf<Output>()

  for (packet in stf.packets) {
    val resp = sim.processPacket(packet.ingressPort, packet.payload)
    if (resp.hasError()) {
      System.err.println("error: ${resp.error.message}")
      exitProcess(ExitCode.INTERNAL_ERROR)
    }
    val trace = resp.processPacket.trace
    when (format) {
      OutputFormat.HUMAN -> print(TraceFormatter.format(trace))
      OutputFormat.TEXTPROTO -> print(TextFormat.printer().printToString(trace))
    }
    val pkts =
      resp.processPacket.outputPacketsList.ifEmpty { collectOutputsFromTrace(trace) }
    for (pkt in pkts) {
      outputQueue += Output(pkt.egressPort, pkt.payload.toByteArray())
    }
  }

  for (expected in stf.expects) {
    val idx = outputQueue.indexOfFirst { it.port == expected.port }
    if (idx < 0) {
      failures += "expected packet on port ${expected.port} but got none"
    } else {
      val actual = outputQueue.removeAt(idx)
      if (!actual.payload.matchesMasked(expected.payload, expected.mask, expected.exactLength)) {
        failures +=
          "port ${expected.port}: payload mismatch\n" +
            "  expected: ${expected.payload.hex(expected.mask)}\n" +
            "  actual:   ${actual.payload.hex()}"
      }
    }
  }

  if (failures.isNotEmpty()) {
    System.err.println("FAIL")
    for (f in failures) System.err.println("  $f")
    exitProcess(ExitCode.TEST_FAILURE)
  }
  println("PASS")
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

private fun ByteArray.hex(mask: ByteArray): String =
  indices.joinToString("") { i -> if (mask[i] == 0.toByte()) "**" else "%02x".format(this[i]) }
