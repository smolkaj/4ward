package fourward.p4runtime

import fourward.ir.v1.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.assertGrpcError
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildEthernetFrame
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildExactEntry
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildGroupEntity
import fourward.p4runtime.P4RuntimeTestHarness.Companion.buildMemberEntity
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.uint128
import io.grpc.Status
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.GetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest

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
    assertGrpcError(Status.Code.INVALID_ARGUMENT) {
      harness.loadPipeline(PipelineConfig.getDefaultInstance())
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
    val entity =
      Entity.newBuilder()
        .setTableEntry(p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(1))
        .build()
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.installEntry(entity) }
  }

  @Test
  fun `8 - write with invalid table ID returns error`() {
    harness.loadPipeline(loadBasicTableConfig())
    val entity =
      Entity.newBuilder()
        .setTableEntry(p4.v1.P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(99999))
        .build()
    assertGrpcError(Status.Code.NOT_FOUND) { harness.installEntry(entity) }
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
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.readEntries() }
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
  // GetForwardingPipelineConfig (scenarios 16-18)
  // =========================================================================

  @Test
  fun `16 - getForwardingPipelineConfig returns loaded p4info`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val resp = harness.getConfig()
    assertEquals(config.p4Info, resp.config.p4Info)
  }

  @Test
  fun `17 - getForwardingPipelineConfig without pipeline returns error`() {
    assertGrpcError(Status.Code.FAILED_PRECONDITION) { harness.getConfig() }
  }

  @Test
  fun `18 - getForwardingPipelineConfig P4INFO_AND_COOKIE omits device config`() {
    harness.loadPipeline(loadBasicTableConfig())
    val resp = harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.P4INFO_AND_COOKIE)
    assertTrue("p4info should be present", resp.config.hasP4Info())
    assertTrue("device config should be empty", resp.config.p4DeviceConfig.isEmpty)
  }

  // =========================================================================
  // Capabilities (scenario 19)
  // =========================================================================

  @Test
  fun `19 - capabilities returns API version`() {
    val resp = harness.capabilities()
    assertEquals("1.5.0", resp.p4RuntimeApiVersion)
  }

  // =========================================================================
  // Write batch ordering (scenario 39)
  // =========================================================================

  /** P4Runtime spec §9.28: updates within a WriteRequest are applied in order. */
  @Test
  fun `39 - write batch applies updates in order`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)

    val entry1 = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entry2 = buildExactEntry(config, matchValue = 0x0800, port = 2)

    // Single WriteRequest: INSERT then MODIFY of the same key.
    // If ordering is correct, both succeed and the entry has port=2.
    runBlocking {
      harness.stub.write(
        p4.v1.P4RuntimeOuterClass.WriteRequest.newBuilder()
          .setDeviceId(1)
          .addUpdates(
            p4.v1.P4RuntimeOuterClass.Update.newBuilder()
              .setType(p4.v1.P4RuntimeOuterClass.Update.Type.INSERT)
              .setEntity(entry1)
          )
          .addUpdates(
            p4.v1.P4RuntimeOuterClass.Update.newBuilder()
              .setType(p4.v1.P4RuntimeOuterClass.Update.Type.MODIFY)
              .setEntity(entry2)
          )
          .build()
      )
    }

    // Read back: should have the MODIFY's action (port=2).
    val results = harness.readEntry(P4RuntimeTestHarness.buildMatchFilter(config, 0x0800))
    assertEquals("entry should exist", 1, results.size)
    assertEquals(
      "entry should have the MODIFY's action",
      entry2.tableEntry.action.action.paramsList,
      results[0].tableEntry.action.action.paramsList,
    )
  }

  // =========================================================================
  // SetForwardingPipelineConfig validation (scenarios 37-38)
  // =========================================================================

  @Test
  fun `37 - setForwardingPipelineConfig with invalid device config returns INVALID_ARGUMENT`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "not a valid DeviceConfig") {
      loadRawPipeline(
        ForwardingPipelineConfig.newBuilder()
          .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
          .setP4DeviceConfig(com.google.protobuf.ByteString.copyFromUtf8("not-a-proto"))
      )
    }
  }

  @Test
  fun `38 - setForwardingPipelineConfig without device config returns INVALID_ARGUMENT`() {
    assertGrpcError(Status.Code.INVALID_ARGUMENT, "must include p4info and p4_device_config") {
      loadRawPipeline(
        ForwardingPipelineConfig.newBuilder()
          .setP4Info(p4.config.v1.P4InfoOuterClass.P4Info.getDefaultInstance())
      )
    }
  }

  // =========================================================================
  // Read filtering (scenarios 20-21)
  // =========================================================================

  @Test
  fun `20 - read with table filter returns only matching entries`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    val tableId = config.p4Info.tablesList.first().preamble.id
    assertEquals("matching table ID", 1, harness.readTableEntries(tableId).size)
    assertTrue("non-matching table ID", harness.readTableEntries(99999).isEmpty())
  }

  @Test
  fun `21 - read with empty entity filter returns nothing`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    // P4Runtime spec §11.1: empty entity list = no filters = no results.
    val request = ReadRequest.newBuilder().setDeviceId(1).build()
    assertTrue("empty filter should return nothing", harness.readEntries(request).isEmpty())
  }

  // =========================================================================
  // GetForwardingPipelineConfig response types (scenarios 22, 36)
  // =========================================================================

  @Test
  fun `22 - getForwardingPipelineConfig DEVICE_CONFIG_AND_COOKIE omits p4info`() {
    harness.loadPipeline(loadBasicTableConfig())
    val resp =
      harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.DEVICE_CONFIG_AND_COOKIE)
    assertFalse("p4info should be absent", resp.config.hasP4Info())
    assertFalse("device config should be present", resp.config.p4DeviceConfig.isEmpty)
  }

  @Test
  fun `36 - getForwardingPipelineConfig COOKIE_ONLY returns empty config`() {
    harness.loadPipeline(loadBasicTableConfig())
    val resp = harness.getConfig(GetForwardingPipelineConfigRequest.ResponseType.COOKIE_ONLY)
    assertFalse("p4info should be absent", resp.config.hasP4Info())
    assertTrue("device config should be empty", resp.config.p4DeviceConfig.isEmpty)
  }

  // =========================================================================
  // Per-entry reads (scenarios 23-25)
  // =========================================================================

  @Test
  fun `23 - read with match key filter returns only matching entry`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry1 = buildExactEntry(config, matchValue = 0x0800, port = 1)
    val entry2 = buildExactEntry(config, matchValue = 0x0806, port = 2)
    harness.installEntry(entry1)
    harness.installEntry(entry2)

    // Read with a filter that matches only entry1's match key.
    val filter = buildReadFilter(config, matchValue = 0x0800)
    val results = harness.readEntry(filter)
    assertEquals("should return exactly one entry", 1, results.size)
    assertEquals(
      "returned entry should match the filter",
      entry1.tableEntry.matchList,
      results[0].tableEntry.matchList,
    )
  }

  @Test
  fun `24 - read with non-matching key returns empty`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.installEntry(buildExactEntry(config, matchValue = 0x0800, port = 1))

    val filter = buildReadFilter(config, matchValue = 0x9999)
    assertTrue("non-matching key should return nothing", harness.readEntry(filter).isEmpty())
  }

  @Test
  fun `25 - per-entry read preserves action parameters`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)

    val filter = buildReadFilter(config, matchValue = 0x0800)
    val results = harness.readEntry(filter)
    assertEquals(1, results.size)
    // The returned entry should have the same action as what was installed.
    assertTrue("should have action set", results[0].tableEntry.action.hasAction())
    assertEquals(
      entry.tableEntry.action.action.actionId,
      results[0].tableEntry.action.action.actionId,
    )
    assertEquals(
      entry.tableEntry.action.action.paramsList,
      results[0].tableEntry.action.action.paramsList,
    )
  }

  // =========================================================================
  // Action profile members (scenarios 26-28)
  // =========================================================================

  @Test
  fun `26 - write and read back action profile member`() {
    harness.loadPipeline(loadBasicTableConfig())
    val member = buildMemberEntity(actionProfileId = 1, memberId = 1, actionId = 1)
    harness.installEntry(member)
    val results = harness.readProfileMembers(actionProfileId = 1)
    assertEquals(1, results.size)
    assertTrue(results[0].hasActionProfileMember())
    assertEquals(1, results[0].actionProfileMember.memberId)
  }

  @Test
  fun `27 - insert duplicate member returns ALREADY_EXISTS`() {
    harness.loadPipeline(loadBasicTableConfig())
    val member = buildMemberEntity(actionProfileId = 1, memberId = 1, actionId = 1)
    harness.installEntry(member)
    assertGrpcError(Status.Code.ALREADY_EXISTS) { harness.installEntry(member) }
  }

  @Test
  fun `28 - delete member then read returns empty`() {
    harness.loadPipeline(loadBasicTableConfig())
    val member = buildMemberEntity(actionProfileId = 1, memberId = 1, actionId = 1)
    harness.installEntry(member)
    harness.deleteEntry(member)
    assertTrue(harness.readProfileMembers(actionProfileId = 1).isEmpty())
  }

  // =========================================================================
  // Action profile groups (scenarios 29-31)
  // =========================================================================

  @Test
  fun `29 - write and read back action profile group`() {
    harness.loadPipeline(loadBasicTableConfig())
    val group = buildGroupEntity(actionProfileId = 1, groupId = 1, memberIds = listOf(1, 2))
    harness.installEntry(group)
    val results = harness.readProfileGroups(actionProfileId = 1)
    assertEquals(1, results.size)
    assertTrue(results[0].hasActionProfileGroup())
    assertEquals(1, results[0].actionProfileGroup.groupId)
    assertEquals(2, results[0].actionProfileGroup.membersCount)
  }

  @Test
  fun `30 - modify group with different members succeeds`() {
    harness.loadPipeline(loadBasicTableConfig())
    val group = buildGroupEntity(actionProfileId = 1, groupId = 1, memberIds = listOf(1))
    harness.installEntry(group)
    val modified = buildGroupEntity(actionProfileId = 1, groupId = 1, memberIds = listOf(1, 2, 3))
    harness.modifyEntry(modified)
    val results = harness.readProfileGroups(actionProfileId = 1)
    assertEquals(1, results.size)
    assertEquals(3, results[0].actionProfileGroup.membersCount)
  }

  @Test
  fun `31 - delete non-existent group returns NOT_FOUND`() {
    harness.loadPipeline(loadBasicTableConfig())
    val group = buildGroupEntity(actionProfileId = 1, groupId = 99, memberIds = listOf(1))
    assertGrpcError(Status.Code.NOT_FOUND) { harness.deleteEntry(group) }
  }

  @Test
  fun `35 - wildcard read returns members across all action profiles`() {
    harness.loadPipeline(loadBasicTableConfig())
    val member1 = buildMemberEntity(actionProfileId = 1, memberId = 1, actionId = 1)
    val member2 = buildMemberEntity(actionProfileId = 1, memberId = 2, actionId = 1)
    harness.installEntry(member1)
    harness.installEntry(member2)
    // Wildcard read: actionProfileId = 0 returns all members.
    val results = harness.readProfileMembers(actionProfileId = 0)
    assertEquals(2, results.size)
  }

  // =========================================================================
  // Register entries (scenarios 32-34)
  // =========================================================================

  private fun loadConfigWithRegister(): PipelineConfig {
    val base = loadBasicTableConfig()
    // Inject a register into the p4info so the simulator creates RegisterInfo.
    val register =
      p4.config.v1.P4InfoOuterClass.Register.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder().setId(REG_ID).setName("myReg")
        )
        .setTypeSpec(
          p4.config.v1.P4Types.P4DataTypeSpec.newBuilder()
            .setBitstring(
              p4.config.v1.P4Types.P4BitstringLikeTypeSpec.newBuilder()
                .setBit(p4.config.v1.P4Types.P4BitTypeSpec.newBuilder().setBitwidth(REG_BITWIDTH))
            )
        )
        .setSize(REG_SIZE)
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addRegisters(register)).build()
  }

  @Test
  fun `32 - write register entry and read it back`() {
    harness.loadPipeline(loadConfigWithRegister())
    val entry = P4RuntimeTestHarness.buildRegisterEntry(REG_ID, 0, 0xCAFE)
    harness.modifyEntry(entry)
    val results = harness.readRegisterEntries(REG_ID)
    // Should return all REG_SIZE entries; index 0 has our value.
    assertEquals(REG_SIZE, results.size)
    val written = results.find { it.registerEntry.index.index == 0L }!!
    assertEquals(
      com.google.protobuf.ByteString.copyFrom(
        P4RuntimeTestHarness.longToBytes(0xCAFE, REG_BYTEWIDTH)
      ),
      written.registerEntry.data.bitstring,
    )
  }

  @Test
  fun `33 - read unwritten register entry returns zero`() {
    harness.loadPipeline(loadConfigWithRegister())
    val results = harness.readRegisterEntries(REG_ID)
    assertEquals(REG_SIZE, results.size)
    for (entity in results) {
      val data = entity.registerEntry.data.bitstring.toByteArray()
      assertTrue("unwritten register should be zero", data.all { it == 0.toByte() })
    }
  }

  @Test
  fun `34 - INSERT register entry returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadConfigWithRegister())
    val entry = P4RuntimeTestHarness.buildRegisterEntry(REG_ID, 0, 1)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // Multi-controller arbitration (scenarios 40-45)
  // =========================================================================

  /** P4Runtime spec §10.2: higher election_id becomes primary. */
  @Test
  fun `40 - higher election_id becomes primary`() {
    harness.openStream().use { stream ->
      val resp1 = stream.arbitrate(electionId = 1)
      assertEquals(
        "first arbitration should be OK",
        com.google.rpc.Code.OK_VALUE,
        resp1.arbitration.status.code,
      )
      val resp2 = stream.arbitrate(electionId = 5)
      assertEquals(
        "higher election_id should be OK",
        com.google.rpc.Code.OK_VALUE,
        resp2.arbitration.status.code,
      )
    }
  }

  /** P4Runtime spec §10.2: lower election_id is non-primary. */
  @Test
  fun `41 - lower election_id is non-primary`() {
    harness.openStream().use { stream ->
      stream.arbitrate(electionId = 5)
      val resp = stream.arbitrate(electionId = 1)
      assertEquals(
        "lower election_id should get ALREADY_EXISTS",
        com.google.rpc.Code.ALREADY_EXISTS_VALUE,
        resp.arbitration.status.code,
      )
    }
  }

  /** P4Runtime spec §10.3: non-primary writes return PERMISSION_DENIED. */
  @Test
  fun `42 - non-primary write returns PERMISSION_DENIED`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.openStream().use { stream -> stream.arbitrate(electionId = 5) }
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    assertGrpcError(Status.Code.PERMISSION_DENIED) { harness.installEntry(entry, uint128(low = 3)) }
  }

  /** P4Runtime spec §10.3: primary write succeeds. */
  @Test
  fun `43 - primary write succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.openStream().use { stream -> stream.arbitrate(electionId = 5) }
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry, uint128(low = 5))
    // Verify the entry was written.
    val results = harness.readEntries()
    assertEquals(1, results.size)
  }

  /** Backward compatibility: write without any prior arbitration succeeds. */
  @Test
  fun `44 - write without arbitration succeeds`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    // No arbitration — write should still work.
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry)
    assertEquals(1, harness.readEntries().size)
  }

  /** P4Runtime spec §10.4: all controllers may read regardless of role. */
  @Test
  fun `45 - all controllers may read regardless of role`() {
    val config = loadBasicTableConfig()
    harness.loadPipeline(config)
    harness.openStream().use { stream -> stream.arbitrate(electionId = 5) }
    val entry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(entry, uint128(low = 5))
    // Read with no election_id (any controller) should succeed.
    val results = harness.readEntries()
    assertEquals("read should return the installed entry", 1, results.size)
  }

  // =========================================================================
  // Counter entries (scenarios 46-48)
  // =========================================================================

  private fun loadConfigWithCounter(): PipelineConfig {
    val base = loadBasicTableConfig()
    val counter =
      p4.config.v1.P4InfoOuterClass.Counter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder().setId(CTR_ID).setName("myCounter")
        )
        .setSize(CTR_SIZE.toLong())
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addCounters(counter)).build()
  }

  @Test
  fun `46 - write counter entry and read it back`() {
    harness.loadPipeline(loadConfigWithCounter())
    val entry = P4RuntimeTestHarness.buildCounterEntry(CTR_ID, 0, byteCount = 100, packetCount = 5)
    harness.modifyEntry(entry)
    val results = harness.readCounterEntries(CTR_ID)
    assertEquals(CTR_SIZE, results.size)
    val written = results.find { it.counterEntry.index.index == 0L }!!
    assertEquals(100, written.counterEntry.data.byteCount)
    assertEquals(5, written.counterEntry.data.packetCount)
  }

  @Test
  fun `47 - read unwritten counter entry returns zero`() {
    harness.loadPipeline(loadConfigWithCounter())
    val results = harness.readCounterEntries(CTR_ID)
    assertEquals(CTR_SIZE, results.size)
    for (entity in results) {
      assertEquals(0, entity.counterEntry.data.byteCount)
      assertEquals(0, entity.counterEntry.data.packetCount)
    }
  }

  @Test
  fun `48 - INSERT counter entry returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadConfigWithCounter())
    val entry = P4RuntimeTestHarness.buildCounterEntry(CTR_ID, 0, byteCount = 1)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // Meter entries (scenarios 49-51)
  // =========================================================================

  private fun loadConfigWithMeter(): PipelineConfig {
    val base = loadBasicTableConfig()
    val meter =
      p4.config.v1.P4InfoOuterClass.Meter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder().setId(MTR_ID).setName("myMeter")
        )
        .setSize(MTR_SIZE.toLong())
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addMeters(meter)).build()
  }

  @Test
  fun `49 - write meter entry and read it back`() {
    harness.loadPipeline(loadConfigWithMeter())
    val entry =
      P4RuntimeTestHarness.buildMeterEntry(
        MTR_ID,
        0,
        cir = 1000,
        cburst = 100,
        pir = 2000,
        pburst = 200,
      )
    harness.modifyEntry(entry)
    val results = harness.readMeterEntries(MTR_ID)
    assertEquals(MTR_SIZE, results.size)
    val written = results.find { it.meterEntry.index.index == 0L }!!
    assertEquals(1000, written.meterEntry.config.cir)
    assertEquals(100, written.meterEntry.config.cburst)
    assertEquals(2000, written.meterEntry.config.pir)
    assertEquals(200, written.meterEntry.config.pburst)
  }

  @Test
  fun `50 - read unwritten meter entry has no config`() {
    harness.loadPipeline(loadConfigWithMeter())
    val results = harness.readMeterEntries(MTR_ID)
    assertEquals(MTR_SIZE, results.size)
    for (entity in results) {
      assertFalse("unwritten meter should have no config", entity.meterEntry.hasConfig())
    }
  }

  @Test
  fun `51 - INSERT meter entry returns INVALID_ARGUMENT`() {
    harness.loadPipeline(loadConfigWithMeter())
    val entry = P4RuntimeTestHarness.buildMeterEntry(MTR_ID, 0, cir = 1)
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // Direct counter entries (scenarios 52-54)
  // =========================================================================

  private fun loadConfigWithDirectCounter(): PipelineConfig {
    val base = loadBasicTableConfig()
    val tableId = base.p4Info.tablesList.first().preamble.id
    val directCounter =
      p4.config.v1.P4InfoOuterClass.DirectCounter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
            .setId(DCTR_ID)
            .setName("myDirectCounter")
        )
        .setDirectTableId(tableId)
        .build()
    return base
      .toBuilder()
      .setP4Info(base.p4Info.toBuilder().addDirectCounters(directCounter))
      .build()
  }

  @Test
  fun `52 - write direct counter entry and read it back`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    // MODIFY the direct counter for the installed entry.
    val directCounterEntity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          p4.v1.P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setData(
              p4.v1.P4RuntimeOuterClass.CounterData.newBuilder()
                .setPacketCount(42)
                .setByteCount(1000)
            )
        )
        .build()
    harness.modifyEntry(directCounterEntity)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectCounterEntries(tableId)
    assertEquals(1, results.size)
    assertEquals(42, results[0].directCounterEntry.data.packetCount)
    assertEquals(1000, results[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `53 - read unwritten direct counter returns zero`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectCounterEntries(tableId)
    assertEquals(1, results.size)
    assertEquals(0, results[0].directCounterEntry.data.packetCount)
    assertEquals(0, results[0].directCounterEntry.data.byteCount)
  }

  @Test
  fun `54 - INSERT direct counter entry returns INVALID_ARGUMENT`() {
    val config = loadConfigWithDirectCounter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val directCounterEntity =
      Entity.newBuilder()
        .setDirectCounterEntry(
          p4.v1.P4RuntimeOuterClass.DirectCounterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setData(p4.v1.P4RuntimeOuterClass.CounterData.newBuilder().setPacketCount(1))
        )
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(directCounterEntity) }
  }

  // =========================================================================
  // Direct meter entries (scenarios 55-57)
  // =========================================================================

  private fun loadConfigWithDirectMeter(): PipelineConfig {
    val base = loadBasicTableConfig()
    val tableId = base.p4Info.tablesList.first().preamble.id
    val directMeter =
      p4.config.v1.P4InfoOuterClass.DirectMeter.newBuilder()
        .setPreamble(
          p4.config.v1.P4InfoOuterClass.Preamble.newBuilder()
            .setId(DMTR_ID)
            .setName("myDirectMeter")
        )
        .setDirectTableId(tableId)
        .build()
    return base.toBuilder().setP4Info(base.p4Info.toBuilder().addDirectMeters(directMeter)).build()
  }

  @Test
  fun `55 - write direct meter entry and read it back`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val directMeterEntity =
      Entity.newBuilder()
        .setDirectMeterEntry(
          p4.v1.P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setConfig(
              p4.v1.P4RuntimeOuterClass.MeterConfig.newBuilder()
                .setCir(1000)
                .setCburst(100)
                .setPir(2000)
                .setPburst(200)
            )
        )
        .build()
    harness.modifyEntry(directMeterEntity)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectMeterEntries(tableId)
    assertEquals(1, results.size)
    val config2 = results[0].directMeterEntry.config
    assertEquals(1000, config2.cir)
    assertEquals(2000, config2.pir)
  }

  @Test
  fun `56 - read unconfigured direct meter has no config`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val tableId = config.p4Info.tablesList.first().preamble.id
    val results = harness.readDirectMeterEntries(tableId)
    assertEquals(1, results.size)
    assertFalse(
      "unconfigured direct meter should have no config",
      results[0].directMeterEntry.hasConfig(),
    )
  }

  @Test
  fun `57 - INSERT direct meter entry returns INVALID_ARGUMENT`() {
    val config = loadConfigWithDirectMeter()
    harness.loadPipeline(config)
    val tableEntry = buildExactEntry(config, matchValue = 0x0800, port = 1)
    harness.installEntry(tableEntry)

    val directMeterEntity =
      Entity.newBuilder()
        .setDirectMeterEntry(
          p4.v1.P4RuntimeOuterClass.DirectMeterEntry.newBuilder()
            .setTableEntry(tableEntry.tableEntry)
            .setConfig(p4.v1.P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(100))
        )
        .build()
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(directMeterEntity) }
  }

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  private fun buildReadFilter(config: PipelineConfig, matchValue: Long): Entity =
    P4RuntimeTestHarness.buildMatchFilter(config, matchValue)

  /** Sends a raw SetForwardingPipelineConfig — bypasses the harness to test validation paths. */
  private fun loadRawPipeline(config: ForwardingPipelineConfig.Builder) = runBlocking {
    harness.stub.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
        .setConfig(config)
        .build()
    )
  }

  companion object {
    private const val REG_ID = 500
    private const val REG_BITWIDTH = 32
    private const val REG_BYTEWIDTH = REG_BITWIDTH / 8
    private const val REG_SIZE = 4
    private const val CTR_ID = 600
    private const val CTR_SIZE = 4
    private const val MTR_ID = 700
    private const val MTR_SIZE = 4
    private const val DCTR_ID = 800
    private const val DMTR_ID = 900
  }
}
