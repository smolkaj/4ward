package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    val vrfMatch = entry.matchList.find { it.fieldId == 1 }!!
    assertEquals("vrf-10", vrfMatch.exact.value.toStringUtf8())

    // Verify set_nexthop_id action param round-trips as string.
    val action = entry.action.action
    val setNexthopId = findAction("set_nexthop_id")
    assertEquals(setNexthopId.preamble.id, action.actionId)
    val nexthopParam = action.paramsList.find { it.paramId == 1 }!!
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
    val rifParam = action.paramsList.find { it.paramId == 1 }!!
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
    val portParam = action.paramsList.find { it.paramId == 1 }!!
    assertEquals("Ethernet0", portParam.value.toStringUtf8())
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
  // Helpers
  // =========================================================================

  private fun findTable(alias: String) =
    config.p4Info.tablesList.find { it.preamble.alias == alias }
      ?: error("table '$alias' not found in p4info")

  private fun findAction(alias: String) =
    config.p4Info.actionsList.find { it.preamble.alias == alias }
      ?: error("action '$alias' not found in p4info")

  private fun stringExactMatch(fieldId: Int, value: String): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFromUtf8(value)))
      .build()

  private fun stringParam(paramId: Int, value: String): p4.v1.P4RuntimeOuterClass.Action.Param =
    p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId)
      .setValue(ByteString.copyFromUtf8(value))
      .build()

  /** Builds a vrf_table entry: exact match on vrf_id (string) → no_action. */
  private fun buildVrfEntry(vrfId: String): Entity {
    val table = findTable("vrf_table")
    val noAction = findAction("no_action")

    val tableEntry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(stringExactMatch(1, vrfId))
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder().setActionId(noAction.preamble.id)
            )
        )
        .build()

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  /**
   * Builds a router_interface_table entry:
   * - match: router_interface_id (string, exact)
   * - action: set_port_and_src_mac(port: string, src_mac: 48-bit MAC)
   */
  private fun buildRouterInterfaceEntry(rifId: String, port: String): Entity {
    val table = findTable("router_interface_table")
    val action = findAction("set_port_and_src_mac")

    // src_mac: non-zero (action_restriction enforces src_mac != 0).
    val srcMacParam =
      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
        .setParamId(2)
        .setValue(ByteString.copyFrom(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)))
        .build()

    val tableEntry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(stringExactMatch(1, rifId))
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(action.preamble.id)
                .addParams(stringParam(1, port))
                .addParams(srcMacParam)
            )
        )
        .build()

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  /**
   * Builds an ipv4_table entry:
   * - match: vrf_id (string, exact) + ipv4_dst 10.0.0.0/8 (LPM)
   * - action: set_nexthop_id(nexthop_id: string)
   */
  private fun buildIpv4RouteEntry(vrfId: String, nexthopId: String): Entity {
    val table = findTable("ipv4_table")
    val action = findAction("set_nexthop_id")

    // ipv4_dst: 10.0.0.0/8 as LPM.
    val ipv4DstMatch =
      FieldMatch.newBuilder()
        .setFieldId(2)
        .setLpm(
          FieldMatch.LPM.newBuilder()
            .setValue(ByteString.copyFrom(byteArrayOf(10, 0, 0, 0)))
            .setPrefixLen(8)
        )
        .build()

    val tableEntry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(stringExactMatch(1, vrfId))
        .addMatch(ipv4DstMatch)
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(action.preamble.id)
                .addParams(stringParam(1, nexthopId))
            )
        )
        .build()

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  /**
   * Builds a nexthop_table entry:
   * - match: nexthop_id (string, exact)
   * - action: set_ip_nexthop(router_interface_id: string, neighbor_id: IPv6)
   */
  private fun buildNexthopEntry(nexthopId: String, routerInterfaceId: String): Entity {
    val table = findTable("nexthop_table")
    val action = findAction("set_ip_nexthop")

    // neighbor_id is ipv6_addr_t (128-bit) — NOT translated.
    val neighborParam =
      p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
        .setParamId(2)
        .setValue(
          ByteString.copyFrom(
            ByteArray(16) { if (it == 15) 1 else 0 } // ::1
          )
        )
        .build()

    val tableEntry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addMatch(stringExactMatch(1, nexthopId))
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              p4.v1.P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(action.preamble.id)
                .addParams(stringParam(1, routerInterfaceId))
                .addParams(neighborParam)
            )
        )
        .build()

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }
}
