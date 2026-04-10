package fourward.simulator

import com.google.protobuf.ByteString
import fourward.sim.OutputPacket
import fourward.sim.TraceTree

/**
 * A network of P4 switches connected by point-to-point links.
 *
 * Composes N [Simulator] instances with a [NetworkTopology], forwarding packets across links using
 * the existing [Simulator.processPacket] API. Individual simulators are unchanged — the network
 * layer is purely additive.
 *
 * Packet delivery is instant. A [MAX_HOP_COUNT] limit prevents infinite loops from routing
 * misconfigurations. Single-threaded — callers must serialise concurrent requests (inherited from
 * [Simulator]).
 */
class NetworkSimulator(topology: NetworkTopology) {

  private val simulators = mutableMapOf<String, Simulator>()
  private val linkMap: Map<Endpoint, Endpoint> = topology.toLinkMap()

  /**
   * Adds a switch to the network and returns its [Simulator] for configuration (loading pipelines,
   * installing table entries).
   */
  fun addSwitch(id: String): Simulator {
    require(id !in simulators) { "switch '$id' already exists" }
    return Simulator().also { simulators[id] = it }
  }

  /**
   * Processes a packet through the network. Returns a [NetworkHop] tree capturing the full
   * cross-switch journey.
   */
  fun processPacket(switchId: String, ingressPort: Int, payload: ByteArray): NetworkHop {
    return processHop(switchId, ingressPort, ByteString.copyFrom(payload), hopCount = 0)
  }

  private fun processHop(
    switchId: String,
    ingressPort: Int,
    payload: ByteString,
    hopCount: Int,
  ): NetworkHop {
    check(hopCount < MAX_HOP_COUNT) {
      "switch '$switchId': hop limit ($MAX_HOP_COUNT) exceeded — routing loop?"
    }

    val sim = checkNotNull(simulators[switchId]) { "unknown switch: '$switchId'" }
    val result = sim.processPacket(ingressPort, payload.toByteArray())

    val edgeOutputs = mutableListOf<EdgeOutput>()
    val nextHops = mutableListOf<NetworkHop>()
    routePacketOutputs(
      switchId = switchId,
      possibleOutcomes = result.possibleOutcomes,
      linkMap = linkMap,
      onForward = { dst, pl -> nextHops.add(processHop(dst.switchId, dst.port, pl, hopCount + 1)) },
      onEdge = { port, pl -> edgeOutputs.add(EdgeOutput(switchId, port, pl)) },
    )

    return NetworkHop(
      switchId = switchId,
      ingressPort = ingressPort,
      ingressPayload = payload,
      trace = result.trace,
      edgeOutputs = edgeOutputs,
      nextHops = nextHops,
    )
  }

  companion object {
    const val MAX_HOP_COUNT = 64
  }
}

/**
 * Routes the outputs of a single switch's packet processing to either a linked switch or an edge
 * sink. This is the shared "one-step forwarding" primitive used by [NetworkSimulator] (which walks
 * the topology recursively to build a trace tree) and by the P4Runtime forwarding subscribers
 * (which chain broker calls reactively). Having both use this helper ensures they agree on what
 * "output egressing on a linked port" means and how action-selector programs are rejected.
 *
 * Takes [possibleOutcomes] directly instead of a wrapper type so it works with both
 * [ProcessPacketResult] and the P4Runtime subscriber's result type.
 *
 * Fails loudly if there is more than one possible world — action selectors across switches are not
 * supported (see `docs/LIMITATIONS.md`).
 */
inline fun routePacketOutputs(
  switchId: String,
  possibleOutcomes: List<List<OutputPacket>>,
  linkMap: Map<Endpoint, Endpoint>,
  onForward: (dst: Endpoint, payload: ByteString) -> Unit,
  onEdge: (egressPort: Int, payload: ByteString) -> Unit,
) {
  check(possibleOutcomes.size <= 1) {
    "switch '$switchId': multiple possible outcomes (action selectors) not yet supported"
  }
  val outputs = possibleOutcomes.firstOrNull() ?: return
  for (output in outputs) {
    val dst = linkMap[Endpoint(switchId, output.dataplaneEgressPort)]
    if (dst != null) {
      onForward(dst, output.payload)
    } else {
      onEdge(output.dataplaneEgressPort, output.payload)
    }
  }
}

/** A switch port: (switch ID, port number). */
data class Endpoint(val switchId: String, val port: Int) {
  override fun toString(): String = "$switchId:$port"
}

/** A point-to-point link between two switch ports. Bidirectional. */
data class Link(val a: Endpoint, val b: Endpoint)

/** A set of switches connected by point-to-point links. */
data class NetworkTopology(val links: List<Link>) {
  /**
   * Builds a bidirectional endpoint → endpoint map from the links. Validates that no port is
   * connected to multiple links.
   */
  fun toLinkMap(): Map<Endpoint, Endpoint> = buildMap {
    for (link in links) {
      require(link.a !in this) { "port ${link.a} is connected to multiple links" }
      require(link.b !in this) { "port ${link.b} is connected to multiple links" }
      put(link.a, link.b)
      put(link.b, link.a)
    }
  }
}

/** A packet that exited the network on an edge port (a port not connected to any link). */
data class EdgeOutput(val switchId: String, val egressPort: Int, val payload: ByteString)

/**
 * One switch processing one packet in the network trace tree.
 *
 * Each hop records what the switch received ([ingressPayload]), the full per-switch [trace] tree,
 * and the results: [edgeOutputs] for packets leaving the network, and [nextHops] for packets
 * forwarded to other switches via internal links. The tree branches when packets fan out
 * (multicast, clone) — one child hop per copy.
 */
data class NetworkHop(
  val switchId: String,
  val ingressPort: Int,
  val ingressPayload: ByteString,
  val trace: TraceTree,
  val edgeOutputs: List<EdgeOutput>,
  val nextHops: List<NetworkHop>,
)
