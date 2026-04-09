package fourward.cli

import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.simulator.EdgeOutput
import fourward.simulator.NetworkHop
import fourward.simulator.NetworkSimulator
import fourward.simulator.NetworkTopology
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * `4ward network test.nstf` — run a multi-switch network simulation.
 *
 * Parses a `.nstf` file describing the topology, per-switch pipeline configs and table entries,
 * input packets, and expected outputs. Builds a [NetworkSimulator], processes packets, prints trace
 * trees, and verifies expectations.
 */
fun networkSim(nstfPath: Path, format: OutputFormat): Int {
  // TODO: support textproto/json output for NetworkHop once the network trace proto is defined.
  if (format != OutputFormat.HUMAN) {
    System.err.println(
      "warning: --format=${format.name.lowercase()} is not yet supported for network simulation; using human format"
    )
  }
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

  val topology = NetworkTopology(nstf.links)
  val network =
    try {
      NetworkSimulator(topology)
    } catch (e: IllegalArgumentException) {
      System.err.println("error: invalid topology: ${e.message}")
      return ExitCode.USAGE_ERROR
    }

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
      // Install entries from external STF file (if provided).
      if (sw.stfPath != null) {
        val stf = StfFile.parse(sw.stfPath)
        installStfEntries(sim, stf, config.p4Info)
      }
      // Install inline entries (if any).
      val inlineLines = nstf.inlineEntries[sw.id]
      if (inlineLines != null) {
        val tempStf = Files.createTempFile("4ward-inline-", ".stf")
        tempStf.toFile().deleteOnExit()
        tempStf.toFile().writeText(inlineLines.joinToString("\n"))
        val stf = StfFile.parse(tempStf)
        installStfEntries(sim, stf, config.p4Info)
      }
    } catch (e: Exception) {
      System.err.println("error: switch '${sw.id}': ${e.message}")
      return ExitCode.INTERNAL_ERROR
    }
  }

  val edgeOutputs = mutableListOf<EdgeOutput>()

  for (packet in nstf.packets) {
    val root =
      try {
        network.processPacket(packet.endpoint.switchId, packet.endpoint.port, packet.payload)
      } catch (e: IllegalStateException) {
        System.err.println("error: ${e.message}")
        return ExitCode.INTERNAL_ERROR
      }

    println("packet injected: ${packet.endpoint}, ${packet.payload.size} bytes")
    printNetworkHop(root, indent = "  ")

    collectEdgeOutputs(root, edgeOutputs)
  }

  val failures = matchNetworkExpects(nstf.expects, edgeOutputs)
  if (failures.isNotEmpty()) {
    System.err.println("FAIL")
    for (f in failures) System.err.println("  $f")
    return ExitCode.TEST_FAILURE
  }
  println("PASS")
  return ExitCode.SUCCESS
}

private fun printNetworkHop(hop: NetworkHop, indent: String) {
  println("${indent}switch ${hop.switchId} (ingress port ${hop.ingressPort}):")
  val trace = TraceFormatter.format(hop.trace).trim().prependIndent("$indent  ")
  println(trace)
  for (output in hop.edgeOutputs) {
    println(
      "$indent  → edge output: ${output.switchId}:${output.egressPort} (${output.payload.size()} bytes)"
    )
  }
  for (next in hop.nextHops) {
    printNetworkHop(next, "$indent  ")
  }
}

private fun collectEdgeOutputs(hop: NetworkHop, out: MutableList<EdgeOutput>) {
  out.addAll(hop.edgeOutputs)
  for (next in hop.nextHops) collectEdgeOutputs(next, out)
}

private fun matchNetworkExpects(
  expects: List<NetworkStf.NetworkExpect>,
  edgeOutputs: List<EdgeOutput>,
): List<String> {
  if (expects.isEmpty()) return emptyList()

  val failures = mutableListOf<String>()
  val remaining = edgeOutputs.toMutableList()

  for (expect in expects) {
    val idx =
      remaining.indexOfFirst { output ->
        output.switchId == expect.endpoint.switchId &&
          output.egressPort == expect.endpoint.port &&
          output.payload.toByteArray().contentEquals(expect.payload)
      }
    if (idx >= 0) {
      remaining.removeAt(idx)
    } else {
      failures.add("expected output on ${expect.endpoint} not found")
    }
  }

  for (output in remaining) {
    failures.add("unexpected output on ${output.switchId}:${output.egressPort}")
  }

  return failures
}
