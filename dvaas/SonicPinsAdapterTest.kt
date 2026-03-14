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
import fourward.dvaas.DvaasProto.GenerateTestVectorsRequest
import fourward.dvaas.DvaasProto.InputType
import fourward.dvaas.DvaasProto.Packet
import fourward.dvaas.DvaasProto.PacketTestVector
import fourward.dvaas.DvaasProto.SwitchInput
import fourward.ir.PipelineConfig
import fourward.p4runtime.GnmiService
import fourward.p4runtime.P4RuntimeService
import fourward.p4runtime.PacketBroker
import fourward.simulator.Simulator
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest

/**
 * Tests [SonicPinsAdapter] — the translation layer between sonic-pins' DVaaS format and 4ward's
 * native format.
 *
 * Includes unit tests for hex/bytes/IrValue conversion and an integration test demonstrating the
 * full sonic-pins → 4ward → sonic-pins round-trip with a real SAI P4 pipeline.
 */
class SonicPinsAdapterTest {

  // ---------------------------------------------------------------------------
  // Hex ↔ bytes conversion
  // ---------------------------------------------------------------------------

  @Test
  fun `hexToBytes decodes simple hex string`() {
    val bytes = SonicPinsAdapter.hexToBytes("0a0b0c0d")
    assertEquals(4, bytes.size)
    assertEquals(0x0a.toByte(), bytes[0])
    assertEquals(0x0b.toByte(), bytes[1])
    assertEquals(0x0c.toByte(), bytes[2])
    assertEquals(0x0d.toByte(), bytes[3])
  }

  @Test
  fun `hexToBytes strips 0x prefix`() {
    val bytes = SonicPinsAdapter.hexToBytes("0x0a0b")
    assertEquals(2, bytes.size)
    assertEquals(0x0a.toByte(), bytes[0])
    assertEquals(0x0b.toByte(), bytes[1])
  }

  @Test
  fun `hexToBytes handles uppercase`() {
    val bytes = SonicPinsAdapter.hexToBytes("AABB")
    assertEquals(0xAA.toByte(), bytes[0])
    assertEquals(0xBB.toByte(), bytes[1])
  }

  @Test
  fun `hexToBytes handles empty string`() {
    val bytes = SonicPinsAdapter.hexToBytes("")
    assertEquals(0, bytes.size)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `hexToBytes rejects odd-length hex`() {
    SonicPinsAdapter.hexToBytes("abc")
  }

  @Test
  fun `bytesToHex encodes to lowercase hex`() {
    assertEquals("0a0b0c0d", SonicPinsAdapter.bytesToHex(byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d)))
  }

  @Test
  fun `bytesToHex handles high bytes`() {
    assertEquals("ffee", SonicPinsAdapter.bytesToHex(byteArrayOf(0xFF.toByte(), 0xEE.toByte())))
  }

  @Test
  fun `hex round-trip is lossless`() {
    val original = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0xFF.toByte())
    val hex = SonicPinsAdapter.bytesToHex(original)
    val decoded = SonicPinsAdapter.hexToBytes(hex)
    assertTrue(original.contentEquals(decoded))
  }

  // ---------------------------------------------------------------------------
  // IrValue format conversion
  // ---------------------------------------------------------------------------

  @Test
  fun `irValueToBytes parses hex_str`() {
    val bytes = SonicPinsAdapter.irValueToBytes("hex_str", "0x0a8b")
    assertEquals(2, bytes.size)
    assertEquals(0x0a.toByte(), bytes[0])
    assertEquals(0x8b.toByte(), bytes[1])
  }

  @Test
  fun `irValueToBytes parses ipv4`() {
    val bytes = SonicPinsAdapter.irValueToBytes("ipv4", "10.0.0.1")
    assertEquals(4, bytes.size)
    assertEquals(10.toByte(), bytes[0])
    assertEquals(0.toByte(), bytes[1])
    assertEquals(0.toByte(), bytes[2])
    assertEquals(1.toByte(), bytes[3])
  }

  @Test
  fun `irValueToBytes parses ipv6`() {
    val bytes = SonicPinsAdapter.irValueToBytes("ipv6", "::1")
    assertEquals(16, bytes.size)
    // ::1 is all zeros except the last byte.
    for (i in 0 until 15) assertEquals("byte $i", 0.toByte(), bytes[i])
    assertEquals(1.toByte(), bytes[15])
  }

  @Test
  fun `irValueToBytes parses mac`() {
    val bytes = SonicPinsAdapter.irValueToBytes("mac", "00:11:22:33:44:55")
    assertEquals(6, bytes.size)
    assertEquals(0x00.toByte(), bytes[0])
    assertEquals(0x11.toByte(), bytes[1])
    assertEquals(0x55.toByte(), bytes[5])
  }

  @Test
  fun `irValueToBytes parses str`() {
    val bytes = SonicPinsAdapter.irValueToBytes("str", "hello")
    assertEquals("hello", String(bytes, Charsets.UTF_8))
  }

  // ---------------------------------------------------------------------------
  // Test vector conversion
  // ---------------------------------------------------------------------------

  @Test
  fun `toTestVector creates DATAPLANE test vector from hex`() {
    val vector = SonicPinsAdapter.toTestVector(id = 42, port = "1", hex = "deadbeef")
    assertEquals(42L, vector.id)
    assertEquals(InputType.INPUT_TYPE_DATAPLANE, vector.input.type)
    assertEquals("1", vector.input.packet.port)
    assertEquals(4, vector.input.packet.payload.size())
    assertEquals(0xDE.toByte(), vector.input.packet.payload.byteAt(0))
  }

  @Test
  fun `toTestVector creates SUBMIT_TO_INGRESS test vector`() {
    val vector =
      SonicPinsAdapter.toTestVector(id = 1, port = "", hex = "aabb", injectionType = "SUBMIT_TO_INGRESS")
    assertEquals(InputType.INPUT_TYPE_SUBMIT_TO_INGRESS, vector.input.type)
  }

  @Test
  fun `toTestVector creates PACKET_OUT test vector`() {
    val vector =
      SonicPinsAdapter.toTestVector(id = 1, port = "3", hex = "aabb", injectionType = "PACKET_OUT")
    assertEquals(InputType.INPUT_TYPE_PACKET_OUT, vector.input.type)
    assertEquals("3", vector.input.packet.port)
  }

  @Test
  fun `parseInjectionType handles DEFAULT as DATAPLANE`() {
    assertEquals(InputType.INPUT_TYPE_DATAPLANE, SonicPinsAdapter.parseInjectionType("DEFAULT"))
  }

  // ---------------------------------------------------------------------------
  // Outcome conversion
  // ---------------------------------------------------------------------------

  @Test
  fun `outcomeToMap converts forwarded packet`() {
    val outcome =
      DvaasProto.PacketTestOutcome.newBuilder()
        .setTestVector(PacketTestVector.newBuilder().setId(7))
        .setActualOutput(
          DvaasProto.SwitchOutput.newBuilder()
            .addPackets(
              Packet.newBuilder()
                .setPort("2")
                .setPayload(ByteString.copyFrom(byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
            )
        )
        .setTrace(fourward.sim.SimulatorProto.TraceTree.getDefaultInstance())
        .setResult(DvaasProto.ValidationResult.newBuilder().setPassed(true))
        .build()

    val map = SonicPinsAdapter.outcomeToMap(outcome)
    assertEquals(7L, map["id"])
    assertTrue(map["passed"] as Boolean)
    @Suppress("UNCHECKED_CAST")
    val packets = map["packets"] as List<Map<String, String>>
    assertEquals(1, packets.size)
    assertEquals("2", packets[0]["port"])
    assertEquals("aabb", packets[0]["hex"])
  }

  @Test
  fun `switchOutputToHex converts packets and packet_ins`() {
    val output =
      DvaasProto.SwitchOutput.newBuilder()
        .addPackets(
          Packet.newBuilder()
            .setPort("1")
            .setPayload(ByteString.copyFrom(byteArrayOf(0x01, 0x02)))
        )
        .addPacketIns(
          DvaasProto.PacketIn.newBuilder()
            .setPayload(ByteString.copyFrom(byteArrayOf(0xAA.toByte())))
            .addMetadata(
              DvaasProto.PacketMetadata.newBuilder()
                .setName("ingress_port")
                .setValue(ByteString.copyFrom(byteArrayOf(0x07)))
            )
        )
        .build()

    val hexOutput = SonicPinsAdapter.switchOutputToHex(output)
    assertEquals(1, hexOutput.packets.size)
    assertEquals("1", hexOutput.packets[0].port)
    assertEquals("0102", hexOutput.packets[0].hex)
    assertEquals(1, hexOutput.packetIns.size)
    assertEquals("aa", hexOutput.packetIns[0].hex)
    assertEquals("ingress_port", hexOutput.packetIns[0].metadata[0].name)
    assertEquals("07", hexOutput.packetIns[0].metadata[0].hexValue)
  }

  // ---------------------------------------------------------------------------
  // Integration: full sonic-pins → 4ward → sonic-pins round-trip
  // ---------------------------------------------------------------------------

  /**
   * Demonstrates the complete DVaaS reference model flow from sonic-pins' perspective:
   *
   * 1. Start with hex-encoded packets (sonic-pins format)
   * 2. Convert to 4ward format via [SonicPinsAdapter]
   * 3. Call [GenerateTestVectors] to compute expected outputs
   * 4. Convert results back to sonic-pins hex format
   * 5. Verify the results are usable for DVaaS validation
   */
  @Test
  fun `full round-trip with passthrough pipeline`() {
    Harness().use { harness ->
      val config = loadConfig("e2e_tests/passthrough/passthrough.txtpb")
      harness.loadPipeline(config)

      // 1. Start with hex-encoded packet (how sonic-pins represents packets).
      val ethernetHex = buildEthernetFrameHex()

      // 2. Convert to 4ward format.
      val vector = SonicPinsAdapter.toTestVector(id = 1, port = "0", hex = ethernetHex)

      // 3. Call GenerateTestVectors.
      val request = GenerateTestVectorsRequest.newBuilder().addTestVectors(vector).build()
      val response = runBlocking { harness.dvaasStub.generateTestVectors(request) }
      assertEquals(1, response.outcomesCount)

      // 4. Convert results back to sonic-pins hex format.
      val outcome = response.getOutcomes(0)
      val hexOutput = SonicPinsAdapter.switchOutputToHex(outcome.actualOutput)

      // 5. Verify: passthrough forwards to port 1 with unchanged payload.
      assertEquals(1, hexOutput.packets.size)
      assertEquals("1", hexOutput.packets[0].port)
      assertEquals(ethernetHex, hexOutput.packets[0].hex)

      // Also verify the map-based conversion works.
      val map = SonicPinsAdapter.outcomeToMap(outcome)
      assertTrue(map["passed"] as Boolean)
      assertEquals(1L, map["id"])
    }
  }

  // ---------------------------------------------------------------------------
  // Test harness
  // ---------------------------------------------------------------------------

  private class Harness : Closeable {
    private val serverName = InProcessServerBuilder.generateName()
    private val simulator = Simulator()
    private val lock = Mutex()
    private val broker = PacketBroker(simulator::processPacket)
    private val p4rtService = P4RuntimeService(simulator, broker, lock = lock)
    private val dvaasService =
      DvaasService(
        processPacketFn = broker::processPacket,
        lock = lock,
        cpuPortFn = p4rtService::currentCpuPort,
        packetOutInjectorFn = p4rtService::injectPacketOut,
        packetInMetadataFn = p4rtService::buildDvaasPacketInMetadata,
      )

    private val server =
      InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(p4rtService)
        .addService(dvaasService)
        .build()
        .start()

    private val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    private val p4rtStub = P4RuntimeGrpcKt.P4RuntimeCoroutineStub(channel)
    val dvaasStub = DvaasValidationGrpcKt.DvaasValidationCoroutineStub(channel)

    fun loadPipeline(config: PipelineConfig) {
      runBlocking {
        p4rtStub.setForwardingPipelineConfig(
          SetForwardingPipelineConfigRequest.newBuilder()
            .setDeviceId(1)
            .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
            .setConfig(
              ForwardingPipelineConfig.newBuilder()
                .setP4Info(config.p4Info)
                .setP4DeviceConfig(config.device.toByteString())
            )
            .build()
        )
      }
    }

    override fun close() {
      p4rtService.close()
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

  /** Builds a minimal Ethernet frame as a hex string (sonic-pins format). */
  @Suppress("MagicNumber")
  private fun buildEthernetFrameHex(): String {
    val frame = ByteArray(18)
    for (i in 0 until 6) frame[i] = 0xFF.toByte()
    frame[11] = 0x01
    frame[12] = 0x08
    frame[13] = 0x00
    frame[14] = 0xDE.toByte()
    frame[15] = 0xAD.toByte()
    frame[16] = 0xBE.toByte()
    frame[17] = 0xEF.toByte()
    return SonicPinsAdapter.bytesToHex(frame)
  }
}
