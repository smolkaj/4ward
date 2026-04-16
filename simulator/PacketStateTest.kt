package fourward.simulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [PacketState] and [TraceBuilder]. */
class PacketStateTest {

  private val ethernet =
    HeaderLayout(
      typeName = "ethernet_t",
      fields = linkedMapOf("dst" to FieldSlot(0, 48, PrimitiveKind.BIT)),
      validBitOffset = 48,
    )
  private val layout =
    PacketLayout(
      typeOffsets = mapOf("ethernet_t" to 0),
      headers = mapOf("ethernet_t" to ethernet),
      structs = emptyMap(),
      stacks = emptyMap(),
      totalBits = ethernet.totalBits,
    )

  @Test
  fun `default constructor allocates a zero buffer at least layout size`() {
    val state = PacketState(layout)
    assertTrue(
      "buffer ${state.buffer.bitSize} bits should hold at least ${layout.totalBits} bits",
      state.buffer.bitSize >= layout.totalBits,
    )
    val ethView = HeaderView(state.buffer, ethernet, base = 0)
    assertEquals(BitVal(0L, 48), ethView["dst"])
  }

  @Test
  fun `fork copies the buffer, mutations are independent`() {
    val parent = PacketState(layout)
    HeaderView(parent.buffer, ethernet)["dst"] = BitVal(0x111111111111L, 48)

    val child = parent.fork()
    HeaderView(child.buffer, ethernet)["dst"] = BitVal(0x222222222222L, 48)

    assertEquals(BitVal(0x111111111111L, 48), HeaderView(parent.buffer, ethernet)["dst"])
    assertEquals(BitVal(0x222222222222L, 48), HeaderView(child.buffer, ethernet)["dst"])
    assertNotSame(parent.buffer, child.buffer)
  }

  @Test
  fun `trace events accumulate via the builder`() {
    val state = PacketState(layout)
    state.trace.emitFieldWrite(0, 48, 0xAAAA_AAAA_AAAAL)
    state.trace.emitControlFlow("table_hit")
    state.trace.emitHeaderInvalidated(0, ethernet.totalBits)

    val events = state.trace.events()
    assertEquals(3, events.size)
    assertEquals(TraceDelta.FieldWrite(0, 48, 0xAAAA_AAAA_AAAAL), events[0])
    assertEquals(TraceDelta.ControlFlow("table_hit"), events[1])
    assertEquals(TraceDelta.HeaderInvalidated(0, ethernet.totalBits), events[2])
  }

  @Test
  fun `forked trace shares parent prefix and gets its own future events`() {
    val state = PacketState(layout)
    state.trace.emitControlFlow("parent_event")

    val child = state.fork()
    child.trace.emitControlFlow("child_event")

    // Parent only sees its own event.
    assertEquals(listOf(TraceDelta.ControlFlow("parent_event")), state.trace.events())
    // Child sees parent prefix + child event.
    assertEquals(
      listOf(TraceDelta.ControlFlow("parent_event"), TraceDelta.ControlFlow("child_event")),
      child.trace.events(),
    )
  }

  @Test
  fun `build produces a PacketTrace replayable to the final buffer`() {
    val state = PacketState(layout)
    val initialBytes = state.buffer.toBytes()

    HeaderView(state.buffer, ethernet)["dst"] = BitVal(0xAABB_CCDD_EEFFL, 48)
    state.trace.emitFieldWrite(0, 48, 0xAABB_CCDD_EEFFL)

    val trace = state.trace.build(initialBytes)
    val replayed = trace.replay()
    assertArrayEquals(state.buffer.toBytes(), replayed.toBytes())
  }
}
