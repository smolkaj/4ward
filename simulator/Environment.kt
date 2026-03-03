package fourward.simulator

import fourward.sim.v1.Trace
import fourward.sim.v1.TraceEvent

/**
 * The execution environment for a single packet traversal.
 *
 * [Environment] holds all mutable state during packet processing:
 *
 *   - Variable bindings (headers, metadata, local variables), organised as a
 *     stack of scopes to handle nested control blocks.
 *   - The packet buffer (bytes not yet extracted by the parser).
 *   - The execution trace being built up event by event.
 *
 * A new Environment is created for each [ProcessPacketRequest] and discarded
 * afterwards. Table state and extern instances live in [Simulator], not here.
 */
class Environment(payload: ByteArray) {

    // -------------------------------------------------------------------------
    // Packet buffer
    // -------------------------------------------------------------------------

    /** Remaining bytes in the input packet, consumed by parser extract(). */
    private val buffer: PacketBuffer = PacketBuffer(payload)

    /** Output packet bytes, written by deparser emit(). */
    private val outputBuffer: MutableList<Byte> = mutableListOf()

    fun extractBytes(count: Int): ByteArray = buffer.read(count)

    fun emitBytes(bytes: ByteArray) { outputBuffer.addAll(bytes.toList()) }

    fun outputPayload(): ByteArray = outputBuffer.toByteArray()

    fun remainingInputBytes(): Int = buffer.remaining()

    // -------------------------------------------------------------------------
    // Variable bindings
    // -------------------------------------------------------------------------

    private val scopes: ArrayDeque<MutableMap<String, Value>> = ArrayDeque()

    init { pushScope() }  // top-level scope

    fun pushScope()  { scopes.addLast(mutableMapOf()) }
    fun popScope()   { scopes.removeLast() }

    /** Defines a new variable in the innermost scope. */
    fun define(name: String, value: Value) {
        scopes.last()[name] = value
    }

    /**
     * Looks up a variable by name, searching from the innermost scope outward.
     * Returns null if not found.
     */
    fun lookup(name: String): Value? {
        for (scope in scopes.reversed()) {
            scope[name]?.let { return it }
        }
        return null
    }

    /**
     * Updates an existing variable binding. Searches from the innermost scope
     * outward, updating the first match. Throws if the variable is not found.
     */
    fun update(name: String, value: Value) {
        for (scope in scopes.reversed()) {
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

    fun buildTrace(): Trace =
        Trace.newBuilder()
            .addAllEvents(traceEvents)
            .build()
}

/** A simple byte-level cursor over a packet buffer. */
private class PacketBuffer(private val data: ByteArray) {
    private var offset: Int = 0

    fun remaining(): Int = data.size - offset

    fun read(count: Int): ByteArray {
        require(count <= remaining()) {
            "attempted to extract $count bytes but only ${remaining()} remain in packet"
        }
        return data.copyOfRange(offset, offset + count).also { offset += count }
    }
}
