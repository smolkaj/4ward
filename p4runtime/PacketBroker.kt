package fourward.p4runtime

import fourward.sim.SimulatorProto.OutputPacket
import fourward.sim.SimulatorProto.TraceTree
import fourward.simulator.ProcessPacketResult
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Fan-out layer between packet sources and the simulator.
 *
 * All callers — [DataplaneService.injectPacket], [P4RuntimeService] PacketOut — go through
 * [processPacket]. The broker delegates to the simulator and delivers the result to all [subscribe]
 * subscribers (powering the [DataplaneService.subscribeResults] RPC).
 *
 * CPU-port routing (PacketOut → PacketIn) is handled by [P4RuntimeService.handlePacketOut], not
 * here, because it requires pipeline state (codec, translator) that only P4RuntimeService has.
 *
 * @param processPacketFn function that processes a single packet (wraps
 *   [fourward.simulator.Simulator.processPacket]).
 */
class PacketBroker(
  private val processPacketFn: (ingressPort: Int, payload: ByteArray) -> ProcessPacketResult
) {

  /**
   * Delivered to each [subscribe] subscriber for every processed packet.
   *
   * Not a `data class` because [ByteArray] has identity-based `equals`/`hashCode`, which would make
   * the generated `equals` silently wrong.
   */
  class SubscriptionResult(
    val ingressPort: Int,
    val payload: ByteArray,
    val possibleOutcomes: List<List<OutputPacket>>,
    val trace: TraceTree,
  )

  /** Handle returned by [subscribe]; call [unsubscribe] to stop receiving results. */
  fun interface SubscriptionHandle {
    fun unsubscribe()
  }

  private val subscribers =
    java.util.concurrent.CopyOnWriteArrayList<(SubscriptionResult) -> Unit>()

  /**
   * Processes a packet through the simulator and dispatches results.
   * 1. Calls the simulator.
   * 2. Delivers the result to all subscribers.
   * 3. Returns the result to the caller.
   *
   * Subscriber exceptions are caught to protect the caller and other subscribers. Well-behaved
   * subscribers handle their own errors (e.g. by closing their stream); this catch is a safety net.
   */
  fun processPacket(ingressPort: Int, payload: ByteArray): ProcessPacketResult {
    val result = processPacketFn(ingressPort, payload)

    if (subscribers.isNotEmpty()) {
      val subResult =
        SubscriptionResult(ingressPort, payload, result.possibleOutcomes, result.trace)
      for (subscriber in subscribers) {
        try {
          subscriber(subResult)
        } catch (
          @Suppress("TooGenericExceptionCaught") // Safety net: any subscriber failure must not
          e: Exception // crash the sender or block other subscribers.
        ) {
          logger.log(Level.WARNING, "Subscriber threw during packet dispatch", e)
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

  private companion object {
    val logger: Logger = Logger.getLogger(PacketBroker::class.java.name)
  }
}
