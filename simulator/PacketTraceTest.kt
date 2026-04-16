package fourward.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [PacketTrace] and [TraceDelta]. */
class PacketTraceTest {

  private val ethernet =
    HeaderLayout(
      typeName = "ethernet_t",
      fields = linkedMapOf("dstAddr" to FieldSlot(0, 48), "etherType" to FieldSlot(48, 16)),
      validBitOffset = 64,
    )

  @Test
  fun `empty trace replays to the initial state`() {
    val buf = PacketBuffer(ethernet.totalBits)
    HeaderView(buf, ethernet).let {
      it["dstAddr"] = BitVal(0x111111111111L, 48)
      it.isValid = true
    }
    val trace = PacketTrace(initial = buf.toBytes(), events = emptyList())

    val replayed = trace.replay()
    val h = HeaderView(replayed, ethernet)
    assertEquals(BitVal(0x111111111111L, 48), h["dstAddr"])
    assertTrue(h.isValid)
  }

  @Test
  fun `fieldWrite delta applies the new value`() {
    val buf = PacketBuffer(ethernet.totalBits)
    val events =
      listOf(TraceDelta.FieldWrite(bitOffset = 0, width = 48, newValue = 0xAAAAAAAAAAAAL))
    val trace = PacketTrace(initial = buf.toBytes(), events = events)

    val replayed = trace.replay()
    assertEquals(BitVal(0xAAAAAAAAAAAAL, 48), HeaderView(replayed, ethernet)["dstAddr"])
  }

  @Test
  fun `multiple fieldWrite deltas apply in order`() {
    val buf = PacketBuffer(ethernet.totalBits)
    val events =
      listOf(
        TraceDelta.FieldWrite(bitOffset = 0, width = 48, newValue = 0x111111111111L),
        TraceDelta.FieldWrite(bitOffset = 48, width = 16, newValue = 0x0800L),
        TraceDelta.FieldWrite(bitOffset = 0, width = 48, newValue = 0x222222222222L), // overwrite
      )
    val trace = PacketTrace(initial = buf.toBytes(), events = events)

    val h = HeaderView(trace.replay(), ethernet)
    assertEquals(BitVal(0x222222222222L, 48), h["dstAddr"])
    assertEquals(BitVal(0x0800L, 16), h["etherType"])
  }

  @Test
  fun `headerInvalidated delta zeros the layout and clears valid bit`() {
    val buf = PacketBuffer(ethernet.totalBits)
    HeaderView(buf, ethernet).let {
      it["dstAddr"] = BitVal(0xFFFFFFFFFFFFL, 48)
      it["etherType"] = BitVal(0xFFFFL, 16)
      it.isValid = true
    }
    val events = listOf(TraceDelta.HeaderInvalidated(bitOffset = 0, totalBits = ethernet.totalBits))
    val trace = PacketTrace(initial = buf.toBytes(), events = events)

    val h = HeaderView(trace.replay(), ethernet)
    assertFalse(h.isValid)
    assertEquals(BitVal(0L, 48), h["dstAddr"])
    assertEquals(BitVal(0L, 16), h["etherType"])
  }

  @Test
  fun `controlFlow delta is recorded but does not change state`() {
    val buf = PacketBuffer(ethernet.totalBits)
    HeaderView(buf, ethernet)["dstAddr"] = BitVal(0x123456789ABCL, 48)
    val events =
      listOf<TraceDelta>(
        TraceDelta.ControlFlow("entered_table: ipv4_lpm"),
        TraceDelta.ControlFlow("action_called: set_nexthop"),
      )
    val trace = PacketTrace(initial = buf.toBytes(), events = events)

    // State unchanged.
    assertEquals(BitVal(0x123456789ABCL, 48), HeaderView(trace.replay(), ethernet)["dstAddr"])
  }

  @Test
  fun `replay is idempotent and produces an independent buffer`() {
    val initial = ByteArray(8) { 0xFF.toByte() }
    val trace =
      PacketTrace(
        initial = initial,
        events = listOf(TraceDelta.FieldWrite(bitOffset = 0, width = 8, newValue = 0x00L)),
      )

    val first = trace.replay()
    val second = trace.replay()
    // Same content, different buffer objects.
    assertArrayEqualsByte(first.toBytes(), second.toBytes())
    // Initial is unmodified.
    assertEquals(0xFF.toByte(), initial[0])
  }

  @Test
  fun `stateAt returns the prefix-replayed state`() {
    val buf = PacketBuffer(ethernet.totalBits)
    val events =
      listOf(
        TraceDelta.FieldWrite(bitOffset = 0, width = 48, newValue = 0x111111111111L),
        TraceDelta.ControlFlow("some_event"),
        TraceDelta.FieldWrite(bitOffset = 0, width = 48, newValue = 0x222222222222L),
      )
    val trace = PacketTrace(initial = buf.toBytes(), events = events)

    // After event 1 only: dstAddr = 0x1111...
    val after1 = HeaderView(trace.stateAt(1), ethernet)
    assertEquals(BitVal(0x111111111111L, 48), after1["dstAddr"])

    // After events 1 and 2: dstAddr still = 0x1111... (ControlFlow doesn't change state)
    val after2 = HeaderView(trace.stateAt(2), ethernet)
    assertEquals(BitVal(0x111111111111L, 48), after2["dstAddr"])

    // After all 3 events: dstAddr = 0x2222...
    val after3 = HeaderView(trace.stateAt(3), ethernet)
    assertEquals(BitVal(0x222222222222L, 48), after3["dstAddr"])
  }

  @Test
  fun `stateAt with zero returns the initial state`() {
    val initial = ByteArray(2) { 0x42 }
    val trace =
      PacketTrace(
        initial = initial,
        events = listOf(TraceDelta.FieldWrite(bitOffset = 0, width = 8, newValue = 0x99L)),
      )
    val state = trace.stateAt(0)
    assertEquals(0x42.toByte(), state.toBytes()[0])
  }

  @Test
  fun `stateAt beyond events list throws`() {
    val trace = PacketTrace(initial = ByteArray(1), events = emptyList())
    assertThrows(IllegalArgumentException::class.java) { trace.stateAt(5) }
  }

  @Test
  fun `trace events are preserved verbatim`() {
    val events =
      listOf<TraceDelta>(
        TraceDelta.ControlFlow("entry"),
        TraceDelta.FieldWrite(0, 8, 0x01L),
        TraceDelta.HeaderInvalidated(0, 16),
      )
    val trace = PacketTrace(initial = ByteArray(2), events = events)
    assertEquals(events, trace.events)
  }

  private fun assertArrayEqualsByte(expected: ByteArray, actual: ByteArray) {
    assertEquals(expected.size, actual.size)
    for (i in expected.indices) {
      assertEquals("byte $i", expected[i], actual[i])
    }
  }
}
