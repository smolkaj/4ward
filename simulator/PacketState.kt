package fourward.simulator

/**
 * Per-packet runtime state: one [PacketBuffer] sized by the pipeline's [PacketLayout], plus a trace
 * builder for delta events. Replaces the per-instance `HeaderVal` / `StructVal` heap-tree
 * representation.
 *
 * `PacketState.fork()` copies the buffer (one bulk memcpy of ~256 bytes for a typical packet) and
 * forks the trace. Used by `V1ModelArchitecture` when re-executing fork branches; the parent and
 * child branches mutate independent buffers without touching each other's state.
 *
 * See `designs/flat_packet_buffer.md`.
 */
class PacketState(
  val buffer: PacketBuffer,
  val layout: PacketLayout,
  val trace: TraceBuilder = TraceBuilder(),
) {
  /** Allocates a fresh, zero-initialised state for [layout]. */
  constructor(layout: PacketLayout) : this(PacketBuffer(layout.totalBits), layout)

  /** Returns an independent copy with its own buffer and forked trace. */
  fun fork(): PacketState = PacketState(buffer.copyOf(), layout, trace.fork())
}

/**
 * Accumulates [TraceDelta]s during pipeline execution. The interpreter calls `emit*` methods at
 * field writes and control-flow events; consumers call [build] at the end to materialise a
 * [PacketTrace].
 *
 * Forking a builder shares the parent's accumulated events by snapshot — the child writes only its
 * own future events to a fresh list. This matches the V1Model semantics where each fork branch's
 * trace is the parent's prefix plus the branch's own deltas.
 */
class TraceBuilder(private val initial: List<TraceDelta> = emptyList()) {
  private val own: MutableList<TraceDelta> = mutableListOf()

  fun emitFieldWrite(bitOffset: Int, width: Int, newValue: Long) {
    own.add(TraceDelta.FieldWrite(bitOffset, width, newValue))
  }

  fun emitHeaderInvalidated(bitOffset: Int, totalBits: Int) {
    own.add(TraceDelta.HeaderInvalidated(bitOffset, totalBits))
  }

  fun emitControlFlow(description: String) {
    own.add(TraceDelta.ControlFlow(description))
  }

  /** Returns a child builder that shares this builder's events as its prefix. */
  fun fork(): TraceBuilder = TraceBuilder(initial = initial + own)

  /** Returns the events accumulated so far (prefix + own), in order. */
  fun events(): List<TraceDelta> = initial + own

  /** Builds an immutable [PacketTrace] from [initialBytes] and the accumulated events. */
  fun build(initialBytes: ByteArray): PacketTrace =
    PacketTrace(initial = initialBytes, events = events())
}
