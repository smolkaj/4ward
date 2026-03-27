package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.PipelineConfig
import fourward.ir.TranslationEntry
import fourward.ir.TypeTranslation
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
 * All scale points run in a single test to avoid JVM warmup artifacts across JUnit methods. A
 * dedicated warmup phase runs ~500 packets before any measurement begins, ensuring JIT compilation
 * is complete.
 *
 * Run with: bazel test //p4runtime:DataplaneBenchmark --test_output=streamed
 */
@Suppress("FunctionNaming", "MagicNumber")
class DataplaneBenchmark {

  @Test
  fun `dataplane benchmark`() {
    // --- JIT warmup: run many packets on a fresh pipeline before measuring anything ---
    val warmupHarness = createHarness()
    installEntries(warmupHarness, routeCount = 100, nexthopCount = 100)
    val warmupPacket = buildIpv4Packet(dstIp = ipForRoute(0))
    repeat(JIT_WARMUP_PACKETS) {
      warmupHarness.injectPacket(ingressPort = 0, payload = warmupPacket)
    }
    warmupHarness.close()

    // --- Run each scale point with a fresh pipeline ---
    val scalePoints =
      listOf(
        ScalePoint(routes = 0, nexthops = 0),
        ScalePoint(routes = 100, nexthops = 100),
        ScalePoint(routes = 1_000, nexthops = 1_000),
        ScalePoint(routes = 4_000, nexthops = 4_000),
        // 10k routes sharing 100 nexthops: exercises LPM at scale without
        // exceeding nexthop_table's 4096 size limit.
        ScalePoint(routes = 10_000, nexthops = 100),
      )

    println()
    println(HEADER)
    println(SEPARATOR)

    for (sp in scalePoints) {
      val result = measureScalePoint(sp)
      println(
        "| %6d | %6d | %8.3f | %8.3f | %8.3f | %8.3f | %10.0f |"
          .format(
            sp.routes,
            result.totalEntries,
            result.p50Ms,
            result.p95Ms,
            result.p99Ms,
            result.meanMs,
            result.throughput,
          )
      )
    }

    println(SEPARATOR)
    println()
  }

  // ===========================================================================
  // Benchmark core
  // ===========================================================================

  private data class ScalePoint(val routes: Int, val nexthops: Int)

  private data class BenchmarkResult(
    val totalEntries: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val meanMs: Double,
    val throughput: Double,
  )

  private fun measureScalePoint(sp: ScalePoint): BenchmarkResult {
    val harness = createHarness()
    try {
      val actualNexthops = minOf(sp.nexthops, sp.routes)
      installEntries(harness, sp.routes, actualNexthops)

      // Per-scale-point warmup: stabilize after entry installation.
      val warmupPkt = buildIpv4Packet(dstIp = ipForRoute(0))
      repeat(SCALE_WARMUP_PACKETS) { harness.injectPacket(ingressPort = 0, payload = warmupPkt) }

      // Measure.
      val latencies = LongArray(BENCHMARK_PACKETS)
      for (i in 0 until BENCHMARK_PACKETS) {
        val pkt = buildIpv4Packet(dstIp = ipForRoute(i % maxOf(sp.routes, 1)))
        val start = System.nanoTime()
        harness.injectPacket(ingressPort = 0, payload = pkt)
        latencies[i] = System.nanoTime() - start
      }

      latencies.sort()
      val totalEntries = if (sp.routes == 0) 0 else SHARED_ENTRIES + actualNexthops + sp.routes
      return BenchmarkResult(
        totalEntries = totalEntries,
        p50Ms = latencies[latencies.size / 2] / NS_PER_MS,
        p95Ms = latencies[(latencies.size * 0.95).toInt()] / NS_PER_MS,
        p99Ms = latencies[(latencies.size * 0.99).toInt()] / NS_PER_MS,
        meanMs = latencies.average() / NS_PER_MS,
        throughput = NS_PER_MS * 1000 / latencies.average(),
      )
    } finally {
      harness.close()
    }
  }

  // ===========================================================================
  // Pipeline setup
  // ===========================================================================

  private fun createHarness(): P4RuntimeTestHarness {
    val harness = P4RuntimeTestHarness()
    var config = P4RuntimeTestHarness.loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
    config = withPortMappings(config, mapOf("0" to 0, "1" to 1, CPU_PORT.toString() to CPU_PORT))
    harness.loadPipeline(config)
    this.config = config
    return harness
  }

  /** Cached pipeline config — set by [createHarness]. */
  private lateinit var config: PipelineConfig

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
  private fun installEntries(
    harness: P4RuntimeTestHarness,
    routeCount: Int,
    nexthopCount: Int = routeCount,
  ) {
    if (routeCount == 0) return

    val vrfTable = findTable("vrf_table")
    val noAction = findAction("no_action")
    harness.installEntry(buildEntry(vrfTable, noAction, listOf(exactMatch(vrfTable, "vrf_id", ""))))

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
      harness.writeRaw(harness.buildBatchRequest(Update.Type.INSERT, entities))
    }

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
            listOf(stringParam(setNexthopId, "nexthop_id", "nhop-${i % nexthopCount}")),
          )
        }
      harness.writeRaw(harness.buildBatchRequest(Update.Type.INSERT, entities))
    }
  }

  // ===========================================================================
  // P4Runtime helpers
  // ===========================================================================

  private fun findTable(alias: String) = P4RuntimeTestHarness.findTable(config, alias)

  private fun findAction(alias: String) = P4RuntimeTestHarness.findAction(config, alias)

  private fun matchFieldId(table: P4InfoOuterClass.Table, name: String) =
    P4RuntimeTestHarness.matchFieldId(table, name)

  private fun paramId(action: P4InfoOuterClass.Action, name: String) =
    P4RuntimeTestHarness.paramId(action, name)

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
            .setAction(Action.newBuilder().setActionId(action.preamble.id).addAllParams(params))
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
      .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value)))
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
        FieldMatch.LPM.newBuilder().setValue(ByteString.copyFrom(value)).setPrefixLen(prefixLen)
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

  // ===========================================================================
  // Packet construction
  // ===========================================================================

  private fun buildIpv4Packet(dstIp: ByteArray): ByteArray {
    val packet = ByteArray(ETHERNET_HEADER_LEN + IPV4_HEADER_LEN)
    System.arraycopy(UNICAST_MAC, 0, packet, 0, MAC_LEN)
    System.arraycopy(SRC_MAC, 0, packet, MAC_LEN, MAC_LEN)
    packet[12] = 0x08.toByte()
    packet[13] = 0x00.toByte()
    packet[14] = 0x45.toByte() // version=4, IHL=5
    packet[16] = 0x00.toByte()
    packet[17] = IPV4_HEADER_LEN.toByte()
    packet[22] = 64.toByte() // TTL
    packet[23] = 0x06.toByte() // TCP
    System.arraycopy(SRC_IP, 0, packet, SRC_IP_OFFSET, 4)
    System.arraycopy(dstIp, 0, packet, DST_IP_OFFSET, 4)
    return packet
  }

  companion object {
    private const val CPU_PORT = 510
    private const val JIT_WARMUP_PACKETS = 500
    private const val SCALE_WARMUP_PACKETS = 100
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
      byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
    private val RIF_MAC = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val UNICAST_MAC = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
    private val SRC_MAC = byteArrayOf(0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E)
    private val SRC_IP = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)

    private const val HEADER =
      "| Routes | Entries |  p50 ms  |  p95 ms  |  p99 ms  | mean ms  | packets/s  |"
    private const val SEPARATOR =
      "|--------|---------|----------|----------|----------|----------|------------|"

    /** Maps route index i to a /32 destination: 10.{b1}.{b2}.{b3}. */
    private fun ipForRoute(i: Int): ByteArray =
      byteArrayOf(
        10,
        (i shr 16 and 0xFF).toByte(),
        (i shr 8 and 0xFF).toByte(),
        (i and 0xFF).toByte(),
      )

    private fun withPortMappings(config: PipelineConfig, ports: Map<String, Int>): PipelineConfig {
      val portTranslation =
        TypeTranslation.newBuilder().setTypeName("port_id_t").setAutoAllocate(true)
      for ((name, dpValue) in ports) {
        portTranslation.addEntries(
          TranslationEntry.newBuilder().setSdnStr(name).setDataplaneValue(encodeMinWidth(dpValue))
        )
      }
      return config
        .toBuilder()
        .setDevice(config.device.toBuilder().addTranslations(portTranslation))
        .build()
    }
  }
}
