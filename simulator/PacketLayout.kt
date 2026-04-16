package fourward.simulator

/**
 * Top-level layout describing how an entire packet's runtime state is placed within a single
 * [PacketBuffer]. Computed once per pipeline at load time and reused across every packet.
 *
 * This is the optimal-design successor to the per-instance `HeaderVal` / `StructVal` storage:
 * instead of each header/struct allocating its own `HashMap`, every named runtime type a packet
 * touches (the headers struct, the metadata struct, `standard_metadata`, action locals) is laid out
 * at a known absolute bit offset within one contiguous buffer. The interpreter and architecture
 * code translate field accesses to absolute offsets via [ResolvedSlot]s without ever allocating
 * intermediate maps or wrapper objects.
 *
 * See `designs/flat_packet_buffer.md`.
 */
data class PacketLayout(
  /**
   * Absolute bit offset of each top-level named type within the packet buffer. Keys are the type
   * names referenced from the architecture's parser/control parameter list (e.g., the headers
   * struct type name, the user metadata struct type name, `standard_metadata_t`).
   */
  val typeOffsets: Map<String, Int>,
  /** Per-type header layouts. Reused as-is from the per-type [LayoutComputer]. */
  val headers: Map<String, HeaderLayout>,
  /** Per-type struct layouts. Reused as-is from the per-type [LayoutComputer]. */
  val structs: Map<String, StructLayout>,
  /** Per-type header-stack layouts. */
  val stacks: Map<String, HeaderStackLayout>,
  /** Total bit width of the packet buffer required to hold every laid-out type. */
  val totalBits: Int,
)
