package fourward.simulator

/**
 * A field access (e.g., `headers.ethernet.dstAddr`) resolved at pipeline-load time to an absolute
 * bit offset and width within the packet buffer.
 *
 * The interpreter caches a `Map<FieldAccess, ResolvedSlot>` keyed by IR `FieldAccess` node identity
 * and looks up the slot at runtime instead of walking the field-name path. Reads/writes against the
 * slot become a single `buffer.readBits(offset, width)` / `writeBits(offset, width, value)`.
 *
 * See `designs/flat_packet_buffer.md`.
 */
data class ResolvedSlot(val offset: Int, val width: Int, val kind: PrimitiveKind)
