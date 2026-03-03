package fourward.simulator

import fourward.sim.v1.Trace
import fourward.sim.v1.TraceEvent
import java.io.ByteArrayOutputStream

/**
 * The execution environment for a single packet traversal.
 *
 * [Environment] holds all mutable state during packet processing:
 * - Variable bindings (headers, metadata, local variables), organised as a stack of scopes to
 *   handle nested control blocks.
 * - The packet buffer (bytes not yet extracted by the parser).
 * - The execution trace being built up event by event.
 *
 * A new Environment is created for each [ProcessPacketRequest] and discarded afterwards. Table
 * state and extern instances live in [Simulator], not here.
 */
class Environment(payload: ByteArray) {

  // -------------------------------------------------------------------------
  // Packet buffer
  // -------------------------------------------------------------------------

  /** Remaining bytes in the input packet, consumed by parser extract(). */
  private val buffer: PacketBuffer = PacketBuffer(payload)

  /** Output packet bytes, written by deparser emit(). */
  private val outputBuffer = ByteArrayOutputStream()

  fun extractBytes(count: Int): ByteArray = buffer.read(count)

  fun emitBytes(bytes: ByteArray) {
    outputBuffer.write(bytes)
  }

  fun outputPayload(): ByteArray = outputBuffer.toByteArray()

  fun remainingInputBytes(): Int = buffer.remaining()

  /** Returns all bytes not yet consumed by the parser (the un-parsed packet body). */
  fun drainRemainingInput(): ByteArray = buffer.readAll()

  // -------------------------------------------------------------------------
  // Variable bindings
  // -------------------------------------------------------------------------

  private val scopes: ArrayDeque<MutableMap<String, Value>> = ArrayDeque()

  init {
    pushScope()
  } // top-level scope

  fun pushScope() {
    scopes.addLast(mutableMapOf())
  }

  fun popScope() {
    scopes.removeLast()
  }

  /** Defines a new variable in the innermost scope. */
  fun define(name: String, value: Value) {
    scopes.last()[name] = value
  }

  /**
   * Looks up a variable by name, searching from the innermost scope outward. Returns null if not
   * found.
   */
  fun lookup(name: String): Value? {
    for (scope in scopes.asReversed()) {
      scope[name]?.let {
        return it
      }
    }
    return null
  }

  /**
   * Updates an existing variable binding. Searches from the innermost scope outward, updating the
   * first match. Throws if the variable is not found.
   */
  fun update(name: String, value: Value) {
    for (scope in scopes.asReversed()) {
      if (name in scope) {
        scope[name] = value
        return
      }
    }
    error("undefined variable: $name")
  }

  // -------------------------------------------------------------------------
  // Trace
  // -------------------------------------------------------------------------

  private val traceEvents: MutableList<TraceEvent> = mutableListOf()

  fun addTraceEvent(event: TraceEvent) {
    traceEvents.add(event)
  }

  fun buildTrace(): Trace = Trace.newBuilder().addAllEvents(traceEvents).build()
}

/**
 * Thrown when the parser tries to extract more bytes than the packet contains.
 *
 * In v1model/BMv2, this corresponds to a `PacketTooShort` parser error. The packet is dropped
 * rather than propagating as a simulator processing failure.
 */
class PacketTooShortException(message: String) : Exception(message)

/** A simple byte-level cursor over a packet buffer. */
private class PacketBuffer(private val data: ByteArray) {
  private var offset: Int = 0

  fun remaining(): Int = data.size - offset

  fun readAll(): ByteArray = data.copyOfRange(offset, data.size).also { offset = data.size }

  fun read(count: Int): ByteArray {
    if (count > remaining()) {
      throw PacketTooShortException(
        "attempted to extract $count bytes but only ${remaining()} remain in packet"
      )
    }
    return data.copyOfRange(offset, offset + count).also { offset += count }
  }
}
