// Copyright 2026 4ward Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fourward.dvaas

import com.google.protobuf.ByteString
import fourward.dvaas.DvaasProto.InputType
import fourward.dvaas.DvaasProto.Packet
import fourward.dvaas.DvaasProto.PacketTestVector
import fourward.dvaas.DvaasProto.SwitchInput
import fourward.dvaas.DvaasProto.SwitchOutput
import fourward.dvaas.DvaasProto.ValidateTestVectorsRequest
import fourward.ir.PipelineConfig
import fourward.p4runtime.PacketBroker
import fourward.simulator.Simulator
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for [DvaasService]. Uses in-process gRPC transport with a real [Simulator].
 */
class DvaasServiceTest {

  /** Lightweight test harness wrapping a DvaasService with in-process gRPC. */
  private class Harness : Closeable {
    private val serverName = InProcessServerBuilder.generateName()
    private val simulator = Simulator()
    private val lock = Mutex()
    private val broker = PacketBroker(simulator::processPacket)
    private val service = DvaasService(broker::processPacket, lock)

    private val server =
      InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(service)
        .build()
        .start()

    val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    val stub = DvaasValidationGrpcKt.DvaasValidationCoroutineStub(channel)

    fun loadPipeline(config: PipelineConfig) {
      simulator.loadPipeline(config)
    }

    override fun close() {
      channel.shutdownNow()
      server.shutdownNow()
    }
  }

  private fun loadConfig(relativePath: String): PipelineConfig {
    val r = System.getenv("JAVA_RUNFILES") ?: "."
    val path = java.nio.file.Paths.get(r, "_main/$relativePath")
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  /** Builds a minimal Ethernet frame: dst=FF:FF:FF:FF:FF:FF src=00:00:00:00:00:01. */
  @Suppress("MagicNumber")
  private fun buildEthernetFrame(): ByteArray {
    val frame = ByteArray(18)
    for (i in 0 until 6) frame[i] = 0xFF.toByte()
    frame[11] = 0x01
    frame[12] = 0x08 // etherType 0x0800
    frame[13] = 0x00.toByte()
    frame[14] = 0xDE.toByte()
    frame[15] = 0xAD.toByte()
    frame[16] = 0xBE.toByte()
    frame[17] = 0xEF.toByte()
    return frame
  }

  private fun dataplaneSwitchInput(port: Int, payload: ByteArray): SwitchInput =
    SwitchInput.newBuilder()
      .setType(InputType.INPUT_TYPE_DATAPLANE)
      .setPacket(
        Packet.newBuilder()
          .setPort(port.toString())
          .setPayload(ByteString.copyFrom(payload))
      )
      .build()

  // ---------------------------------------------------------------------------
  // Error handling
  // ---------------------------------------------------------------------------

  @Test
  fun `returns FAILED_PRECONDITION when no pipeline is loaded`() {
    Harness().use { harness ->
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setInput(dataplaneSwitchInput(0, byteArrayOf(0x01)))
          )
          .build()

      val e =
        try {
          runBlocking { harness.stub.validateTestVectors(request) }
          null
        } catch (ex: StatusException) {
          ex
        }

      assertEquals(Status.Code.FAILED_PRECONDITION, e?.status?.code)
    }
  }

  @Test
  fun `empty request returns empty response`() {
    Harness().use { harness ->
      val response = runBlocking {
        harness.stub.validateTestVectors(ValidateTestVectorsRequest.getDefaultInstance())
      }
      assertEquals(0, response.outcomesCount)
    }
  }

  // ---------------------------------------------------------------------------
  // Passthrough program: forwards every packet to port 1
  // ---------------------------------------------------------------------------

  @Test
  fun `passthrough program forwards packet to port 1`() {
    Harness().use { harness ->
      val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      harness.loadPipeline(config)

      val payload = buildEthernetFrame()
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneSwitchInput(0, payload))
              .addAcceptableOutputs(
                SwitchOutput.newBuilder()
                  .addPackets(
                    Packet.newBuilder()
                      .setPort("1")
                      .setPayload(ByteString.copyFrom(payload))
                  )
              )
          )
          .build()

      val response = runBlocking { harness.stub.validateTestVectors(request) }
      assertEquals(1, response.outcomesCount)
      assertTrue(response.getOutcomes(0).result.passed)
      assertTrue(response.getOutcomes(0).hasTrace())
    }
  }

  @Test
  fun `passthrough program fails when expecting wrong port`() {
    Harness().use { harness ->
      val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      harness.loadPipeline(config)

      val payload = buildEthernetFrame()
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneSwitchInput(0, payload))
              .addAcceptableOutputs(
                SwitchOutput.newBuilder()
                  .addPackets(
                    Packet.newBuilder()
                      .setPort("42")
                      .setPayload(ByteString.copyFrom(payload))
                  )
              )
          )
          .build()

      val response = runBlocking { harness.stub.validateTestVectors(request) }
      assertEquals(1, response.outcomesCount)
      assertFalse(response.getOutcomes(0).result.passed)
    }
  }

  @Test
  fun `passthrough recording mode captures actual output without validation`() {
    Harness().use { harness ->
      val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      harness.loadPipeline(config)

      val payload = buildEthernetFrame()
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneSwitchInput(0, payload))
            // No acceptable_outputs → recording mode.
          )
          .build()

      val response = runBlocking { harness.stub.validateTestVectors(request) }
      assertEquals(1, response.outcomesCount)
      assertTrue(response.getOutcomes(0).result.passed)
      // Actual output should still be populated.
      assertEquals(1, response.getOutcomes(0).actualOutput.packetsCount)
      assertEquals("1", response.getOutcomes(0).actualOutput.getPackets(0).port)
    }
  }

  // ---------------------------------------------------------------------------
  // Batch validation
  // ---------------------------------------------------------------------------

  @Test
  fun `batch validates multiple test vectors`() {
    Harness().use { harness ->
      val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      harness.loadPipeline(config)

      val payload = buildEthernetFrame()
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneSwitchInput(0, payload))
              .addAcceptableOutputs(
                SwitchOutput.newBuilder()
                  .addPackets(Packet.newBuilder().setPort("1").setPayload(ByteString.copyFrom(payload)))
              )
          )
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(2)
              .setInput(dataplaneSwitchInput(5, payload))
              .addAcceptableOutputs(
                SwitchOutput.newBuilder()
                  .addPackets(Packet.newBuilder().setPort("1").setPayload(ByteString.copyFrom(payload)))
              )
          )
          .build()

      val response = runBlocking { harness.stub.validateTestVectors(request) }
      assertEquals(2, response.outcomesCount)
      assertTrue(response.getOutcomes(0).result.passed)
      assertTrue(response.getOutcomes(1).result.passed)
    }
  }

  // ---------------------------------------------------------------------------
  // basic_table: table-dependent behavior (default action = drop)
  // ---------------------------------------------------------------------------

  @Test
  fun `basic_table drops packets with no matching entry`() {
    Harness().use { harness ->
      val config = loadConfig("e2e_tests/basic_table/basic_table.txtpb")
      harness.loadPipeline(config)

      val payload = buildEthernetFrame()
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneSwitchInput(0, payload))
              // Default action is drop → expect empty output.
              .addAcceptableOutputs(SwitchOutput.getDefaultInstance())
          )
          .build()

      val response = runBlocking { harness.stub.validateTestVectors(request) }
      assertEquals(1, response.outcomesCount)
      assertTrue(response.getOutcomes(0).result.passed)
      assertEquals(0, response.getOutcomes(0).actualOutput.packetsCount)
    }
  }

  // ---------------------------------------------------------------------------
  // Trace tree presence
  // ---------------------------------------------------------------------------

  @Test
  fun `trace tree contains pipeline stage events`() {
    Harness().use { harness ->
      val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      harness.loadPipeline(config)

      val payload = buildEthernetFrame()
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder().setInput(dataplaneSwitchInput(0, payload))
          )
          .build()

      val response = runBlocking { harness.stub.validateTestVectors(request) }
      val trace = response.getOutcomes(0).trace

      // The trace should have events (parser transitions, pipeline stages, etc.)
      assertTrue("trace should have events", trace.eventsCount > 0)
      // The trace should terminate with a packet outcome.
      assertTrue("trace should have packet outcome", trace.hasPacketOutcome())
    }
  }
}
