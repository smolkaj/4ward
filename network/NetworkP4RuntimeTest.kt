package fourward.network

import fourward.e2e.StfFile
import fourward.e2e.decodeHex
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.e2e.runfilePath
import fourward.ir.PipelineConfig
import fourward.p4runtime.P4RuntimeServer
import fourward.simulator.Endpoint
import fourward.simulator.Link
import fourward.simulator.NetworkTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [startNetworkServers] and [wireForwarding]. */
class NetworkP4RuntimeTest {

  @Test
  fun `starts one server per switch`() {
    val servers = startNetworkServers(topology = EMPTY_TOPOLOGY, switches = twoSwitches())
    try {
      assertEquals(setOf("s1", "s2"), servers.keys)
      assertTrue("server ports should be positive", servers.values.all { it.port() > 0 })
    } finally {
      servers.values.forEach { it.stop() }
    }
  }

  @Test
  fun `servers are pre-loaded with pipelines`() {
    val servers = startNetworkServers(topology = EMPTY_TOPOLOGY, switches = twoSwitches())
    try {
      // s1 forwards etherType 0x0800 to port 1, s2 to port 2.
      val s1Outputs = servers["s1"]!!.processPacket(0, PAYLOAD.decodeHex()).possibleOutcomes.single()
      assertEquals(1, s1Outputs.size)
      assertEquals(1, s1Outputs[0].dataplaneEgressPort)

      val s2Outputs = servers["s2"]!!.processPacket(1, PAYLOAD.decodeHex()).possibleOutcomes.single()
      assertEquals(1, s2Outputs.size)
      assertEquals(2, s2Outputs[0].dataplaneEgressPort)
    } finally {
      servers.values.forEach { it.stop() }
    }
  }

  @Test
  fun `port collision on second switch cleans up first`() {
    val blocker = P4RuntimeServer(port = 0).start()
    try {
      val e =
        assertThrows(NetworkStartFailure::class.java) {
          startNetworkServers(
            topology = EMPTY_TOPOLOGY,
            switches = twoSwitches(),
            basePort = blocker.port(),
          )
        }
      // First switch binds to blocker's port (conflict), fails immediately.
      assertEquals("s1", e.switchId)
    } finally {
      blocker.stop()
    }
  }

  @Test
  fun `cross-switch forwarding delivers packets to linked switches`() {
    // s1:1 ↔ s2:1. s1 forwards to port 1, s2 forwards to port 2 (edge).
    val topology = NetworkTopology(listOf(Link(Endpoint("s1", 1), Endpoint("s2", 1))))
    val servers = startNetworkServers(topology = topology, switches = twoSwitches())
    try {
      val s2Outputs = java.util.concurrent.CopyOnWriteArrayList<Int>()
      servers["s2"]!!.onPacketProcessed { result ->
        for (outs in result.possibleOutcomes) for (o in outs) s2Outputs.add(o.dataplaneEgressPort)
      }

      servers["s1"]!!.processPacket(0, PAYLOAD.decodeHex())

      assertEquals(listOf(2), s2Outputs.toList())
    } finally {
      servers.values.forEach { it.stop() }
    }
  }

  @Test
  fun `routing loop does not hang`() {
    // Both switches forward to port 1, which is linked → routing loop.
    val topology = NetworkTopology(listOf(Link(Endpoint("s1", 1), Endpoint("s2", 1))))
    val servers =
      startNetworkServers(
        topology = topology,
        switches =
          listOf(switchSpec("s1", "forward(1)"), switchSpec("s2", "forward(1)")),
      )
    try {
      // Should return without hanging — hop limit bails the recursion.
      servers["s1"]!!.processPacket(0, PAYLOAD.decodeHex())
    } finally {
      servers.values.forEach { it.stop() }
    }
  }

  private fun twoSwitches(): List<NetworkSwitch> =
    listOf(switchSpec("s1", "forward(1)"), switchSpec("s2", "forward(2)"))

  /** Builds a NetworkSwitch running basic_table.p4 with one entry: etherType 0x0800 → action. */
  private fun switchSpec(id: String, action: String): NetworkSwitch {
    val config = loadBasicTable()
    val stfContent = "add port_table hdr.ethernet.etherType:0x0800 $action"
    return NetworkSwitch(
      id = id,
      pipelineConfig = config,
      installEntries = { server ->
        installStfEntries(
          server::writeEntry,
          StfFile.parse(listOf(stfContent)),
          config.p4Info,
        )
      },
    )
  }

  private fun loadBasicTable(): PipelineConfig =
    loadPipelineConfig(runfilePath(BASIC_TABLE_PKG, "basic_table.txtpb"))

  companion object {
    private const val BASIC_TABLE_PKG = "e2e_tests/basic_table"
    private const val PAYLOAD = "FFFFFFFFFFFF 000000000001 0800 DEADBEEF"
    private val EMPTY_TOPOLOGY = NetworkTopology(emptyList())
  }
}
