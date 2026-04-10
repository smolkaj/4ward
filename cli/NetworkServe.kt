package fourward.cli

import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.p4runtime.P4RuntimeServer
import fourward.simulator.Endpoint
import fourward.simulator.NetworkSimulator
import fourward.simulator.NetworkTopology
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.logging.Logger

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

  val servers =
    try {
      startNetworkServers(nstf, basePort)
    } catch (e: NetworkServeException) {
      System.err.println("error: ${e.message}")
      return e.exitCode
    }

  println("Network P4Runtime servers started:")
  for ((idx, sw) in nstf.switches.withIndex()) {
    println("  ${sw.id} -> localhost:${servers[idx].port()} (device_id=${idx + 1})")
  }
  println("\nPress Ctrl+C to stop.")

  servers.first().blockUntilShutdown()
  return ExitCode.SUCCESS
}

/** Starts one P4Runtime server per switch, pre-loaded with pipelines and entries. */
fun startNetworkServers(nstf: NetworkStf, basePort: Int): List<P4RuntimeServer> {
  val servers = mutableListOf<P4RuntimeServer>()
  val switchIds = mutableListOf<String>()

  for ((idx, sw) in nstf.switches.withIndex()) {
    val port = if (basePort == 0) 0 else basePort + idx
    val server = P4RuntimeServer(port = port, deviceId = idx.toLong() + 1)

    try {
      val config = loadPipelineConfig(sw.pipelinePath)
      server.simulator.loadPipeline(config)
      if (sw.stfPath != null) {
        val stf = StfFile.parse(sw.stfPath)
        installStfEntries(server.simulator, stf, config.p4Info)
      }
      val inlineLines = nstf.inlineEntries[sw.id]
      if (inlineLines != null) {
        val stf = StfFile.parse(inlineLines)
        installStfEntries(server.simulator, stf, config.p4Info)
      }
    } catch (e: Exception) {
      servers.forEach { it.stop() }
      throw NetworkServeException("switch '${sw.id}': ${e.message}", ExitCode.INTERNAL_ERROR)
    }

    try {
      server.start()
    } catch (e: Exception) {
      servers.forEach { it.stop() }
      throw NetworkServeException(
        "switch '${sw.id}': failed to start on port $port: ${e.message}",
        ExitCode.INTERNAL_ERROR,
      )
    }
    servers.add(server)
    switchIds.add(sw.id)
  }

  wireNetworkForwarding(NetworkTopology(nstf.links), switchIds, servers)

  return servers
}

/**
 * Registers forwarding subscribers on each switch's [PacketBroker] so that packets egressing on
 * linked ports are automatically injected into the connected switch.
 *
 * Uses the broker's existing subscriber model — no changes to [PacketBroker] or [P4RuntimeServer]
 * internals.
 */
private fun wireNetworkForwarding(
  topology: NetworkTopology,
  switchIds: List<String>,
  servers: List<P4RuntimeServer>,
) {
  if (topology.links.isEmpty()) return

  val brokerBySwitch = switchIds.zip(servers).associate { (id, server) -> id to server.broker }
  val linkMap = topology.toLinkMap()

  // Track recursion depth per thread to detect routing loops. The forwarding subscriber calls
  // dstBroker.processPacket(), which dispatches to subscribers (including this one) on the same
  // thread — so a thread-local counter is the right mechanism.
  val hopCount = ThreadLocal.withInitial { 0 }

  for ((switchId, broker) in brokerBySwitch) {
    broker.subscribe { result ->
      // TODO: handle alternative forks (action selectors) across switches.
      // See docs/LIMITATIONS.md, "Network simulation".
      check(result.possibleOutcomes.size <= 1) {
        "switch '$switchId': multiple possible outcomes (action selectors) not yet supported"
      }
      val outputs = result.possibleOutcomes.firstOrNull() ?: return@subscribe

      val hops = hopCount.get()
      if (hops >= NetworkSimulator.MAX_HOP_COUNT) {
        logger.warning(
          "cross-switch forwarding: hop limit (${NetworkSimulator.MAX_HOP_COUNT}) exceeded — routing loop?"
        )
        return@subscribe
      }
      hopCount.set(hops + 1)
      try {
        for (output in outputs) {
          val dst = linkMap[Endpoint(switchId, output.dataplaneEgressPort)] ?: continue
          val dstBroker = brokerBySwitch[dst.switchId] ?: continue
          try {
            dstBroker.processPacket(dst.port, output.payload.toByteArray())
          } catch (e: Exception) {
            logger.warning(
              "cross-switch forwarding $switchId:${output.dataplaneEgressPort} → $dst failed: ${e.message}"
            )
          }
        }
      } finally {
        hopCount.set(hops)
      }
    }
  }
}

private val logger = Logger.getLogger("fourward.cli.NetworkServe")

class NetworkServeException(message: String, val exitCode: Int) : RuntimeException(message)
