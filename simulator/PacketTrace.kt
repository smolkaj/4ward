package fourward.simulator

/**
 * A record of what happened to a packet during pipeline execution, stored as an initial state plus
 * a sequence of deltas. This is the delta-encoded counterpart of the snapshot-based trace model —
 * see `designs/flat_packet_buffer.md`.
 *
 * Consumers usually iterate [events] forward, or call [replay] / [stateAt] to reconstruct the
 * packet buffer at a given point. Random-access state queries cost O(index) because we replay from
 * [initial] up to the requested event.
 */
data class PacketTrace(
  /** Packet buffer bytes captured at the entry point, before any delta was applied. */
  val initial: ByteArray,
  /** Deltas in the order they occurred during pipeline execution. */
  val events: List<TraceDelta>,
) {
  /** Reconstructs the packet buffer after all events have been applied. */
  fun replay(): PacketBuffer = stateAt(events.size)

  /**
   * Reconstructs the packet buffer after the first [eventCount] events have been applied. Useful
   * for trace debuggers and consumers that need the state at a specific point in execution.
   */
  fun stateAt(eventCount: Int): PacketBuffer {
    require(eventCount in 0..events.size) {
      "eventCount $eventCount out of range 0..${events.size}"
    }
    val buf = PacketBuffer.fromBytes(initial)
    for (i in 0 until eventCount) {
      events[i].applyTo(buf)
    }
    return buf
  }

  // Arrays don't have structural equality, so data-class equality would compare by reference.
  // Override.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is PacketTrace) return false
    return initial.contentEquals(other.initial) && events == other.events
  }

  override fun hashCode(): Int = 31 * initial.contentHashCode() + events.hashCode()
}

/**
 * A single recorded event in a [PacketTrace]. Either a state-changing delta or a control-flow
 * marker with no state effect.
 *
 * [FieldWrite] and [HeaderInvalidated] change the packet buffer; [ControlFlow] carries a semantic
 * event (table lookup, action call, drop, etc.) with no buffer impact.
 */
sealed interface TraceDelta {
  /** Applies this delta to [buffer]. Control-flow events are a no-op. */
  fun applyTo(buffer: PacketBuffer)

  /** A primitive field write at a known bit offset. */
  data class FieldWrite(val bitOffset: Int, val width: Int, val newValue: Long) : TraceDelta {
    override fun applyTo(buffer: PacketBuffer) {
      buffer.writeBits(bitOffset, width, newValue)
    }
  }

  /**
   * A header was invalidated — the validity bit is cleared and all fields are zeroed (P4 spec
   * §8.17). [bitOffset] is the start of the header layout within the buffer; [totalBits] spans the
   * full layout including the validity bit.
   */
  data class HeaderInvalidated(val bitOffset: Int, val totalBits: Int) : TraceDelta {
    override fun applyTo(buffer: PacketBuffer) {
      buffer.zeroRange(bitOffset, totalBits)
    }
  }

  /**
   * A control-flow marker: table lookup, action call, drop, parser transition, etc. Carries a
   * human-readable description; structured sub-types can be added as the interpreter grows.
   */
  data class ControlFlow(val description: String) : TraceDelta {
    override fun applyTo(buffer: PacketBuffer) {
      // No state change.
    }
  }
}
