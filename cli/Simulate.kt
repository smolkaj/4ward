package fourward.cli

import com.google.protobuf.TextFormat
import com.google.protobuf.util.JsonFormat
import fourward.e2e.ReceivedPacket
import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.e2e.matchOutputAgainstExpects
import fourward.simulator.MAX_POSSIBLE_OUTCOMES
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
fun simulate(pipelinePath: Path, stfPath: Path, format: OutputFormat, dropPort: Int? = null): Int {
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
  val sim = Simulator(dropPort)

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

  var possibleWorlds: List<List<ReceivedPacket>> = listOf(emptyList())
  val textProtoPrinter = TextFormat.printer()
  val jsonPrinter = JsonFormat.printer().preservingProtoFieldNames()

  for (packet in stf.packets) {
    val result = sim.processPacket(packet.ingressPort, packet.payload)
    when (format) {
      OutputFormat.HUMAN -> {
        println("packet received: port ${packet.ingressPort}, ${packet.payload.size} bytes")
        println(TraceFormatter.format(result.trace).trim().prependIndent("  "))
      }
      OutputFormat.TEXTPROTO -> {
        print(TEXTPROTO_HEADER)
        print(textProtoPrinter.printToString(result.trace))
      }
      OutputFormat.JSON -> {
        print(JSON_HEADER)
        println(jsonPrinter.print(result.trace))
      }
    }
    // Each packet may have multiple possible outcomes (alternative forks, e.g. action selectors).
    // Expand the Cartesian product of outcomes across packets.
    possibleWorlds =
      possibleWorlds
        .flatMap { world ->
          result.possibleOutcomes.map { outcome ->
            world +
              outcome.map { pkt ->
                ReceivedPacket(pkt.dataplaneEgressPort, pkt.payload.toByteArray())
              }
          }
        }
        .let { if (it.size > MAX_POSSIBLE_OUTCOMES) it.take(MAX_POSSIBLE_OUTCOMES) else it }
  }

  // Find the first world that satisfies all expects, or report the best failure.
  val worldFailures =
    possibleWorlds.map { world -> matchOutputAgainstExpects(stf.expects, world.toMutableList()) }
  val failures = worldFailures.firstOrNull { it.isEmpty() } ?: worldFailures.minBy { it.size }
  if (failures.isNotEmpty()) {
    System.err.println("FAIL")
    for (f in failures) System.err.println("  $f")
    return ExitCode.TEST_FAILURE
  }
  println("PASS")
  return ExitCode.SUCCESS
}

private const val PROTO_FILE = "@fourward//simulator/simulator.proto"
private const val PROTO_MESSAGE = "fourward.sim.TraceTree"
private const val TEXTPROTO_HEADER = "# proto-file: $PROTO_FILE\n# proto-message: $PROTO_MESSAGE\n"
private const val JSON_HEADER = "// proto-file: $PROTO_FILE\n// proto-message: $PROTO_MESSAGE\n"
