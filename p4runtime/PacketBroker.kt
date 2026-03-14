package fourward.p4runtime

import fourward.sim.v1.SimulatorProto.OutputPacket
import fourward.sim.v1.SimulatorProto.TraceTree
import fourward.simulator.ProcessPacketResult

/**
 * Sits between all packet sources and the simulator. Owns CPU port routing and result distribution.
 *
 * All callers — [DataplaneService.injectPacket], [P4RuntimeService] PacketOut — go through
 * [processPacket]. The broker:
 * 1. Delegates to the simulator.
 * 2. Delivers the result to all [subscribe] subscribers.
 * 3. Routes CPU-port outputs to the [PacketIn listener][setPacketInListener].
 * 4. Returns the outputs and trace to the caller.
 *
 * @param simulator function that processes a single packet
 *   (wraps [fourward.simulator.Simulator.processPacket]).
 * @param cpuPort the data-plane CPU port number, or null if the CPU port is disabled.
 */
class PacketBroker(
  private val simulator: (ingressPort: Int, payload: ByteArray) -> ProcessPacketResult,
  private val cpuPort: Int?,
) {

  /** Delivered to each [subscribe] subscriber for every processed packet. */
  data class SubscriptionResult(
    val ingressPort: Int,
    val payload: ByteArray,
    val outputPackets: List<OutputPacket>,
    val trace: TraceTree,
  )

  /** Handle returned by [subscribe]; call [unsubscribe] to stop receiving results. */
  fun interface SubscriptionHandle {
    fun unsubscribe()
  }

  private val subscribers = mutableListOf<(SubscriptionResult) -> Unit>()
  private var packetInListener: ((OutputPacket) -> Unit)? = null

  /**
   * Processes a packet through the simulator and dispatches results.
   *
   * 1. Calls the simulator.
   * 2. Delivers the result to all subscribers.
   * 3. Routes CPU-port outputs to the PacketIn listener.
   * 4. Returns the result to the caller.
   */
  fun processPacket(ingressPort: Int, payload: ByteArray): ProcessPacketResult {
    val result = simulator(ingressPort, payload)

    // Deliver to all subscribers.
    if (subscribers.isNotEmpty()) {
      val subResult = SubscriptionResult(ingressPort, payload, result.outputPackets, result.trace)
      for (subscriber in subscribers) {
        subscriber(subResult)
      }
    }

    // Route CPU-port outputs to the PacketIn listener.
    val listener = packetInListener
    if (listener != null && cpuPort != null) {
      for (outputPacket in result.outputPackets) {
        if (outputPacket.egressPort == cpuPort) {
          listener(outputPacket)
        }
      }
    }

    return result
  }

  /** Registers a subscriber that receives results for every processed packet. */
  fun subscribe(callback: (SubscriptionResult) -> Unit): SubscriptionHandle {
    subscribers.add(callback)
    return SubscriptionHandle { subscribers.remove(callback) }
  }

  /** Sets the listener for packets egressing on the CPU port (PacketIn). */
  fun setPacketInListener(listener: (OutputPacket) -> Unit) {
    packetInListener = listener
  }

  /** Clears the PacketIn listener. CPU-port outputs will be silently dropped. */
  fun clearPacketInListener() {
    packetInListener = null
  }
}
