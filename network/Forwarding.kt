package fourward.network

import fourward.p4runtime.P4RuntimeServer
import fourward.simulator.Endpoint
import fourward.simulator.NetworkSimulator
import fourward.simulator.NetworkTopology
import java.util.logging.Logger

/**
 * Wires cross-switch packet forwarding between P4Runtime servers.
 *
 * Registers a subscriber on each switch's [fourward.p4runtime.PacketBroker] so that packets
 * egressing on linked ports are automatically injected into the connected switch. Uses the broker's
 * existing subscriber model — no changes to `PacketBroker` or `P4RuntimeServer` internals.
 *
 * A per-thread hop counter (same limit as [NetworkSimulator.MAX_HOP_COUNT]) prevents routing loops
 * from hanging. The forwarding is recursive via subscriber dispatch — each forwarded packet
 * triggers the connected switch's subscriber on the same thread, so a `ThreadLocal` counter is the
 * right mechanism.
 *
 * Programs with action selectors (multiple possible worlds per switch) are not supported and will
 * cause the subscriber to fail loudly. See `docs/LIMITATIONS.md`, "Network simulation".
 */
fun wireForwarding(topology: NetworkTopology, serversBySwitch: Map<String, P4RuntimeServer>) {
  if (topology.links.isEmpty()) return

  val brokerBySwitch = serversBySwitch.mapValues { (_, server) -> server.broker }
  val linkMap = topology.toLinkMap()
  val hopCount = ThreadLocal.withInitial { 0 }

  for ((switchId, broker) in brokerBySwitch) {
    broker.subscribe { result ->
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

private val logger = Logger.getLogger("fourward.network.Forwarding")
