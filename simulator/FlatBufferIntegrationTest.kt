package fourward.simulator

import fourward.ir.BehavioralConfig
import fourward.ir.BitType
import fourward.ir.DeviceConfig
import fourward.ir.FieldDecl
import fourward.ir.HeaderDecl
import fourward.ir.PipelineConfig
import fourward.ir.StructDecl
import fourward.ir.Type
import fourward.ir.TypeDecl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end integration: prove that the flat-buffer stack hangs together end-to-end.
 *
 * Takes a minimal synthetic pipeline (ethernet header in a headers struct + a metadata struct),
 * computes layouts, allocates a packet buffer, writes field values through views, forks the buffer,
 * mutates the fork, captures the whole thing as a delta-encoded trace, and replays the trace to
 * recover final state. Exercises: [computeLayouts], [PacketBuffer], [HeaderView], [StructView],
 * [TraceDelta], [PacketTrace].
 *
 * No interpreter involvement — this validates the design before that migration. See
 * `designs/flat_packet_buffer.md`.
 */
class FlatBufferIntegrationTest {

  /** A minimal pipeline: headers = { ethernet }, meta = { ingress_port, drop }. */
  private val pipeline =
    PipelineConfig.newBuilder()
      .setDevice(
        DeviceConfig.newBuilder()
          .setBehavioral(
            BehavioralConfig.newBuilder()
              .addTypes(
                typeDecl(
                  "ethernet_t",
                  header("dstAddr" to bit(48), "srcAddr" to bit(48), "etherType" to bit(16)),
                )
              )
              .addTypes(typeDecl("headers_t", struct("ethernet" to named("ethernet_t"))))
              .addTypes(typeDecl("meta_t", struct("ingress_port" to bit(9), "drop" to bool())))
              .addTypes(
                typeDecl(
                  "packet_state_t",
                  struct("hdrs" to named("headers_t"), "meta" to named("meta_t")),
                )
              )
          )
      )
      .build()

  @Test
  fun `fork copies buffer, mutations are independent, trace replays faithfully`() {
    // 1. Compute layouts from the pipeline config.
    val layouts = computeLayouts(pipeline)
    val packetStateLayout = layouts.structs.getValue("packet_state_t")

    // 2. Allocate a packet buffer sized for the top-level layout.
    val parentBuffer = PacketBuffer(packetStateLayout.totalBits)
    val parentView = StructView(parentBuffer, packetStateLayout)

    // 3. "Parse" phase: fill in the ethernet header and set metadata.
    val eth = parentView.struct("hdrs").header("ethernet")
    eth["dstAddr"] = BitVal(0xAABB_CCDD_EEFFL, 48)
    eth["srcAddr"] = BitVal(0x1122_3344_5566L, 48)
    eth["etherType"] = BitVal(0x0800L, 16)
    eth.isValid = true

    val meta = parentView.struct("meta")
    meta["ingress_port"] = BitVal(3L, 9)

    // Sanity check: writes round-trip.
    assertEquals(BitVal(0x0800L, 16), eth["etherType"])
    assertTrue(eth.isValid)
    assertEquals(BitVal(3L, 9), meta["ingress_port"])

    // 4. Capture initial state for the trace.
    val initialBytes = parentBuffer.toBytes()

    // 5. "Fork" phase: copy the buffer. The fork is a second, independent copy of the same state.
    val forkBuffer = parentBuffer.copyOf()
    val forkView = StructView(forkBuffer, packetStateLayout)

    // The fork sees the parent's state.
    val forkEth = forkView.struct("hdrs").header("ethernet")
    assertEquals(BitVal(0xAABB_CCDD_EEFFL, 48), forkEth["dstAddr"])
    assertTrue(forkEth.isValid)

    // 6. "Action" phase: modify the fork. Build up a trace of what we did.
    val forkEvents = mutableListOf<TraceDelta>()
    forkEvents += TraceDelta.ControlFlow("action: rewrite_dst_mac")

    // Compute the global bit offset of hdrs.ethernet.dstAddr within the packet buffer.
    val hdrsOffset = (packetStateLayout.members.getValue("hdrs") as NestedStruct).offset
    val ethOffset =
      (layouts.structs.getValue("headers_t").members.getValue("ethernet") as NestedHeader).offset
    val dstAddrSlot = layouts.headers.getValue("ethernet_t").fields.getValue("dstAddr")
    val dstAddrGlobalOffset = hdrsOffset + ethOffset + dstAddrSlot.offset

    // Apply: rewrite dstAddr on the fork.
    val newDst = 0xDEAD_BEEF_CAFEL
    forkEth["dstAddr"] = BitVal(newDst, 48)
    forkEvents +=
      TraceDelta.FieldWrite(bitOffset = dstAddrGlobalOffset, width = 48, newValue = newDst)

    // Also drop the packet in metadata.
    val metaOffset = (packetStateLayout.members.getValue("meta") as NestedStruct).offset
    val dropSlot = layouts.structs.getValue("meta_t").members.getValue("drop") as PrimitiveField
    val dropGlobalOffset = metaOffset + dropSlot.offset
    forkView.struct("meta")["drop"] = BitVal(1L, 1)
    forkEvents += TraceDelta.FieldWrite(bitOffset = dropGlobalOffset, width = 1, newValue = 1L)
    forkEvents += TraceDelta.ControlFlow("drop")

    // 7. Invariant check: parent is untouched.
    val parentEth = parentView.struct("hdrs").header("ethernet")
    assertEquals(BitVal(0xAABB_CCDD_EEFFL, 48), parentEth["dstAddr"])
    assertEquals(BitVal(0L, 1), parentView.struct("meta")["drop"])

    // 8. Build the trace: initial snapshot + fork's event log.
    val trace = PacketTrace(initial = initialBytes, events = forkEvents)

    // 9. Replay the trace and verify final state matches the fork exactly.
    val replayed = trace.replay()
    assertArrayEquals(forkBuffer.toBytes(), replayed.toBytes())

    val replayedView = StructView(replayed, packetStateLayout)
    val replayedEth = replayedView.struct("hdrs").header("ethernet")
    assertEquals(BitVal(newDst, 48), replayedEth["dstAddr"])
    assertEquals(BitVal(0x0800L, 16), replayedEth["etherType"]) // unchanged by deltas
    assertEquals(BitVal(1L, 1), replayedView.struct("meta")["drop"])

    // 10. Also verify stateAt() at an intermediate event gives an intermediate state.
    //     Event index 1 = after the first FieldWrite (dstAddr rewrite) but before the drop.
    val midway = trace.stateAt(2)
    val midEth = StructView(midway, packetStateLayout).struct("hdrs").header("ethernet")
    assertEquals(BitVal(newDst, 48), midEth["dstAddr"])
    // drop hasn't been applied yet
    assertEquals(BitVal(0L, 1), StructView(midway, packetStateLayout).struct("meta")["drop"])
  }

  @Test
  fun `packet state fits in a small buffer`() {
    // Validates the working-set-size claim: a realistic packet's state is on the order of a
    // cache line, not a heap-tree.
    val layouts = computeLayouts(pipeline)
    val packetStateLayout = layouts.structs.getValue("packet_state_t")

    // ethernet (48+48+16+1=113) + headers.ethernet nested + meta.ingress_port (9) + meta.drop (1).
    // packet_state nests headers_t (113) and meta_t (10) -> 123 bits total.
    assertEquals(113 + 9 + 1, packetStateLayout.totalBits)
    val buffer = PacketBuffer(packetStateLayout.totalBits)
    // Buffer is a literal handful of bytes — fits in a single cache line.
    assertTrue("buffer size: ${buffer.toBytes().size} bytes", buffer.toBytes().size <= 64)
  }

  @Test
  fun `header invalidation zeros the header's portion of a shared buffer only`() {
    // Validates that the HeaderInvalidated delta's (bitOffset, totalBits) reliably targets just
    // one header in a larger packet state — important for the interpreter migration, where
    // setInvalid() on one header must not disturb its neighbours.
    val layouts = computeLayouts(pipeline)
    val packetStateLayout = layouts.structs.getValue("packet_state_t")

    val buffer = PacketBuffer(packetStateLayout.totalBits)
    val root = StructView(buffer, packetStateLayout)

    // Fill ethernet and metadata.
    val eth = root.struct("hdrs").header("ethernet")
    eth["dstAddr"] = BitVal(0xFFFF_FFFF_FFFFL, 48)
    eth["srcAddr"] = BitVal(0xFFFF_FFFF_FFFFL, 48)
    eth["etherType"] = BitVal(0xFFFFL, 16)
    eth.isValid = true
    root.struct("meta")["ingress_port"] = BitVal(0x1AAL, 9)
    root.struct("meta")["drop"] = BitVal(1L, 1)

    // Invalidate ethernet via the delta (not via eth.isValid=false, to exercise the delta path).
    val ethOffsetInPacket =
      (packetStateLayout.members.getValue("hdrs") as NestedStruct).offset +
        (layouts.structs.getValue("headers_t").members.getValue("ethernet") as NestedHeader).offset
    val ethTotalBits = layouts.headers.getValue("ethernet_t").totalBits
    TraceDelta.HeaderInvalidated(ethOffsetInPacket, ethTotalBits).applyTo(buffer)

    // Ethernet is now zeroed and invalid.
    assertEquals(BitVal(0L, 48), eth["dstAddr"])
    assertFalse(eth.isValid)
    // But metadata is intact.
    assertEquals(BitVal(0x1AAL, 9), root.struct("meta")["ingress_port"])
    assertEquals(BitVal(1L, 1), root.struct("meta")["drop"])
  }

  // =====================================================================
  // Helpers — tiny proto-building DSL.
  // =====================================================================

  private fun typeDecl(name: String, content: (TypeDecl.Builder) -> Unit): TypeDecl =
    TypeDecl.newBuilder().setName(name).also(content).build()

  private fun header(vararg fields: Pair<String, Type>): (TypeDecl.Builder) -> Unit = { b ->
    b.setHeader(HeaderDecl.newBuilder().addAllFields(fields.map { fieldDecl(it.first, it.second) }))
  }

  private fun struct(vararg fields: Pair<String, Type>): (TypeDecl.Builder) -> Unit = { b ->
    b.setStruct(StructDecl.newBuilder().addAllFields(fields.map { fieldDecl(it.first, it.second) }))
  }

  private fun fieldDecl(name: String, type: Type): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(type).build()

  private fun bit(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

  private fun bool(): Type = Type.newBuilder().setBoolean(true).build()

  private fun named(name: String): Type = Type.newBuilder().setNamed(name).build()
}
