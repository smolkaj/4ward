package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * End-to-end test of the P4Runtime server with SAI P4 (middleblock).
 *
 * SAI P4 uses `@p4runtime_translation("", string)` on all ID types (vrf_id_t, nexthop_id_t,
 * router_interface_id_t, port_id_t, etc.). String SDN values are UTF-8-encoded in the proto `bytes`
 * field. This test verifies that the full stack — p4c-4ward compilation, simulator loading,
 * P4Runtime Write/Read with sdn_string translation — works end-to-end.
 */
class SaiP4E2ETest {

  private lateinit var harness: P4RuntimeTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
    config = P4RuntimeTestHarness.loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
    harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // =========================================================================
  // Pipeline loading
  // =========================================================================

  @Test
  fun `SAI P4 middleblock pipeline loads successfully`() {
    // Pipeline was loaded in setUp — verify p4info has the expected tables.
    val tableNames = config.p4Info.tablesList.map { it.preamble.alias }
    assertTrue("vrf_table should be present", "vrf_table" in tableNames)
    assertTrue("ipv4_table should be present", "ipv4_table" in tableNames)
    assertTrue("nexthop_table should be present", "nexthop_table" in tableNames)
    assertTrue("router_interface_table should be present", "router_interface_table" in tableNames)
    assertTrue("neighbor_table should be present", "neighbor_table" in tableNames)
  }

  @Test
  fun `pipeline config has VRF translation mapping from annotation`() {
    // The SAI P4 source declares @p4runtime_translation_mappings({ {"", 0} }) on vrf_id_t.
    // The p4c-4ward backend extracts this into DeviceConfig.translations.
    val translations = config.device.translationsList
    assertTrue("expected at least one translation", translations.isNotEmpty())

    val vrfTranslation = translations.find { it.uri == "" }
    assertTrue("expected translation with empty URI (vrf_id_t)", vrfTranslation != null)
    assertTrue("expected auto_allocate=true", vrfTranslation!!.autoAllocate)
    assertEquals("expected one explicit entry", 1, vrfTranslation.entriesCount)

    val entry = vrfTranslation.entriesList[0]
    assertEquals("SDN value should be empty string", "", entry.sdnStr)
    // Dataplane value is 0 encoded as 2 bytes (VRF_BITWIDTH=10 → 2 bytes).
    assertEquals("dataplane value should be 2 bytes", 2, entry.dataplaneValue.size())
    assertTrue(
      "dataplane value should be all zeros",
      entry.dataplaneValue.toByteArray().all { it == 0.toByte() },
    )
  }

  @Test
  fun `p4info has sdn_string translated types`() {
    val translatedTypes = config.p4Info.typeInfo.newTypesMap
    val stringTypes =
      translatedTypes.filter { (_, spec) ->
        spec.hasTranslatedType() && spec.translatedType.hasSdnString()
      }
    // SAI P4 declares 8 string-translated types (all use URI "").
    assertTrue(
      "expected at least 8 sdn_string types, got ${stringTypes.size}",
      stringTypes.size >= 8,
    )
    assertTrue("vrf_id_t should be sdn_string", "vrf_id_t" in stringTypes)
    assertTrue("nexthop_id_t should be sdn_string", "nexthop_id_t" in stringTypes)
    assertTrue("port_id_t should be sdn_string", "port_id_t" in stringTypes)
  }

  // =========================================================================
  // VRF table: sdn_string match field
  // =========================================================================

  @Test
  fun `vrf_table entry with string vrf_id round-trips`() {
    val vrfEntry = buildVrfEntry("vrf-1")
    harness.installEntry(vrfEntry)

    val vrfTable = findTable("vrf_table")
    val entities = harness.readTableEntries(vrfTable.preamble.id)
    assertEquals("expected one vrf_table entry", 1, entities.size)

    // The match field (vrf_id) should round-trip as a UTF-8 string.
    val readMatch = entities[0].tableEntry.matchList.first()
    assertEquals("vrf-1", readMatch.exact.value.toStringUtf8())
  }

  @Test
  fun `multiple vrf_table entries with different string IDs`() {
    harness.installEntry(buildVrfEntry("vrf-1"))
    harness.installEntry(buildVrfEntry("vrf-2"))
    harness.installEntry(buildVrfEntry("vrf-3"))

    val vrfTable = findTable("vrf_table")
    val entities = harness.readTableEntries(vrfTable.preamble.id)
    assertEquals("expected three vrf_table entries", 3, entities.size)

    val vrfIds = entities.map { it.tableEntry.matchList.first().exact.value.toStringUtf8() }.toSet()
    assertEquals(setOf("vrf-1", "vrf-2", "vrf-3"), vrfIds)
  }

  // =========================================================================
  // IPv4 routing: string vrf_id match + set_nexthop_id action param
  // =========================================================================

  @Test
  fun `ipv4_table entry with translated vrf_id and nexthop_id round-trips`() {
    // First install a VRF.
    harness.installEntry(buildVrfEntry("vrf-10"))

    // Install an IPv4 route: vrf_id="vrf-10", ipv4_dst=10.0.0.0/8 → set_nexthop_id("nhop-1").
    val ipv4Entry = buildIpv4RouteEntry("vrf-10", "nhop-1")
    harness.installEntry(ipv4Entry)

    val ipv4Table = findTable("ipv4_table")
    val entities = harness.readTableEntries(ipv4Table.preamble.id)
    assertEquals("expected one ipv4_table entry", 1, entities.size)

    val entry = entities[0].tableEntry

    // Verify vrf_id match field round-trips as string.
    val vrfFieldId = matchFieldId(ipv4Table, "vrf_id")
    val vrfMatch = entry.matchList.find { it.fieldId == vrfFieldId }!!
    assertEquals("vrf-10", vrfMatch.exact.value.toStringUtf8())

    // Verify set_nexthop_id action param round-trips as string.
    val action = entry.action.action
    val setNexthopId = findAction("set_nexthop_id")
    assertEquals(setNexthopId.preamble.id, action.actionId)
    val nexthopParamId = paramId(setNexthopId, "nexthop_id")
    val nexthopParam = action.paramsList.find { it.paramId == nexthopParamId }!!
    assertEquals("nhop-1", nexthopParam.value.toStringUtf8())
  }

  // =========================================================================
  // Nexthop table: string match + action params with translated types
  // =========================================================================

  @Test
  fun `nexthop_table entry with set_ip_nexthop round-trips`() {
    val nexthopEntry = buildNexthopEntry("nhop-1", "rif-1")
    harness.installEntry(nexthopEntry)

    val nexthopTable = findTable("nexthop_table")
    val entities = harness.readTableEntries(nexthopTable.preamble.id)
    assertEquals("expected one nexthop_table entry", 1, entities.size)

    val entry = entities[0].tableEntry

    // Verify nexthop_id match round-trips as string.
    val nexthopMatch = entry.matchList.first()
    assertEquals("nhop-1", nexthopMatch.exact.value.toStringUtf8())

    // Verify router_interface_id action param round-trips as string.
    val action = entry.action.action
    val setIpNexthop = findAction("set_ip_nexthop")
    val rifParamId = paramId(setIpNexthop, "router_interface_id")
    val rifParam = action.paramsList.find { it.paramId == rifParamId }!!
    assertEquals("rif-1", rifParam.value.toStringUtf8())
  }

  // =========================================================================
  // Router interface table: port_id_t in action param (the SAI "port as string" pattern)
  // =========================================================================

  @Test
  fun `router_interface_table entry with port_id_t param round-trips`() {
    val rifEntry = buildRouterInterfaceEntry("rif-1", "Ethernet0")
    harness.installEntry(rifEntry)

    val rifTable = findTable("router_interface_table")
    val entities = harness.readTableEntries(rifTable.preamble.id)
    assertEquals("expected one router_interface_table entry", 1, entities.size)

    val entry = entities[0].tableEntry

    // router_interface_id match (string).
    val rifMatch = entry.matchList.first()
    assertEquals("rif-1", rifMatch.exact.value.toStringUtf8())

    // port param (port_id_t, string) and src_mac param (48-bit, not translated).
    val action = entry.action.action
    val setPortAndSrcMac = findAction("set_port_and_src_mac")
    val portParamId = paramId(setPortAndSrcMac, "port")
    val portParam = action.paramsList.find { it.paramId == portParamId }!!
    assertEquals("Ethernet0", portParam.value.toStringUtf8())
  }

  // =========================================================================
  // Packet forwarding
  // =========================================================================

  @Test
  fun `IPv4 packet is forwarded with correct MAC rewrites and TTL decrement`() {
    // Install routing entries via P4Runtime.
    // Default VRF (vrf_id="") is implicit — no vrf_table entry needed.
    harness.installEntry(buildIpv4RouteEntry(vrfId = "", nexthopId = "nhop-1"))
    harness.installEntry(buildNexthopEntry(nexthopId = "nhop-1", routerInterfaceId = "rif-1"))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet1", RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))

    // Build and send an IPv4 packet.
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = packet)

    assertEquals("expected exactly one output packet", 1, outputs.size)
    val output = outputs[0].payload.toByteArray()

    // dst_mac rewritten to neighbor MAC.
    assertBytesEqual("dst_mac", NEIGHBOR_MAC, output, 0)
    // src_mac rewritten to RIF MAC.
    assertBytesEqual("src_mac", RIF_MAC, output, MAC_LEN)
    // TTL decremented by 1.
    assertEquals("TTL should be decremented", 63, output[TTL_OFFSET].toInt() and 0xFF)
    // IP addresses unchanged.
    assertBytesEqual("src_ip", SRC_IP, output, SRC_IP_OFFSET)
    assertBytesEqual("dst_ip", DST_IP, output, DST_IP_OFFSET)
  }

  // =========================================================================
  // PacketIO: PacketOut via StreamChannel
  // =========================================================================

  @Test
  fun `PacketOut with submit_to_ingress=0 exits on specified port`() {
    // Look up packet_out metadata IDs from p4info.
    val packetOutMeta =
      config.p4Info.controllerPacketMetadataList.find { it.preamble.name == "packet_out" }!!
    val egressPortId = packetOutMeta.metadataList.find { it.name == "egress_port" }!!.id
    val submitToIngressId = packetOutMeta.metadataList.find { it.name == "submit_to_ingress" }!!.id

    // Build a simple Ethernet payload.
    val payload = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)

    // Build PacketOut with egress_port as a string (port_id_t is sdn_string).
    val packetOut =
      p4.v1.P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(payload))
        .addMetadata(
          p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(egressPortId)
            .setValue(ByteString.copyFromUtf8("Ethernet1"))
        )
        .addMetadata(
          p4.v1.P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(submitToIngressId)
            .setValue(ByteString.copyFrom(byteArrayOf(0)))
        )
        .build()

    // Open stream, arbitrate, and send.
    harness.openStream().use { session ->
      session.arbitrate()
      val response = session.sendPacketOut(packetOut)

      // Should receive a PacketIn with the original payload.
      assertTrue("expected a PacketIn response", response != null && response.hasPacket())
      val packetIn = response!!.packet

      // Payload should match the original Ethernet frame (except IPv4 checksum
      // which the deparser computes). Verify key fields individually.
      val output = packetIn.payload.toByteArray()
      assertEquals("payload size should match", payload.size, output.size)
      // Ethernet header unchanged.
      assertBytesEqual("dst_mac", UNICAST_MAC, output, 0)
      assertBytesEqual("src_mac", SRC_MAC, output, MAC_LEN)
      // TTL unchanged (bypass_ingress skips routing, no decrement).
      assertEquals("TTL", 64, output[TTL_OFFSET].toInt() and 0xFF)

      // Should have PacketIn metadata (ingress_port + target_egress_port).
      val packetInMeta =
        config.p4Info.controllerPacketMetadataList.find { it.preamble.name == "packet_in" }!!
      val targetEgressPortId =
        packetInMeta.metadataList.find { it.name == "target_egress_port" }!!.id
      val egressMeta = packetIn.metadataList.find { it.metadataId == targetEgressPortId }
      assertTrue("expected target_egress_port metadata", egressMeta != null)
      // The target_egress_port should reverse-translate to "Ethernet1".
      assertEquals("Ethernet1", egressMeta!!.value.toStringUtf8())
    }
  }

  // =========================================================================
  // ACL ingress: drop
  // =========================================================================

  @Test
  fun `ACL ingress drop prevents packet from being forwarded`() {
    // Install normal routing so the packet would be forwarded without ACL.
    installRoutingChain()

    // Install ACL entry matching dst_ip=10.0.0.1 → acl_drop.
    val aclTable = findTable("acl_ingress_table")
    val aclDrop = findAction("acl_drop")
    harness.installEntry(
      buildEntry(
        aclTable,
        aclDrop,
        matches =
          listOf(
            optionalMatch(aclTable, "is_ipv4", byteArrayOf(1)),
            ternaryMatch(aclTable, "dst_ip", DST_IP, byteArrayOf(-1, -1, -1, -1)),
          ),
        priority = 1,
      )
    )

    // Send packet — should be dropped by ACL.
    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = packet)
    assertEquals("packet should be dropped by ACL", 0, outputs.size)
  }

  // =========================================================================
  // ACL ingress: redirect to nexthop
  // =========================================================================

  @Test
  fun `ACL redirect to nexthop overrides normal routing`() {
    // Install normal routing: dst 10.0.0.0/8 → nhop-1 → rif-1 → Ethernet1.
    installRoutingChain()

    // Install a second path: nhop-2 → rif-2 → Ethernet2 with a different MAC.
    harness.installEntry(buildNexthopEntry("nhop-2", "rif-2"))
    harness.installEntry(buildRouterInterfaceEntry("rif-2", "Ethernet2", ALT_RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-2", NEIGHBOR_ID, ALT_NEIGHBOR_MAC))

    // ACL: redirect packets to dst_ip=10.0.0.1 → nhop-2 (overriding the route).
    val aclTable = findTable("acl_ingress_table")
    val redirectAction = findAction("redirect_to_nexthop")
    harness.installEntry(
      buildEntry(
        aclTable,
        redirectAction,
        matches =
          listOf(
            optionalMatch(aclTable, "is_ipv4", byteArrayOf(1)),
            ternaryMatch(aclTable, "dst_ip", DST_IP, byteArrayOf(-1, -1, -1, -1)),
          ),
        params = listOf(stringParam(redirectAction, "nexthop_id", "nhop-2")),
        priority = 1,
      )
    )

    val packet = buildIpv4Packet(dstMac = UNICAST_MAC, srcMac = SRC_MAC, ttl = 64)
    val outputs = harness.simulatePacket(ingressPort = 0, payload = packet)

    assertEquals("expected exactly one output packet", 1, outputs.size)
    val output = outputs[0].payload.toByteArray()
    // dst_mac should be ALT_NEIGHBOR_MAC (from nhop-2 path), not NEIGHBOR_MAC.
    assertBytesEqual("dst_mac from redirect path", ALT_NEIGHBOR_MAC, output, 0)
    assertBytesEqual("src_mac from redirect path", ALT_RIF_MAC, output, MAC_LEN)
  }

  // =========================================================================
  // IPv4 multicast: packet replication
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `IPv4 multicast replicates packet to multiple ports`() {
    // Install multicast route via ipv4_multicast_table (EXACT match on ipv4_dst).
    val mcastTable = findTable("ipv4_multicast_table")
    val setMcastGroup = findAction("set_multicast_group_id")
    val mcastDstIp = byteArrayOf(224.toByte(), 0, 0, 1)
    harness.installEntry(
      buildEntry(
        mcastTable,
        setMcastGroup,
        matches =
          listOf(
            exactMatch(mcastTable, "vrf_id", ""),
            exactMatch(mcastTable, "ipv4_dst", mcastDstIp),
          ),
        params = listOf(bytesParam(setMcastGroup, "multicast_group_id", byteArrayOf(0, 1))),
      )
    )

    // Install multicast group 1 with two replicas: port 0 and port 1.
    installMulticastGroup(groupId = 1, ports = listOf(0, 1))

    // Send a multicast-destined packet (224.0.0.1) with IPv4 multicast MAC.
    // SAI P4 gates ipv4_multicast_table on IS_IPV4_MULTICAST_MAC(dst_addr).
    val packet =
      buildIpv4Packet(dstMac = MULTICAST_MAC, srcMac = SRC_MAC, ttl = 64, dstIp = mcastDstIp)
    val outputs = harness.simulatePacket(ingressPort = 2, payload = packet)

    assertTrue(
      "expected at least 2 output packets for multicast, got ${outputs.size}",
      outputs.size >= 2,
    )
  }

  // =========================================================================
  // WCMP: action selector member and group round-trip
  // =========================================================================

  @Test
  fun `WCMP action profile members and groups round-trip through SAI P4`() {
    // Find the WCMP action selector profile ID.
    val wcmpSelector =
      config.p4Info.actionProfilesList.find { it.preamble.alias == "wcmp_group_selector" }!!
    val actionProfileId = wcmpSelector.preamble.id

    // Install two action profile members pointing to different nexthops.
    val setNexthop = findAction("set_nexthop_id")
    harness.installEntry(
      buildActionProfileMember(actionProfileId, memberId = 1, setNexthop, "nhop-a")
    )
    harness.installEntry(
      buildActionProfileMember(actionProfileId, memberId = 2, setNexthop, "nhop-b")
    )

    // Install a WCMP group with both members.
    harness.installEntry(buildActionProfileGroup(actionProfileId, groupId = 1, listOf(1, 2)))

    // Read back and verify.
    val members = harness.readProfileMembers(actionProfileId)
    assertEquals("expected 2 profile members", 2, members.size)

    val groups = harness.readProfileGroups(actionProfileId)
    assertEquals("expected 1 profile group", 1, groups.size)
    assertEquals(2, groups[0].actionProfileGroup.membersCount)

    // Verify members carry the translated nexthop_id params.
    val nexthopParam = paramId(setNexthop, "nexthop_id")
    val memberNexthops =
      members.map { m ->
        m.actionProfileMember.action.paramsList
          .find { it.paramId == nexthopParam }
          ?.value
          ?.toStringUtf8()
      }
    assertTrue("nhop-a should be in members", "nhop-a" in memberNexthops)
    assertTrue("nhop-b should be in members", "nhop-b" in memberNexthops)
  }

  // =========================================================================
  // Delete: verify translated entries can be removed
  // =========================================================================

  @Test
  fun `delete removes translated entry and read returns empty`() {
    val vrfEntry = buildVrfEntry("vrf-to-delete")
    harness.installEntry(vrfEntry)

    val vrfTable = findTable("vrf_table")
    assertEquals(1, harness.readTableEntries(vrfTable.preamble.id).size)

    harness.deleteEntry(vrfEntry)
    assertEquals(0, harness.readTableEntries(vrfTable.preamble.id).size)
  }

  // =========================================================================
  // p4info lookup helpers — delegates to P4RuntimeTestHarness.Companion
  // =========================================================================

  private fun findTable(alias: String) = P4RuntimeTestHarness.findTable(config, alias)

  private fun findAction(alias: String) = P4RuntimeTestHarness.findAction(config, alias)

  private fun matchFieldId(table: P4InfoOuterClass.Table, name: String) =
    P4RuntimeTestHarness.matchFieldId(table, name)

  private fun paramId(action: P4InfoOuterClass.Action, name: String) =
    P4RuntimeTestHarness.paramId(action, name)

  // =========================================================================
  // Match field and action param builders
  // =========================================================================

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
      .build()

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: String,
  ): FieldMatch = exactMatch(table, fieldName, value.toByteArray(Charsets.UTF_8))

  private fun optionalMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setOptional(FieldMatch.Optional.newBuilder().setValue(ByteString.copyFrom(value)))
      .build()

  private fun ternaryMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    mask: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setTernary(
        FieldMatch.Ternary.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setMask(ByteString.copyFrom(mask))
      )
      .build()

  @Suppress("SameParameterValue")
  private fun lpmMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    prefixLen: Int,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setLpm(
        FieldMatch.LPM.newBuilder().setValue(ByteString.copyFrom(value)).setPrefixLen(prefixLen)
      )
      .build()

  private fun stringParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: String,
  ): p4.v1.P4RuntimeOuterClass.Action.Param =
    p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFromUtf8(value))
      .build()

  private fun bytesParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: ByteArray,
  ): p4.v1.P4RuntimeOuterClass.Action.Param =
    p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFrom(value))
      .build()

  // =========================================================================
  // Table entry builders
  // =========================================================================

  /** Builds a table entry with the given matches, action params, and optional priority. */
  private fun buildEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    matches: List<FieldMatch>,
    params: List<p4.v1.P4RuntimeOuterClass.Action.Param> = emptyList(),
    priority: Int = 0,
  ): Entity {
    val entry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addAllMatch(matches)
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(action.preamble.id)
                .addAllParams(params)
            )
        )
    if (priority > 0) entry.setPriority(priority)
    return Entity.newBuilder().setTableEntry(entry).build()
  }

  /** Builds a vrf_table entry: exact match on vrf_id (string) → no_action. */
  private fun buildVrfEntry(vrfId: String): Entity {
    val table = findTable("vrf_table")
    val action = findAction("no_action")
    return buildEntry(table, action, matches = listOf(exactMatch(table, "vrf_id", vrfId)))
  }

  /**
   * Builds a router_interface_table entry:
   * - match: router_interface_id (string, exact)
   * - action: set_port_and_src_mac(port: string, src_mac: 48-bit MAC)
   */
  private fun buildRouterInterfaceEntry(
    rifId: String,
    port: String,
    srcMac: ByteArray = RIF_MAC,
  ): Entity {
    val table = findTable("router_interface_table")
    val action = findAction("set_port_and_src_mac")
    return buildEntry(
      table,
      action,
      matches = listOf(exactMatch(table, "router_interface_id", rifId)),
      params = listOf(stringParam(action, "port", port), bytesParam(action, "src_mac", srcMac)),
    )
  }

  /**
   * Builds an ipv4_table entry:
   * - match: vrf_id (string, exact) + ipv4_dst 10.0.0.0/8 (LPM)
   * - action: set_nexthop_id(nexthop_id: string)
   */
  private fun buildIpv4RouteEntry(vrfId: String, nexthopId: String): Entity {
    val table = findTable("ipv4_table")
    val action = findAction("set_nexthop_id")
    return buildEntry(
      table,
      action,
      matches =
        listOf(
          exactMatch(table, "vrf_id", vrfId),
          lpmMatch(table, "ipv4_dst", byteArrayOf(10, 0, 0, 0), prefixLen = 8),
        ),
      params = listOf(stringParam(action, "nexthop_id", nexthopId)),
    )
  }

  /**
   * Builds a nexthop_table entry:
   * - match: nexthop_id (string, exact)
   * - action: set_ip_nexthop(router_interface_id: string, neighbor_id: IPv6)
   */
  private fun buildNexthopEntry(
    nexthopId: String,
    routerInterfaceId: String,
    neighborId: ByteArray = NEIGHBOR_ID,
  ): Entity {
    val table = findTable("nexthop_table")
    val action = findAction("set_ip_nexthop")
    return buildEntry(
      table,
      action,
      matches = listOf(exactMatch(table, "nexthop_id", nexthopId)),
      params =
        listOf(
          stringParam(action, "router_interface_id", routerInterfaceId),
          bytesParam(action, "neighbor_id", neighborId),
        ),
    )
  }

  /**
   * Builds a neighbor_table entry:
   * - match: router_interface_id (string, exact) + neighbor_id (IPv6, exact)
   * - action: set_dst_mac(dst_mac: 48-bit MAC)
   */
  @Suppress("SameParameterValue")
  private fun buildNeighborEntry(rifId: String, neighborId: ByteArray, dstMac: ByteArray): Entity {
    val table = findTable("neighbor_table")
    val action = findAction("set_dst_mac")
    return buildEntry(
      table,
      action,
      matches =
        listOf(
          exactMatch(table, "router_interface_id", rifId),
          exactMatch(table, "neighbor_id", neighborId),
        ),
      params = listOf(bytesParam(action, "dst_mac", dstMac)),
    )
  }

  // =========================================================================
  // Packet builders and assertions
  // =========================================================================

  /** Builds a minimal Ethernet + IPv4 packet (no payload beyond the IP header). */
  @Suppress("SameParameterValue", "MagicNumber")
  private fun buildIpv4Packet(
    dstMac: ByteArray,
    srcMac: ByteArray,
    ttl: Int,
    srcIp: ByteArray = SRC_IP,
    dstIp: ByteArray = DST_IP,
  ): ByteArray {
    val packet = ByteArray(ETHERNET_HEADER_LEN + IPV4_HEADER_LEN)
    // Ethernet header: dst_mac + src_mac + ethertype (0x0800 = IPv4).
    System.arraycopy(dstMac, 0, packet, 0, MAC_LEN)
    System.arraycopy(srcMac, 0, packet, MAC_LEN, MAC_LEN)
    packet[12] = 0x08.toByte()
    packet[13] = 0x00.toByte()
    // IPv4 header (20 bytes, no options).
    packet[14] = 0x45.toByte() // version=4, IHL=5
    // total length = 20
    packet[16] = 0x00.toByte()
    packet[17] = IPV4_HEADER_LEN.toByte()
    packet[22] = ttl.toByte()
    packet[23] = 0x06.toByte() // protocol = TCP (arbitrary, not checked)
    // Checksum left as 0 — SAI P4 doesn't verify ingress checksums.
    System.arraycopy(srcIp, 0, packet, SRC_IP_OFFSET, 4)
    System.arraycopy(dstIp, 0, packet, DST_IP_OFFSET, 4)
    return packet
  }

  /** Installs the standard routing chain: VRF → IPv4 route → nexthop → RIF → neighbor. */
  private fun installRoutingChain() {
    harness.installEntry(buildIpv4RouteEntry(vrfId = "", nexthopId = "nhop-1"))
    harness.installEntry(buildNexthopEntry(nexthopId = "nhop-1", routerInterfaceId = "rif-1"))
    harness.installEntry(buildRouterInterfaceEntry("rif-1", "Ethernet1", RIF_MAC))
    harness.installEntry(buildNeighborEntry("rif-1", NEIGHBOR_ID, NEIGHBOR_MAC))
  }

  /** Installs a PRE multicast group with one replica per port. */
  @Suppress("MagicNumber")
  private fun installMulticastGroup(groupId: Int, ports: List<Int>) {
    val entry =
      P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
        .setMulticastGroupId(groupId)
        .addAllReplicas(
          ports.mapIndexed { idx, port ->
            P4RuntimeOuterClass.Replica.newBuilder().setEgressPort(port).setInstance(idx).build()
          }
        )
        .build()
    val preEntity =
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(entry)
        )
        .build()
    harness.installEntry(preEntity)
  }

  /** Builds an action profile member for the WCMP selector. */
  private fun buildActionProfileMember(
    actionProfileId: Int,
    memberId: Int,
    action: P4InfoOuterClass.Action,
    nexthopId: String,
  ): Entity =
    Entity.newBuilder()
      .setActionProfileMember(
        P4RuntimeOuterClass.ActionProfileMember.newBuilder()
          .setActionProfileId(actionProfileId)
          .setMemberId(memberId)
          .setAction(
            P4RuntimeOuterClass.Action.newBuilder()
              .setActionId(action.preamble.id)
              .addParams(stringParam(action, "nexthop_id", nexthopId))
          )
      )
      .build()

  /** Builds an action profile group for the WCMP selector. */
  private fun buildActionProfileGroup(
    actionProfileId: Int,
    groupId: Int,
    memberIds: List<Int>,
  ): Entity =
    Entity.newBuilder()
      .setActionProfileGroup(
        P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
          .setActionProfileId(actionProfileId)
          .setGroupId(groupId)
          .addAllMembers(
            memberIds.map { mid ->
              P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
                .setMemberId(mid)
                .setWeight(1)
                .build()
            }
          )
      )
      .build()

  /** Asserts that [expected] bytes match [actual] starting at [offset]. */
  private fun assertBytesEqual(label: String, expected: ByteArray, actual: ByteArray, offset: Int) {
    for (i in expected.indices) {
      assertEquals("$label byte $i mismatch", expected[i], actual[offset + i])
    }
  }

  companion object {
    private const val MAC_LEN = 6
    private const val ETHERNET_HEADER_LEN = 14
    private const val IPV4_HEADER_LEN = 20
    private const val TTL_OFFSET = 22 // Ethernet(14) + IPv4 byte 8
    private const val SRC_IP_OFFSET = 26 // Ethernet(14) + IPv4 byte 12
    private const val DST_IP_OFFSET = 30 // Ethernet(14) + IPv4 byte 16

    // ::1 — used as neighbor_id in nexthop and neighbor table entries.
    private val NEIGHBOR_ID = ByteArray(16) { if (it == 15) 1 else 0 }

    private val NEIGHBOR_MAC =
      byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
    private val ALT_NEIGHBOR_MAC =
      byteArrayOf(0x00, 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    private val RIF_MAC = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val ALT_RIF_MAC = byteArrayOf(0x00, 0x22, 0x33, 0x44, 0x55, 0x66)
    private val UNICAST_MAC = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)

    // IPv4 multicast MAC for 224.0.0.1: 01:00:5E:00:00:01 (bit 23 = 0).
    // SAI P4 checks IS_IPV4_MULTICAST_MAC(dst_addr) = addr[47:24]==0x01005E && addr[23:23]==0.
    private val MULTICAST_MAC = byteArrayOf(0x01, 0x00, 0x5E, 0x00, 0x00, 0x01)
    private val SRC_MAC = byteArrayOf(0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E)
    private val SRC_IP = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
    private val DST_IP = byteArrayOf(10, 0, 0, 1)
  }
}
