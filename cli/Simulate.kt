package fourward.cli

import com.google.protobuf.TextFormat
import fourward.e2e.EgressPacket
import fourward.e2e.StfFile
import fourward.e2e.collectOutputsFromTrace
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.e2e.verifyPacketOutputs
import java.nio.file.Path

/**
 * `4ward sim pipeline.txtpb test.stf` — run an STF test against a pre-compiled pipeline.
 *
 * Loads the pipeline, installs table entries, sends packets, verifies expectations, and prints the
 * trace tree for each packet. Returns an exit code (does not call `exitProcess`).
 */
fun simulate(pipelinePath: Path, stfPath: Path, format: OutputFormat): Int {
  val config = loadPipelineConfig(pipelinePath)
  val stf = StfFile.parse(stfPath)
  val sim = InProcessSimulator()

  val loadResp = sim.loadPipeline(config)
  if (loadResp.hasError()) {
    System.err.println("error: failed to load pipeline: ${loadResp.error.message}")
    return ExitCode.INTERNAL_ERROR
  }

  try {
    installStfEntries(sim, stf, config.p4Info)
  } catch (e: IllegalStateException) {
    System.err.println("error: ${e.message}")
    return ExitCode.INTERNAL_ERROR
  }

  val outputQueue = mutableListOf<EgressPacket>()

  for (packet in stf.packets) {
    val resp = sim.processPacket(packet.ingressPort, packet.payload)
    if (resp.hasError()) {
      System.err.println("error: ${resp.error.message}")
      return ExitCode.INTERNAL_ERROR
    }
    val trace = resp.processPacket.trace
    when (format) {
      OutputFormat.HUMAN -> print(TraceFormatter.format(trace))
      OutputFormat.TEXTPROTO -> print(TextFormat.printer().printToString(trace))
    }
    val pkts = resp.processPacket.outputPacketsList.ifEmpty { collectOutputsFromTrace(trace) }
    for (pkt in pkts) {
      outputQueue += EgressPacket(pkt.egressPort, pkt.payload.toByteArray())
    }
  }

  val failures = verifyPacketOutputs(outputQueue, stf.expects)
  if (failures.isNotEmpty()) {
    System.err.println("FAIL")
    for (f in failures) System.err.println("  $f")
    return ExitCode.TEST_FAILURE
  }
  println("PASS")
  return ExitCode.SUCCESS
}
