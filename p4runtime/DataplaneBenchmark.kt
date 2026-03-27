package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.PipelineConfig
import fourward.ir.TranslationEntry
import fourward.ir.TypeTranslation
import org.junit.After
import org.junit.Before
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Dataplane performance benchmark for SAI P4 middleblock.
 *
 * Measures per-packet latency through the gRPC InjectPacket RPC at configurable table scale. This
 * is Track 10 Phase 1: establishing a baseline so we know where time goes before optimizing.
 *
 * Run with: bazel test //p4runtime:DataplaneBenchmark --test_output=streamed
 */
@Suppress("FunctionNaming")
class DataplaneBenchmark {

  private lateinit var harness: P4RuntimeTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
    config =
      P4RuntimeTestHarness.loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
    config =
      withPortMappings(
        config,
        mapOf("0" to 0, "1" to 1, CPU_PORT.toString() to CPU_PORT),
      )
    harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // ===========================================================================
  // Benchmarks
  // ===========================================================================

  @Test
  fun `benchmark - 0 entries baseline`() = runBenchmark(routeCount = 0)

  @Test
  fun `benchmark - 100 routes`() = runBenchmark(routeCount = 100)

  @Test
  fun `benchmark - 1000 routes`() = runBenchmark(routeCount = 1_000)

  // 4000 is close to the nexthop_table size limit (4096 in SAI P4).
  @Test
  fun `benchmark - 4000 routes`() = runBenchmark(routeCount = 4_000)

  // 10k routes sharing 100 nexthops. Exercises LPM table lookup at scale
  // (ipv4_table has 131k capacity) without exceeding nexthop_table's 4096 limit.
  @Test
  fun `benchmark - 10000 routes (shared nexthops)`() =
    runBenchmark(routeCount = 10_000, nexthopCount = 100)

  // ===========================================================================
  // Benchmark driver
  // ===========================================================================

  @Suppress("MagicNumber")
  private fun runBenchmark(routeCount: Int, nexthopCount: Int = routeCount) {
    val actualNexthops = minOf(nexthopCount, routeCount)
    // --- Install table entries ---
    val installStart = System.nanoTime()
    installEntries(routeCount, actualNexthops)
    val installMs = (System.nanoTime() - installStart) / 1_000_000.0

    // --- Warmup: run a few packets to trigger JIT compilation ---
    val packet = buildIpv4Packet(dstIp = ipForRoute(0))
    repeat(WARMUP_PACKETS) {
      harness.injectPacket(ingressPort = 0, payload = packet)
    }

    // --- Benchmark: measure per-packet latency ---
    val latencies = LongArray(BENCHMARK_PACKETS)
    for (i in 0 until BENCHMARK_PACKETS) {
      // Vary destination IP to exercise different table entries.
      val pkt =
        buildIpv4Packet(dstIp = ipForRoute(i % maxOf(routeCount, 1)))
      val start = System.nanoTime()
      harness.injectPacket(ingressPort = 0, payload = pkt)
      latencies[i] = System.nanoTime() - start
    }

    latencies.sort()
    val p50 = latencies[latencies.size / 2]
    val p95 = latencies[(latencies.size * 0.95).toInt()]
    val p99 = latencies[(latencies.size * 0.99).toInt()]
    val mean = latencies.average()
    val throughput = 1_000_000_000.0 / mean

    val totalEntries =
      if (routeCount == 0) 0 else SHARED_ENTRIES + actualNexthops + routeCount
    println()
    print("=== Dataplane Benchmark: $routeCount routes ")
    println("($totalEntries entries), $BENCHMARK_PACKETS packets ===")
    println(
      "  Entry install:  %.1f ms (%.0f entries/sec)".format(
        installMs,
        if (installMs > 0) totalEntries / installMs * 1000 else 0.0,
      )
    )
    println("  Latency p50:    %.3f ms".format(p50 / NS_PER_MS))
    println("  Latency p95:    %.3f ms".format(p95 / NS_PER_MS))
    println("  Latency p99:    %.3f ms".format(p99 / NS_PER_MS))
    println("  Latency mean:   %.3f ms".format(mean / NS_PER_MS))
    println("  Throughput:     %.0f packets/sec".format(throughput))
    println()
  }

  // ===========================================================================
  // Table entry installation
  // ===========================================================================

  /**
   * Installs L3 routing chains: [nexthopCount] nexthops and [routeCount] IPv4 /32 routes.
   *
   * All routes share a single VRF + router_interface + neighbor. Each route maps to a nexthop via
   * round-robin (nhop-{i % nexthopCount}). Entries are batched to minimize gRPC round-trips during
   * setup.
   */
  @Suppress("MagicNumber")
  private fun installEntries(
    routeCount: Int,
    nexthopCount: Int = routeCount,
  ) {
    if (routeCount == 0) return

    // All routes share a single VRF + router_interface + neighbor.
    val vrfTable = findTable("vrf_table")
    val noAction = findAction("no_action")
    harness.installEntry(
      buildEntry(
        vrfTable,
        noAction,
        listOf(exactMatch(vrfTable, "vrf_id", "")),
      )
    )

    val rifTable = findTable("router_interface_table")
    val setPortAndSrcMac = findAction("set_port_and_src_mac")
    harness.installEntry(
      buildEntry(
        rifTable,
        setPortAndSrcMac,
        listOf(exactMatch(rifTable, "router_interface_id", "rif-0")),
        listOf(
          stringParam(setPortAndSrcMac, "port", "1"),
          bytesParam(setPortAndSrcMac, "src_mac", RIF_MAC),
        ),
      )
    )

    val neighborTable = findTable("neighbor_table")
    val setDstMac = findAction("set_dst_mac")
    harness.installEntry(
      buildEntry(
        neighborTable,
        setDstMac,
        listOf(
          exactMatch(neighborTable, "router_interface_id", "rif-0"),
          exactMatch(neighborTable, "neighbor_id", NEIGHBOR_ID),
        ),
        listOf(bytesParam(setDstMac, "dst_mac", NEIGHBOR_MAC)),
      )
    )

    val nexthopTable = findTable("nexthop_table")
    val setIpNexthop = findAction("set_ip_nexthop")
    val ipv4Table = findTable("ipv4_table")
    val setNexthopId = findAction("set_nexthop_id")

    // Batch nexthop entries.
    for (batch in (0 until nexthopCount).chunked(BATCH_SIZE)) {
      val entities =
        batch.map { i ->
          buildEntry(
            nexthopTable,
            setIpNexthop,
            listOf(exactMatch(nexthopTable, "nexthop_id", "nhop-$i")),
            listOf(
              stringParam(setIpNexthop, "router_interface_id", "rif-0"),
              bytesParam(setIpNexthop, "neighbor_id", NEIGHBOR_ID),
            ),
          )
        }
      harness.writeRaw(
        harness.buildBatchRequest(Update.Type.INSERT, entities)
      )
    }

    // Batch ipv4 route entries: each is a /32 LPM, round-robin across nexthops.
    for (batch in (0 until routeCount).chunked(BATCH_SIZE)) {
      val entities =
        batch.map { i ->
          buildEntry(
            ipv4Table,
            setNexthopId,
            listOf(
              exactMatch(ipv4Table, "vrf_id", ""),
              lpmMatch(ipv4Table, "ipv4_dst", ipForRoute(i), prefixLen = 32),
            ),
            listOf(
              stringParam(
                setNexthopId,
                "nexthop_id",
                "nhop-${i % nexthopCount}",
              )
            ),
          )
        }
      harness.writeRaw(
        harness.buildBatchRequest(Update.Type.INSERT, entities)
      )
    }
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private fun findTable(alias: String) =
    P4RuntimeTestHarness.findTable(config, alias)

  private fun findAction(alias: String) =
    P4RuntimeTestHarness.findAction(config, alias)

  private fun matchFieldId(
    table: P4InfoOuterClass.Table,
    name: String,
  ) = P4RuntimeTestHarness.matchFieldId(table, name)

  private fun paramId(
    action: P4InfoOuterClass.Action,
    name: String,
  ) = P4RuntimeTestHarness.paramId(action, name)

  private fun buildEntry(
    table: P4InfoOuterClass.Table,
    action: P4InfoOuterClass.Action,
    matches: List<FieldMatch>,
    params: List<Action.Param> = emptyList(),
    priority: Int = 0,
  ): Entity {
    val entry =
      TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addAllMatch(matches)
        .setAction(
          p4.v1.P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              Action.newBuilder()
                .setActionId(action.preamble.id)
                .addAllParams(params)
            )
        )
    if (priority > 0) entry.setPriority(priority)
    return Entity.newBuilder().setTableEntry(entry).build()
  }

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setExact(
        FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value))
      )
      .build()

  private fun exactMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: String,
  ): FieldMatch = exactMatch(table, fieldName, value.toByteArray(Charsets.UTF_8))

  private fun lpmMatch(
    table: P4InfoOuterClass.Table,
    fieldName: String,
    value: ByteArray,
    prefixLen: Int,
  ): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(matchFieldId(table, fieldName))
      .setLpm(
        FieldMatch.LPM.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setPrefixLen(prefixLen)
      )
      .build()

  private fun stringParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: String,
  ): Action.Param =
    Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFromUtf8(value))
      .build()

  private fun bytesParam(
    action: P4InfoOuterClass.Action,
    paramName: String,
    value: ByteArray,
  ): Action.Param =
    Action.Param.newBuilder()
      .setParamId(paramId(action, paramName))
      .setValue(ByteString.copyFrom(value))
      .build()

  @Suppress("MagicNumber")
  private fun buildIpv4Packet(dstIp: ByteArray): ByteArray {
    val packet = ByteArray(ETHERNET_HEADER_LEN + IPV4_HEADER_LEN)
    // Ethernet: dst=UNICAST_MAC src=SRC_MAC ethertype=0x0800
    System.arraycopy(UNICAST_MAC, 0, packet, 0, MAC_LEN)
    System.arraycopy(SRC_MAC, 0, packet, MAC_LEN, MAC_LEN)
    packet[12] = 0x08.toByte()
    packet[13] = 0x00.toByte()
    // IPv4: version=4, IHL=5, total_length=20, TTL=64, protocol=TCP
    packet[14] = 0x45.toByte()
    packet[16] = 0x00.toByte()
    packet[17] = IPV4_HEADER_LEN.toByte()
    packet[22] = 64.toByte()
    packet[23] = 0x06.toByte()
    System.arraycopy(SRC_IP, 0, packet, SRC_IP_OFFSET, 4)
    System.arraycopy(dstIp, 0, packet, DST_IP_OFFSET, 4)
    return packet
  }

  companion object {
    private const val CPU_PORT = 510
    private const val WARMUP_PACKETS = 100
    private const val BENCHMARK_PACKETS = 1_000
    private const val BATCH_SIZE = 500
    private const val SHARED_ENTRIES = 3 // vrf + router_interface + neighbor
    private const val NS_PER_MS = 1_000_000.0

    private const val MAC_LEN = 6
    private const val ETHERNET_HEADER_LEN = 14
    private const val IPV4_HEADER_LEN = 20
    private const val SRC_IP_OFFSET = 26
    private const val DST_IP_OFFSET = 30

    private val NEIGHBOR_ID = ByteArray(16) { if (it == 15) 1 else 0 }
    private val NEIGHBOR_MAC =
      byteArrayOf(
        0x00, 0xAA.toByte(), 0xBB.toByte(),
        0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(),
      )
    private val RIF_MAC = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val UNICAST_MAC =
      byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
    private val SRC_MAC =
      byteArrayOf(0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E)
    private val SRC_IP =
      byteArrayOf(192.toByte(), 168.toByte(), 1, 1)

    /** Maps route index i to a /32 destination IP: 10.{b1}.{b2}.{b3}. */
    @Suppress("MagicNumber")
    private fun ipForRoute(i: Int): ByteArray =
      byteArrayOf(
        10,
        (i shr 16 and 0xFF).toByte(),
        (i shr 8 and 0xFF).toByte(),
        (i and 0xFF).toByte(),
      )

    private fun withPortMappings(
      config: PipelineConfig,
      ports: Map<String, Int>,
    ): PipelineConfig {
      val portTranslation =
        TypeTranslation.newBuilder()
          .setTypeName("port_id_t")
          .setAutoAllocate(true)
      for ((name, dpValue) in ports) {
        portTranslation.addEntries(
          TranslationEntry.newBuilder()
            .setSdnStr(name)
            .setDataplaneValue(encodeMinWidth(dpValue))
        )
      }
      return config
        .toBuilder()
        .setDevice(
          config.device.toBuilder().addTranslations(portTranslation)
        )
        .build()
    }
  }
}
