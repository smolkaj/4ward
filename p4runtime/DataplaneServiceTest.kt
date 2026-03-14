package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildEthernetFrame
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.sim.v1.DataplaneGrpcKt.DataplaneCoroutineStub
import fourward.sim.v1.SimulatorProto.InjectPacketRequest
import fourward.sim.v1.SimulatorProto.InputPacket
import fourward.sim.v1.SimulatorProto.SubscribeResultsRequest
import fourward.sim.v1.SimulatorProto.SubscribeResultsResponse
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DataplaneServiceTest {

  private lateinit var harness: P4RuntimeTestHarness

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  private fun loadPassthroughConfig() =
    loadConfig("e2e_tests/passthrough/passthrough.txtpb")

  private fun loadBasicTableConfig() =
    loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  // =========================================================================
  // InjectPacket
  // =========================================================================

  @Test
  fun `InjectPacket returns outputs and trace`() {
    harness.loadPipeline(loadPassthroughConfig())
    val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
    val response = harness.injectPacket(ingressPort = 0, payload = payload)

    assertEquals("passthrough produces 1 output", 1, response.outputPacketsCount)
    assertTrue("trace should be present", response.hasTrace())
    assertEquals(
      "output payload matches input",
      ByteString.copyFrom(payload),
      response.getOutputPackets(0).payload,
    )
  }

  @Test
  fun `InjectPacket with table entries forwards to correct port`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    val payload = buildEthernetFrame(etherType = 0x0800)
    val response = harness.injectPacket(ingressPort = 0, payload = payload)

    assertEquals("expected 1 output", 1, response.outputPacketsCount)
    assertEquals("should exit on port 1", 1, response.getOutputPackets(0).egressPort)
  }

  @Test
  fun `InjectPacket with no matching entry produces no outputs`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // No table entries installed — default action is drop.
    val payload = buildEthernetFrame(etherType = 0x0800)
    val response = harness.injectPacket(ingressPort = 0, payload = payload)

    assertEquals("dropped packet produces no outputs", 0, response.outputPacketsCount)
  }

  // =========================================================================
  // SubscribeResults
  // =========================================================================

  @Test
  fun `SubscribeResults first message is SubscriptionActive`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)
    val first = stub.subscribeResults(SubscribeResultsRequest.getDefaultInstance()).first()
    assertTrue("first message should be SubscriptionActive", first.hasActive())
  }

  @Test
  fun `SubscribeResults delivers result after injection`() = runBlocking {
    harness.loadPipeline(loadPassthroughConfig())
    val stub = DataplaneCoroutineStub(harness.channel)

    // Collect 2 messages: SubscriptionActive + 1 result.
    val messages = async {
      withTimeout(5000) {
        stub.subscribeResults(SubscribeResultsRequest.getDefaultInstance()).take(2).toList()
      }
    }

    // Wait for subscription to be active, then inject.
    // Small yield to let the subscription start.
    kotlinx.coroutines.delay(100)
    harness.injectPacket(ingressPort = 0, payload = byteArrayOf(0x01))

    val result = messages.await()
    assertEquals("expected 2 messages", 2, result.size)
    assertTrue("first is SubscriptionActive", result[0].hasActive())
    assertTrue("second is ProcessPacketResult", result[1].hasResult())
    assertEquals(0, result[1].result.input.ingressPort)
    assertEquals(
      ByteString.copyFrom(byteArrayOf(0x01)),
      result[1].result.input.payload,
    )
  }
}
