package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.assertGrpcError
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.longToBytes
import io.grpc.Status
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.After
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Tests that @entry_restriction and @action_restriction constraints on SAI P4 middleblock tables
 * are enforced via the p4-constraints validator.
 *
 * Each test constructs a table entry that violates a specific constraint and verifies that the
 * P4Runtime Write RPC rejects it with INVALID_ARGUMENT.
 */
class SaiP4ConstraintTest {

  private lateinit var harness: P4RuntimeTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness(constraintValidatorBinary = VALIDATOR_BINARY)
    config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
    harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // =========================================================================
  // @entry_restriction: acl_pre_ingress_table
  // =========================================================================

  @Test
  fun `acl_pre_ingress - valid entry with dscp on IPv4 is accepted`() {
    // dscp::mask != 0 with is_ipv4 == 1 → constraint satisfied.
    val table = findTable("acl_pre_ingress_table")
    val action = findAction("set_vrf")
    val entry = buildTernaryAclEntry(
      table, action,
      ternaryFields = mapOf(
        "dscp" to TernaryValue(value = 0x10, mask = 0x3F, byteLen = 1),
      ),
      optionalFields = mapOf(
        "is_ipv4" to OptionalValue(value = 1, byteLen = 1),
      ),
      actionParams = mapOf("vrf_id" to stringValue("vrf-1")),
    )
    harness.installEntry(entry)
  }

  @Test
  fun `acl_pre_ingress - dscp match without IP type is rejected`() {
    // dscp::mask != 0 but no is_ip/is_ipv4/is_ipv6 → violates constraint.
    val table = findTable("acl_pre_ingress_table")
    val action = findAction("set_vrf")
    val entry = buildTernaryAclEntry(
      table, action,
      ternaryFields = mapOf(
        "dscp" to TernaryValue(value = 0x10, mask = 0x3F, byteLen = 1),
      ),
      actionParams = mapOf("vrf_id" to stringValue("vrf-1")),
    )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  @Test
  fun `acl_pre_ingress - is_ipv4 and is_ipv6 both matched is rejected`() {
    // Mutual exclusion: is_ipv4::mask != 0 -> is_ipv6::mask == 0.
    val table = findTable("acl_pre_ingress_table")
    val action = findAction("set_vrf")
    val entry = buildTernaryAclEntry(
      table, action,
      optionalFields = mapOf(
        "is_ipv4" to OptionalValue(value = 1, byteLen = 1),
        "is_ipv6" to OptionalValue(value = 1, byteLen = 1),
      ),
      actionParams = mapOf("vrf_id" to stringValue("vrf-1")),
    )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // @entry_restriction: ipv4_multicast_table
  // =========================================================================

  @Test
  fun `ipv4_multicast - multicast address is accepted`() {
    // ipv4_dst in 224.0.0.0 - 239.255.255.255 → constraint satisfied.
    val table = findTable("ipv4_multicast_table")
    val action = findAction("set_multicast_group_id")
    val entry = buildExactEntry(
      table, action,
      exactFields = mapOf(
        "vrf_id" to stringValue(""),
        "ipv4_dst" to bytesValue(byteArrayOf(224.toByte(), 0, 0, 1)),
      ),
      actionParams = mapOf(
        "multicast_group_id" to bytesValue(longToBytes(1, 2)),
      ),
    )
    harness.installEntry(entry)
  }

  @Test
  fun `ipv4_multicast - unicast address is rejected`() {
    // ipv4_dst = 10.0.0.1, not in multicast range → violates constraint.
    val table = findTable("ipv4_multicast_table")
    val action = findAction("set_multicast_group_id")
    val entry = buildExactEntry(
      table, action,
      exactFields = mapOf(
        "vrf_id" to stringValue(""),
        "ipv4_dst" to bytesValue(byteArrayOf(10, 0, 0, 1)),
      ),
      actionParams = mapOf(
        "multicast_group_id" to bytesValue(longToBytes(1, 2)),
      ),
    )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // @action_restriction: set_multicast_group_id
  // =========================================================================

  @Test
  fun `set_multicast_group_id - group_id 0 is rejected`() {
    // @action_restriction("multicast_group_id != 0").
    val table = findTable("ipv4_multicast_table")
    val action = findAction("set_multicast_group_id")
    val entry = buildExactEntry(
      table, action,
      exactFields = mapOf(
        "vrf_id" to stringValue(""),
        "ipv4_dst" to bytesValue(byteArrayOf(224.toByte(), 0, 0, 1)),
      ),
      actionParams = mapOf(
        "multicast_group_id" to bytesValue(longToBytes(0, 2)),
      ),
    )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  // =========================================================================
  // @action_restriction: src_mac != 0
  // =========================================================================

  @Test
  fun `router_interface - src_mac all zeros is rejected`() {
    // @action_restriction("src_mac != 0") on set_port_and_src_mac.
    val table = findTable("router_interface_table")
    val action = findAction("set_port_and_src_mac")
    val entry = buildExactEntry(
      table, action,
      exactFields = mapOf(
        "router_interface_id" to stringValue("rif-test"),
      ),
      actionParams = mapOf(
        "port" to stringValue("Ethernet0"),
        "src_mac" to bytesValue(ByteArray(MAC_LEN)),
      ),
    )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
  }

  @Test
  fun `router_interface - nonzero src_mac is accepted`() {
    val table = findTable("router_interface_table")
    val action = findAction("set_port_and_src_mac")
    val entry = buildExactEntry(
      table, action,
      exactFields = mapOf(
        "router_interface_id" to stringValue("rif-test"),
      ),
      actionParams = mapOf(
        "port" to stringValue("Ethernet0"),
        "src_mac" to bytesValue(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)),
      ),
    )
    harness.installEntry(entry)
  }

  // =========================================================================
  // @entry_restriction: disable_vlan_checks_table (dummy_match::mask == 0)
  // =========================================================================

  @Test
  fun `disable_vlan_checks - wildcard dummy_match is accepted`() {
    // dummy_match::mask == 0 → must be wildcard.
    val table = findTable("disable_vlan_checks_table")
    val action = findAction("disable_vlan_checks")
    // No match fields at all → all wildcarded → constraint satisfied.
    val entry = buildTernaryAclEntry(table, action, ternaryFields = emptyMap())
    harness.installEntry(entry)
  }

  @Test
  fun `disable_vlan_checks - exact dummy_match is rejected`() {
    // dummy_match::mask != 0 → violates constraint.
    val table = findTable("disable_vlan_checks_table")
    val action = findAction("disable_vlan_checks")
    val entry = buildTernaryAclEntry(
      table, action,
      ternaryFields = mapOf(
        "dummy_match" to TernaryValue(value = 1, mask = 1, byteLen = 1),
      ),
    )
    assertGrpcError(Status.Code.INVALID_ARGUMENT) { harness.installEntry(entry) }
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

  private fun matchFieldId(table: P4InfoOuterClass.Table, name: String): Int =
    table.matchFieldsList.find { it.name == name }?.id
      ?: error("match field '$name' not found in table '${table.preamble.alias}'")

  private fun paramId(action: P4InfoOuterClass.Action, name: String): Int =
    action.paramsList.find { it.name == name }?.id
      ?: error("param '$name' not found in action '${action.preamble.alias}'")

  /** Wraps a string as a UTF-8 ByteString for SDN string-typed fields. */
  private fun stringValue(s: String): ByteString = ByteString.copyFromUtf8(s)

  /** Wraps a byte array as a ByteString. */
  private fun bytesValue(b: ByteArray): ByteString = ByteString.copyFrom(b)

  data class TernaryValue(val value: Long, val mask: Long, val byteLen: Int)
  data class OptionalValue(val value: Long, val byteLen: Int)

  /** Builds a ternary ACL-style table entry with ternary and optional match fields. */
  @Suppress("LongParameterList")
  private fun buildTernaryAclEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    ternaryFields: Map<String, TernaryValue> = emptyMap(),
    optionalFields: Map<String, OptionalValue> = emptyMap(),
    actionParams: Map<String, ByteString> = emptyMap(),
    priority: Int = 10,
  ): Entity {
    val tableEntry = TableEntry.newBuilder()
      .setTableId(table.preamble.id)
      .setPriority(priority)

    for ((fieldName, tv) in ternaryFields) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId(table, fieldName))
          .setTernary(
            FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(tv.value, tv.byteLen)))
              .setMask(ByteString.copyFrom(longToBytes(tv.mask, tv.byteLen)))
          )
      )
    }

    for ((fieldName, ov) in optionalFields) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId(table, fieldName))
          .setOptional(
            FieldMatch.Optional.newBuilder()
              .setValue(ByteString.copyFrom(longToBytes(ov.value, ov.byteLen)))
          )
      )
    }

    val actionBuilder = p4.v1.P4RuntimeOuterClass.Action.newBuilder()
      .setActionId(action.preamble.id)
    for ((paramName, value) in actionParams) {
      actionBuilder.addParams(
        p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramId(action, paramName))
          .setValue(value)
      )
    }

    tableEntry.setAction(
      p4.v1.P4RuntimeOuterClass.TableAction.newBuilder().setAction(actionBuilder)
    )

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  /** Builds a table entry with exact match fields. */
  private fun buildExactEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    exactFields: Map<String, ByteString> = emptyMap(),
    actionParams: Map<String, ByteString> = emptyMap(),
  ): Entity {
    val tableEntry = TableEntry.newBuilder()
      .setTableId(table.preamble.id)

    for ((fieldName, value) in exactFields) {
      tableEntry.addMatch(
        FieldMatch.newBuilder()
          .setFieldId(matchFieldId(table, fieldName))
          .setExact(FieldMatch.Exact.newBuilder().setValue(value))
      )
    }

    val actionBuilder = p4.v1.P4RuntimeOuterClass.Action.newBuilder()
      .setActionId(action.preamble.id)
    for ((paramName, value) in actionParams) {
      actionBuilder.addParams(
        p4.v1.P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramId(action, paramName))
          .setValue(value)
      )
    }

    tableEntry.setAction(
      p4.v1.P4RuntimeOuterClass.TableAction.newBuilder().setAction(actionBuilder)
    )

    return Entity.newBuilder().setTableEntry(tableEntry).build()
  }

  @Suppress("MagicNumber")
  companion object {
    private const val MAC_LEN = 6
    private val VALIDATOR_BINARY: Path =
      Paths.get(System.getenv("JAVA_RUNFILES") ?: ".", "_main/p4runtime/constraint_validator")
  }
}
