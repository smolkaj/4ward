package fourward.cli

import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.simulator.Endpoint
import fourward.simulator.Link
import fourward.simulator.NetworkSimulator
import fourward.simulator.NetworkTopology
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * `4ward network test.nstf` — run a multi-switch network simulation.
 *
 * Parses a `.nstf` file describing the topology, per-switch pipeline configs and table entries,
 * input packets, and expected outputs. Builds a [NetworkSimulator], processes packets, prints
 * trace trees, and verifies expectations.
 */
fun networkSim(nstfPath: Path, format: OutputFormat): Int {
  val nstf =
    try {
      NetworkStf.parse(nstfPath)
    } catch (e: FileNotFoundException) {
      System.err.println("error: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: NoSuchFileException) {
      System.err.println("error: $nstfPath: no such file")
      return ExitCode.USAGE_ERROR
    } catch (e: IllegalArgumentException) {
      System.err.println("error: $nstfPath: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: IllegalStateException) {
      System.err.println("error: $nstfPath: ${e.message}")
      return ExitCode.USAGE_ERROR
    }

  val topology =
    NetworkTopology(
      nstf.links.map { Link(Endpoint(it.switchA, it.portA), Endpoint(it.switchB, it.portB)) }
    )
  val network =
    try {
      NetworkSimulator(topology)
    } catch (e: IllegalArgumentException) {
      System.err.println("error: invalid topology: ${e.message}")
      return ExitCode.USAGE_ERROR
    }

  // Load each switch's pipeline and install table entries.
  for (sw in nstf.switches) {
    val config =
      try {
        loadPipelineConfig(sw.pipelinePath)
      } catch (e: Exception) {
        System.err.println("error: switch '${sw.id}': failed to load pipeline: ${e.message}")
        return ExitCode.USAGE_ERROR
      }
    val sim =
      try {
        network.addSwitch(sw.id)
      } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        return ExitCode.USAGE_ERROR
      }
    try {
      sim.loadPipeline(config)
      val stf = StfFile.parse(sw.stfPath)
      installStfEntries(sim, stf, config.p4Info)
    } catch (e: Exception) {
      System.err.println("error: switch '${sw.id}': ${e.message}")
      return ExitCode.INTERNAL_ERROR
    }
  }

  // Process packets and collect edge outputs for expectation matching.
  val edgeOutputs = mutableListOf<Pair<String, fourward.simulator.EdgeOutput>>()
  var failed = false

  for (packet in nstf.packets) {
    val root =
      try {
        network.processPacket(packet.switchId, packet.port, packet.payload)
      } catch (e: IllegalStateException) {
        System.err.println("error: ${e.message}")
        return ExitCode.INTERNAL_ERROR
      }

    when (format) {
      OutputFormat.HUMAN -> {
        println("packet injected: ${packet.switchId}:${packet.port}, ${packet.payload.size} bytes")
        printNetworkHop(root, indent = "  ")
      }
      OutputFormat.TEXTPROTO,
      OutputFormat.JSON -> {
        // For now, print per-switch traces in human format.
        // Full network trace proto format is future work.
        println("packet injected: ${packet.switchId}:${packet.port}")
        printNetworkHop(root, indent = "  ")
      }
    }

    collectEdgeOutputs(root, edgeOutputs)
  }

  // Match outputs against expectations.
  val failures = matchNetworkExpects(nstf.expects, edgeOutputs)
  if (failures.isNotEmpty()) {
    System.err.println("FAIL")
    for (f in failures) System.err.println("  $f")
    return ExitCode.TEST_FAILURE
  }
  println("PASS")
  return ExitCode.SUCCESS
}

/** Recursively prints a [NetworkHop] tree in human-readable format. */
private fun printNetworkHop(hop: fourward.simulator.NetworkHop, indent: String) {
  println("${indent}switch ${hop.switchId} (ingress port ${hop.ingressPort}):")
  println("${indent}  ${TraceFormatter.format(hop.trace).trim().prependIndent("${indent}  ").trimStart()}")
  for (output in hop.edgeOutputs) {
    println("${indent}  → edge output: ${output.switchId}:${output.egressPort} (${output.payload.size()} bytes)")
  }
  for (next in hop.nextHops) {
    printNetworkHop(next, "$indent  ")
  }
}

/** Recursively collects all edge outputs from a [NetworkHop] tree. */
private fun collectEdgeOutputs(
  hop: fourward.simulator.NetworkHop,
  out: MutableList<Pair<String, fourward.simulator.EdgeOutput>>,
) {
  for (edge in hop.edgeOutputs) out.add(Pair(hop.switchId, edge))
  for (next in hop.nextHops) collectEdgeOutputs(next, out)
}

/** Matches expected outputs against actual edge outputs. Returns a list of failure messages. */
private fun matchNetworkExpects(
  expects: List<NetworkStf.NetworkExpect>,
  edgeOutputs: List<Pair<String, fourward.simulator.EdgeOutput>>,
): List<String> {
  if (expects.isEmpty()) return emptyList()

  val failures = mutableListOf<String>()
  val remaining = edgeOutputs.toMutableList()

  for (expect in expects) {
    val idx =
      remaining.indexOfFirst { (_, output) ->
        output.switchId == expect.switchId &&
          output.egressPort == expect.port &&
          output.payload.toByteArray().contentEquals(expect.payload)
      }
    if (idx >= 0) {
      remaining.removeAt(idx)
    } else {
      failures.add(
        "expected output on ${expect.switchId}:${expect.port} not found"
      )
    }
  }

  for ((_, output) in remaining) {
    failures.add(
      "unexpected output on ${output.switchId}:${output.egressPort}"
    )
  }

  return failures
}
