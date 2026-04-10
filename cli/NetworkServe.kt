package fourward.cli

import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.network.NetworkStartFailure
import fourward.network.NetworkSwitch
import fourward.network.startNetworkServers
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * `4ward network serve test.nstf` — start P4Runtime servers for a multi-switch network.
 *
 * Parses a `.nstf` file, starts one P4Runtime gRPC server per switch (pre-loaded with pipeline
 * configs and table entries from the .nstf), wires cross-switch packet forwarding, prints a port
 * map, and blocks until shutdown. Controllers connect to individual switches via standard P4Runtime
 * RPCs. Packets injected on one switch automatically traverse linked ports to other switches.
 */
fun networkServe(nstfPath: Path, basePort: Int): Int {
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

  val switchSpecs =
    try {
      nstf.switches.map { sw -> buildSwitchSpec(nstf, sw) }
    } catch (e: Exception) {
      System.err.println("error: failed to load pipeline: ${e.message}")
      return ExitCode.USAGE_ERROR
    }

  val servers =
    try {
      startNetworkServers(nstf.toTopology(), switchSpecs, basePort)
    } catch (e: NetworkStartFailure) {
      System.err.println("error: ${e.message}")
      return ExitCode.INTERNAL_ERROR
    }

  println("Network P4Runtime servers started:")
  for ((idx, entry) in servers.entries.withIndex()) {
    println("  ${entry.key} -> localhost:${entry.value.port()} (device_id=${idx + 1})")
  }
  println("\nPress Ctrl+C to stop.")

  servers.values.first().blockUntilShutdown()
  return ExitCode.SUCCESS
}

/** Converts a [NetworkStf.SwitchDecl] into a [NetworkSwitch] spec by loading its pipeline. */
private fun buildSwitchSpec(nstf: NetworkStf, sw: NetworkStf.SwitchDecl): NetworkSwitch {
  val config = loadPipelineConfig(sw.pipelinePath)
  return NetworkSwitch(
    id = sw.id,
    pipelineConfig = config,
    installEntries = { server ->
      sw.stfPath?.let { installStfEntries(server::writeEntry, StfFile.parse(it), config.p4Info) }
      nstf.inlineEntries[sw.id]?.let { lines ->
        installStfEntries(server::writeEntry, StfFile.parse(lines), config.p4Info)
      }
    },
  )
}
