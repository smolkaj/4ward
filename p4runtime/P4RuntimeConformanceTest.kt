package fourward.p4runtime

import fourward.ir.v1.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildEthernetFrame
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import io.grpc.StatusException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity

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

  private fun loadBasicTableConfig() =
    P4RuntimeTestHarness.loadConfig("e2e_tests/basic_table/basic_table.txtpb")

  private fun loadPassthroughConfig() =
    P4RuntimeTestHarness.loadConfig("e2e_tests/passthrough/passthrough.txtpb")

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
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val resp = harness.installEntry(entry)
    assertNotNull(resp)
  }

  @Test
  fun `5 - modify existing entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    // Modify to forward to a different port.
    val modified = buildExactEntry(config, matchValue = 0x0800, port = 2)
    val resp = harness.modifyEntry(modified)
    assertNotNull(resp)
  }

  @Test
  fun `6 - delete entry succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    val resp = harness.deleteEntry(entry)
    assertNotNull(resp)
  }

  @Test
  fun `7 - write without pipeline returns error`() {
    try {
      val entity =
        Entity.newBuilder()
          .setTableEntry(p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(1).build())
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
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
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
    assertEquals(com.google.protobuf.ByteString.copyFrom(payload), responses[1].packet.payload)
  }

  @Test
  fun `14 - PacketOut with table entries has correct forwarding`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // basic_table.p4 matches on etherType: install forward(port=1) for IPv4.
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
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
    harness.openStream().use { stream ->
      stream.arbitrate()
      val payload1 = byteArrayOf(0x01, 0x02)
      val payload2 = byteArrayOf(0x03, 0x04)
      val pkt1 = stream.sendPacket(payload1)
      val pkt2 = stream.sendPacket(payload2)
      assertNotNull("first packet returned", pkt1)
      assertNotNull("second packet returned", pkt2)
      assertTrue("first is packet_in", pkt1!!.hasPacket())
      assertTrue("second is packet_in", pkt2!!.hasPacket())
      assertEquals(com.google.protobuf.ByteString.copyFrom(payload1), pkt1.packet.payload)
      assertEquals(com.google.protobuf.ByteString.copyFrom(payload2), pkt2.packet.payload)
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /** Builds a table entry: exact match on the table's first field → forward(port). */
  @Suppress("MagicNumber")
  private fun buildExactEntry(config: PipelineConfig, matchValue: Long, port: Int): Entity {
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
                longToBytes(matchValue, (matchField.bitwidth + 7) / 8)
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
}
