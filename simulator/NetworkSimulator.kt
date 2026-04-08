package fourward.simulator

import fourward.ir.PipelineConfig
import fourward.sim.SimulatorProto.TraceTree

/**
 * A network of P4 switches connected by point-to-point links.
 *
 * Composes N [Simulator] instances with a [NetworkTopology], forwarding packets across links using
 * the existing [Simulator.processPacket] API. Individual simulators are unchanged — the network
 * layer is purely additive.
 *
 * Packet delivery is instant and FIFO per link. A [MAX_HOP_COUNT] limit prevents infinite loops
 * from routing misconfigurations.
 */
class NetworkSimulator(private val topology: NetworkTopology) {

  private val simulators = mutableMapOf<String, Simulator>()

  /** Link lookup: (switchId, port) → (switchId, port). Built from topology, bidirectional. */
  private val linkMap: Map<Pair<String, Int>, Pair<String, Int>> = buildMap {
    for (link in topology.links) {
      val a = Pair(link.switchA, link.portA)
      val b = Pair(link.switchB, link.portB)
      require(a !in this) { "port ${link.switchA}:${link.portA} is connected to multiple links" }
      require(b !in this) { "port ${link.switchB}:${link.portB} is connected to multiple links" }
      put(a, b)
      put(b, a)
    }
  }

  /**
   * Adds a switch to the network and returns its [Simulator] for configuration (loading pipelines,
   * installing table entries).
   */
  fun addSwitch(id: String): Simulator {
    require(id !in simulators) { "switch '$id' already exists" }
    return Simulator().also { simulators[id] = it }
  }

  /**
   * Processes a packet through the network, following it across links until all copies either exit
   * on edge ports, are dropped, or exceed the hop limit.
   *
   * Returns a [NetworkHop] tree capturing the full cross-switch journey. For packets that fan out
   * (multicast, clone), the tree branches — one child hop per copy that crosses a link.
   *
   * For the walking skeleton, only the first possible outcome (world) from each switch is followed.
   * Handling alternative forks (action selectors) across switches is future work.
   */
  fun processPacket(switchId: String, ingressPort: Int, payload: ByteArray): NetworkHop {
    return processHop(switchId, ingressPort, payload, hopCount = 0)
  }

  private fun processHop(
    switchId: String,
    ingressPort: Int,
    payload: ByteArray,
    hopCount: Int,
  ): NetworkHop {
    check(hopCount <= MAX_HOP_COUNT) {
      "hop limit ($MAX_HOP_COUNT) exceeded — routing loop?"
    }

    val sim = checkNotNull(simulators[switchId]) { "unknown switch: '$switchId'" }
    val result = sim.processPacket(ingressPort, payload)

    // TODO: handle alternative forks (action selectors) across switches.
    val outputs = result.possibleOutcomes.firstOrNull() ?: emptyList()

    val edgeOutputs = mutableListOf<EdgeOutput>()
    val nextHops = mutableListOf<NetworkHop>()

    for (output in outputs) {
      val dst = linkMap[Pair(switchId, output.dataplaneEgressPort)]
      if (dst != null) {
        nextHops.add(processHop(dst.first, dst.second, output.payload.toByteArray(), hopCount + 1))
      } else {
        edgeOutputs.add(EdgeOutput(switchId, output.dataplaneEgressPort, output.payload.toByteArray()))
      }
    }

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

/** A point-to-point link between two switch ports. Bidirectional. */
data class Link(val switchA: String, val portA: Int, val switchB: String, val portB: Int)

/** A set of switches connected by point-to-point links. */
data class NetworkTopology(val links: List<Link>)

/** A packet that exited the network on an edge port (a port not connected to any link). */
data class EdgeOutput(val switchId: String, val egressPort: Int, val payload: ByteArray)

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
  val ingressPayload: ByteArray,
  val trace: TraceTree,
  val edgeOutputs: List<EdgeOutput>,
  val nextHops: List<NetworkHop>,
)
