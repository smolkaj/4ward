package fourward.p4runtime

import fourward.sim.SimulatorProto.OutputPacket
import fourward.sim.SimulatorProto.TraceTree
import fourward.simulator.ProcessPacketResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketBrokerTest {

  private fun outputPacket(egressPort: Int, payload: ByteArray = byteArrayOf()) =
    OutputPacket.newBuilder()
      .setDataplaneEgressPort(egressPort)
      .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
      .build()

  private fun result(vararg outputs: OutputPacket) =
    ProcessPacketResult(outputs.toList(), TraceTree.getDefaultInstance())

  private fun fakeProcessor(
    vararg results: Pair<Int, ProcessPacketResult>
  ): (Int, ByteArray) -> ProcessPacketResult {
    val map = results.toMap()
    return { port, _ -> map[port] ?: result() }
  }

  @Test
  fun `processPacket returns outputs and trace`() {
    val expected = result(outputPacket(1, byteArrayOf(0xAB.toByte())))
    val broker = PacketBroker(fakeProcessor(0 to expected))

    val actual = broker.processPacket(0, byteArrayOf(0x01))
    assertEquals(expected.outputPackets, actual.outputPackets)
  }

  @Test
  fun `subscriber receives result with input and outputs`() {
    val outputs = listOf(outputPacket(1))
    val broker = PacketBroker(fakeProcessor(0 to result(outputPacket(1))))

    val received = mutableListOf<PacketBroker.SubscriptionResult>()
    broker.subscribe { received.add(it) }

    broker.processPacket(0, byteArrayOf(0x01))

    assertEquals(1, received.size)
    assertEquals(0, received[0].ingressPort)
    assertEquals(outputs, received[0].outputPackets)
  }

  @Test
  fun `multiple subscribers each receive results`() {
    val broker = PacketBroker(fakeProcessor(0 to result(outputPacket(1))))

    val received1 = mutableListOf<PacketBroker.SubscriptionResult>()
    val received2 = mutableListOf<PacketBroker.SubscriptionResult>()
    broker.subscribe { received1.add(it) }
    broker.subscribe { received2.add(it) }

    broker.processPacket(0, byteArrayOf())

    assertEquals(1, received1.size)
    assertEquals(1, received2.size)
  }

  @Test
  fun `no subscribers does not cause errors`() {
    val broker = PacketBroker(fakeProcessor(0 to result(outputPacket(1))))
    broker.processPacket(0, byteArrayOf())
  }

  @Test
  fun `unsubscribe stops delivery`() {
    val broker = PacketBroker(fakeProcessor(0 to result(outputPacket(1))))

    val received = mutableListOf<PacketBroker.SubscriptionResult>()
    val handle = broker.subscribe { received.add(it) }

    broker.processPacket(0, byteArrayOf())
    assertEquals(1, received.size)

    handle.unsubscribe()
    broker.processPacket(0, byteArrayOf())
    assertEquals("no new result after unsubscribe", 1, received.size)
  }

  @Test
  fun `throwing subscriber does not crash caller or block other subscribers`() {
    val broker = PacketBroker(fakeProcessor(0 to result(outputPacket(1))))

    val received = mutableListOf<PacketBroker.SubscriptionResult>()
    broker.subscribe { throw IllegalStateException("boom") }
    broker.subscribe { received.add(it) }

    // The caller should get the result even though the first subscriber threw.
    val callerResult = broker.processPacket(0, byteArrayOf())
    assertEquals(1, callerResult.outputPackets.size)

    // The second subscriber should still receive the result.
    assertEquals(1, received.size)
  }
}
