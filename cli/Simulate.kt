package fourward.cli

import com.google.protobuf.TextFormat
import fourward.e2e.ReceivedPacket
import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.e2e.matchOutputAgainstExpects
import fourward.simulator.Simulator
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * `4ward sim pipeline.txtpb test.stf` — run an STF test against a pre-compiled pipeline.
 *
 * Loads the pipeline, installs table entries, sends packets, verifies expectations, and prints the
 * trace tree for each packet. Returns an exit code (does not call `exitProcess`).
 */
fun simulate(pipelinePath: Path, stfPath: Path, format: OutputFormat): Int {
  val config =
    try {
      loadPipelineConfig(pipelinePath)
    } catch (e: FileNotFoundException) {
      System.err.println("error: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: NoSuchFileException) {
      System.err.println("error: $pipelinePath: no such file")
      return ExitCode.USAGE_ERROR
    }
  val stf =
    try {
      StfFile.parse(stfPath)
    } catch (e: FileNotFoundException) {
      System.err.println("error: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: NoSuchFileException) {
      System.err.println("error: $stfPath: no such file")
      return ExitCode.USAGE_ERROR
    }
  val sim = Simulator()

  try {
    sim.loadPipeline(config)
  } catch (e: IllegalArgumentException) {
    System.err.println("error: failed to load pipeline: ${e.message}")
    return ExitCode.INTERNAL_ERROR
  }

  try {
    installStfEntries(sim, stf, config.p4Info)
  } catch (e: IllegalStateException) {
    System.err.println("error: ${e.message}")
    return ExitCode.INTERNAL_ERROR
  }

  val outputQueue = mutableListOf<ReceivedPacket>()
  val textProtoPrinter = TextFormat.printer()

  for (packet in stf.packets) {
    val result = sim.processPacket(packet.ingressPort, packet.payload)
    when (format) {
      OutputFormat.HUMAN -> {
        println("packet received: port ${packet.ingressPort}, ${packet.payload.size} bytes")
        println(TraceFormatter.format(result.trace).trim().prependIndent("  "))
      }
      OutputFormat.TEXTPROTO -> print(textProtoPrinter.printToString(result.trace))
    }
    val pkts = result.outputPackets
    for (pkt in pkts) {
      outputQueue += ReceivedPacket(pkt.egressPort, pkt.payload.toByteArray())
    }
  }

  val failures = matchOutputAgainstExpects(stf.expects, outputQueue)
  if (failures.isNotEmpty()) {
    System.err.println("FAIL")
    for (f in failures) System.err.println("  $f")
    return ExitCode.TEST_FAILURE
  }
  println("PASS")
  return ExitCode.SUCCESS
}
