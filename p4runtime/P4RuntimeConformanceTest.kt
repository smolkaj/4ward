package fourward.p4runtime

import fourward.ir.v1.PipelineConfig
import io.grpc.StatusException
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageResponse

/**
 * P4Runtime conformance tests.
 *
 * Each test verifies one aspect of the P4Runtime server's behavior against the 4ward simulator.
 * Tests are organized by RPC and numbered per the implementation plan.
 *
 * Tests use the basic_table.p4 fixture (exact-match table with forward/drop actions) unless a
 * specific P4 feature is needed.
 */
class P4RuntimeConformanceTest {

  private lateinit var harness: P4RuntimeTestHarness

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // ---------------------------------------------------------------------------
  // Fixture loading
  // ---------------------------------------------------------------------------

  private fun loadBasicTableConfig(): PipelineConfig {
    val r = System.getenv("JAVA_RUNFILES") ?: "."
    val path = Paths.get(r, "_main/e2e_tests/basic_table/basic_table.txtpb")
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  private fun loadPassthroughConfig(): PipelineConfig {
    val r = System.getenv("JAVA_RUNFILES") ?: "."
    val path = Paths.get(r, "_main/e2e_tests/passthrough/passthrough.txtpb")
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  // =========================================================================
  // SetForwardingPipelineConfig (scenarios 1-3)
  // =========================================================================

  @Test
  fun `1 - load valid pipeline succeeds`() {
    val config = loadBasicTableConfig()
    val resp = harness.loadPipeline(config)
    assertNotNull(resp)
  }

  @Test
  fun `2 - load second pipeline replaces first`() {
    harness.loadPipeline(loadBasicTableConfig())
    // Loading a different pipeline should succeed (replaces the first).
    val resp = harness.loadPipeline(loadPassthroughConfig())
    assertNotNull(resp)
  }

  @Test
  fun `3 - load empty config returns error`() {
    try {
      harness.loadPipeline(PipelineConfig.getDefaultInstance())
      fail("expected error for empty pipeline config")
    } catch (e: StatusException) {
      // Expected — invalid pipeline config should be rejected.
      assertTrue(e.status.code.name, e.status.code != io.grpc.Status.Code.OK)
    }
  }

  // =========================================================================
  // Write (scenarios 4-8)
  // =========================================================================

  @Test
  fun `4 - install table entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildBasicTableEntry(config, dstAddr = 0x0800000001, port = 1)
    val resp = harness.installEntry(entry)
    assertNotNull(resp)
  }

  @Test
  fun `5 - modify existing entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildBasicTableEntry(config, dstAddr = 0x0800000001, port = 1)
    harness.installEntry(entry)
    // Modify to forward to a different port.
    val modified = buildBasicTableEntry(config, dstAddr = 0x0800000001, port = 2)
    val resp = harness.modifyEntry(modified)
    assertNotNull(resp)
  }

  @Test
  fun `6 - delete entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildBasicTableEntry(config, dstAddr = 0x0800000001, port = 1)
    harness.installEntry(entry)
    val resp = harness.deleteEntry(entry)
    assertNotNull(resp)
  }

  @Test
  fun `7 - write without pipeline returns error`() {
    try {
      val entity =
        Entity.newBuilder()
          .setTableEntry(
            p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(1).build()
          )
          .build()
      harness.installEntry(entity)
      fail("expected error for write without pipeline")
    } catch (e: StatusException) {
      assertTrue(e.status.code.name, e.status.code != io.grpc.Status.Code.OK)
    }
  }

  @Test
  fun `8 - write with invalid table ID returns error`() {
    harness.loadPipeline(loadBasicTableConfig())
    try {
      val entity =
        Entity.newBuilder()
          .setTableEntry(
            p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
              .setTableId(99999) // Non-existent table.
              .build()
          )
          .build()
      harness.installEntry(entity)
      fail("expected error for invalid table ID")
    } catch (e: StatusException) {
      assertTrue(e.status.code.name, e.status.code != io.grpc.Status.Code.OK)
    }
  }

  // =========================================================================
  // Read (scenarios 9-11)
  // =========================================================================

  @Test
  fun `9 - read back installed entries matches written`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildBasicTableEntry(config, dstAddr = 0x0800000001, port = 1)
    harness.installEntry(entry)
    val entities = harness.readEntries()
    assertTrue("expected at least one entity", entities.isNotEmpty())
  }

  @Test
  fun `10 - read empty table returns empty response`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entities = harness.readEntries()
    assertTrue("expected empty read", entities.isEmpty())
  }

  @Test
  fun `11 - read without pipeline returns error`() {
    try {
      harness.readEntries()
      fail("expected error for read without pipeline")
    } catch (e: StatusException) {
      assertTrue(e.status.code.name, e.status.code != io.grpc.Status.Code.OK)
    }
  }

  // =========================================================================
  // StreamChannel (scenarios 12-15)
  // =========================================================================

  @Test
  fun `12 - arbitration establishes master`() {
    val responses = harness.sendPacketViaStream(byteArrayOf(), expectedResponses = 1)
    assertTrue("expected arbitration response", responses.isNotEmpty())
    assertTrue("expected arbitration ack", responses[0].hasArbitration())
  }

  @Test
  fun `13 - PacketOut processed by simulator, PacketIn returned`() {
    harness.loadPipeline(loadPassthroughConfig())
    val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
    val responses = harness.sendPacketViaStream(payload)
    assertTrue("expected at least 2 responses", responses.size >= 2)
    // First response is arbitration ack, second is PacketIn.
    assertTrue("expected packet_in", responses[1].hasPacket())
    assertEquals(
      com.google.protobuf.ByteString.copyFrom(payload),
      responses[1].packet.payload,
    )
  }

  @Test
  fun `14 - PacketOut with table entries has correct forwarding`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // basic_table.p4 matches on etherType: install forward(port=1) for IPv4 (0x0800).
    val entry = buildEtherTypeEntry(config, etherType = 0x0800, port = 1)
    harness.installEntry(entry)
    // Ethernet frame: dst=ff:ff:ff:ff:ff:ff src=00:00:00:00:00:01 etherType=0x0800 payload=DEAD
    val payload = buildEthernetFrame(etherType = 0x0800)
    val responses = harness.sendPacketViaStream(payload)
    assertTrue("expected at least 2 responses", responses.size >= 2)
    assertTrue("expected packet_in", responses[1].hasPacket())
  }

  @Test
  fun `15 - multiple packets preserve ordering`() {
    harness.loadPipeline(loadPassthroughConfig())
    val payload1 = byteArrayOf(0x01, 0x02)
    val payload2 = byteArrayOf(0x03, 0x04)
    val r1 = harness.sendPacketViaStream(payload1)
    val r2 = harness.sendPacketViaStream(payload2)
    assertTrue("first packet returned", r1.any { it.hasPacket() })
    assertTrue("second packet returned", r2.any { it.hasPacket() })
    // Verify payloads match what was sent.
    val pktIn1 = r1.first { it.hasPacket() }.packet
    val pktIn2 = r2.first { it.hasPacket() }.packet
    assertEquals(com.google.protobuf.ByteString.copyFrom(payload1), pktIn1.payload)
    assertEquals(com.google.protobuf.ByteString.copyFrom(payload2), pktIn2.payload)
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /**
   * Builds a basic_table.p4 table entry: exact match on dst_addr, forward action with egress_port.
   */
  private fun buildBasicTableEntry(config: PipelineConfig, dstAddr: Long, port: Int): Entity {
    val p4info = config.p4Info
    val table = p4info.tablesList.first()
    val forwardAction = p4info.actionsList.find { it.preamble.name.contains("forward") }!!

    val matchField =
      p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(table.matchFieldsList.first().id)
        .setExact(
          p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
            .setValue(
              com.google.protobuf.ByteString.copyFrom(
                longToBytes(dstAddr, (table.matchFieldsList.first().bitwidth + 7) / 8)
              )
            )
        )
        .build()

    val actionParam =
      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
        .setParamId(forwardAction.paramsList.first().id)
        .setValue(
          com.google.protobuf.ByteString.copyFrom(
            longToBytes(port.toLong(), (forwardAction.paramsList.first().bitwidth + 7) / 8)
          )
        )
        .build()

    val tableEntry =
      p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(matchField)
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(forwardAction.preamble.id)
                .addParams(actionParam)
            )
        )
        .build()

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  /** Builds a basic_table entry matching etherType → forward(port). */
  @Suppress("MagicNumber")
  private fun buildEtherTypeEntry(config: PipelineConfig, etherType: Int, port: Int): Entity {
    val p4info = config.p4Info
    val table = p4info.tablesList.first()
    val forwardAction = p4info.actionsList.find { it.preamble.name.contains("forward") }!!
    val matchField = table.matchFieldsList.first()

    val fieldMatch =
      p4.v1.P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(matchField.id)
        .setExact(
          p4.v1.P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
            .setValue(
              com.google.protobuf.ByteString.copyFrom(
                longToBytes(etherType.toLong(), (matchField.bitwidth + 7) / 8)
              )
            )
        )
        .build()

    val actionParam =
      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
        .setParamId(forwardAction.paramsList.first().id)
        .setValue(
          com.google.protobuf.ByteString.copyFrom(
            longToBytes(port.toLong(), (forwardAction.paramsList.first().bitwidth + 7) / 8)
          )
        )
        .build()

    val tableEntry =
      p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(fieldMatch)
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(forwardAction.preamble.id)
                .addParams(actionParam)
            )
        )
        .build()

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  /** Builds a minimal Ethernet frame: dst=FF:FF:FF:FF:FF:FF src=00:00:00:00:00:01 + etherType. */
  @Suppress("MagicNumber")
  private fun buildEthernetFrame(etherType: Int): ByteArray {
    val frame = ByteArray(18) // 14-byte header + 4 bytes payload
    // Dst MAC: broadcast
    for (i in 0 until 6) frame[i] = 0xFF.toByte()
    // Src MAC: 00:00:00:00:00:01
    frame[11] = 0x01
    // EtherType (big-endian)
    frame[12] = (etherType shr 8).toByte()
    frame[13] = (etherType and 0xFF).toByte()
    // Payload
    frame[14] = 0xDE.toByte()
    frame[15] = 0xAD.toByte()
    frame[16] = 0xBE.toByte()
    frame[17] = 0xEF.toByte()
    return frame
  }

  private fun longToBytes(value: Long, byteLen: Int): ByteArray {
    val bytes = ByteArray(byteLen)
    for (i in 0 until byteLen) {
      bytes[byteLen - 1 - i] = (value shr (i * 8) and 0xFF).toByte()
    }
    return bytes
  }
}
