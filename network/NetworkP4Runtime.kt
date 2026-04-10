package fourward.network

import fourward.ir.PipelineConfig
import fourward.p4runtime.P4RuntimeServer
import fourward.simulator.NetworkTopology

/**
 * Specification for a single switch in a multi-switch P4Runtime deployment.
 *
 * [installEntries] runs after the pipeline is loaded but before the server starts accepting RPCs.
 * Use it to install table entries, action profile members, PRE entries, etc.
 */
data class NetworkSwitch(
  val id: String,
  val pipelineConfig: PipelineConfig,
  val installEntries: (P4RuntimeServer) -> Unit = {},
)

/**
 * Thrown when a switch fails to initialize or start in [startNetworkServers]. All already-started
 * servers are stopped before this exception propagates.
 */
class NetworkStartFailure(val switchId: String, cause: Throwable) :
  RuntimeException("switch '$switchId': ${cause.message ?: cause::class.simpleName}", cause)

/**
 * Starts one P4Runtime server per switch, pre-loaded and wired for cross-switch forwarding.
 *
 * Switch `i` listens on port `basePort + i`. [basePort] of 0 assigns an ephemeral port to each
 * switch independently (useful for tests).
 *
 * @return servers keyed by switch ID, in insertion order
 * @throws NetworkStartFailure if any switch fails to configure or start
 */
fun startNetworkServers(
  topology: NetworkTopology,
  switches: List<NetworkSwitch>,
  basePort: Int = 0,
): Map<String, P4RuntimeServer> {
  val started = linkedMapOf<String, P4RuntimeServer>()

  for ((idx, sw) in switches.withIndex()) {
    val port = if (basePort == 0) 0 else basePort + idx
    val server = P4RuntimeServer(port = port, deviceId = idx.toLong() + 1)

    try {
      server.loadPipeline(sw.pipelineConfig)
      sw.installEntries(server)
      server.start()
    } catch (e: Exception) {
      started.values.forEach { it.stop() }
      throw NetworkStartFailure(sw.id, e)
    }
    started[sw.id] = server
  }

  wireForwarding(topology, started)
  return started
}
