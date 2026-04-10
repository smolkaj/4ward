package fourward.network

import fourward.p4runtime.P4RuntimeServer
import fourward.simulator.NetworkSimulator
import fourward.simulator.NetworkTopology
import fourward.simulator.routePacketOutputs
import java.util.logging.Logger

/**
 * Wires cross-switch packet forwarding between P4Runtime servers.
 *
 * Registers a packet subscriber on each switch so that packets egressing on linked ports are
 * automatically injected into the connected switch. Uses the existing subscriber API on
 * [P4RuntimeServer] — no changes to internals.
 *
 * Shares the per-hop routing primitive ([routePacketOutputs]) with [NetworkSimulator], so both
 * implementations agree on what "linked output" means and how action-selector programs are
 * rejected. The overall traversal structure is different: [NetworkSimulator] pulls recursively to
 * build a trace tree, while this function chains subscribers reactively.
 *
 * ## Hop limit
 *
 * A `ThreadLocal` counter (limit: [NetworkSimulator.MAX_HOP_COUNT]) prevents routing loops from
 * hanging. **This relies on [fourward.p4runtime.PacketBroker.subscribe]'s synchronous dispatch**:
 * the forwarding subscriber calls `dstServer.processPacket`, which dispatches to subscribers
 * (including this one) on the same thread before returning. If broker dispatch ever becomes async
 * (thread pool, coroutine queue), this counter silently stops working and loops will hang. See
 * `docs/LIMITATIONS.md`.
 */
fun wireForwarding(topology: NetworkTopology, serversBySwitch: Map<String, P4RuntimeServer>) {
  if (topology.links.isEmpty()) return

  val linkMap = topology.toLinkMap()
  val hopCount = ThreadLocal.withInitial { 0 }

  for ((switchId, server) in serversBySwitch) {
    server.onPacketProcessed { result ->
      val hops = hopCount.get()
      if (hops >= NetworkSimulator.MAX_HOP_COUNT) {
        logger.warning(
          "cross-switch forwarding: hop limit (${NetworkSimulator.MAX_HOP_COUNT}) exceeded — routing loop?"
        )
        return@onPacketProcessed
      }
      hopCount.set(hops + 1)
      try {
        routePacketOutputs(
          switchId = switchId,
          possibleOutcomes = result.possibleOutcomes,
          linkMap = linkMap,
          onForward = { dst, payload ->
            val dstServer = serversBySwitch[dst.switchId] ?: return@routePacketOutputs
            try {
              dstServer.processPacket(dst.port, payload.toByteArray())
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
              logger.warning("cross-switch forwarding $switchId → $dst failed: ${e.message}")
            }
          },
          onEdge = { _, _ ->
            // Edge outputs are delivered to local subscribers by the broker itself.
          },
        )
      } finally {
        hopCount.set(hops)
      }
    }
  }
}

private val logger = Logger.getLogger("fourward.network.Forwarding")
