package fourward.e2e.network

import com.google.protobuf.ByteString
import fourward.e2e.StfFile
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.e2e.runfilePath
import fourward.simulator.Endpoint
import fourward.simulator.Link
import fourward.simulator.NetworkSimulator
import fourward.simulator.NetworkTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walking-skeleton test for [NetworkSimulator]: two switches, one link, one shared P4 program.
 *
 * Topology:
 * ```
 *   [s1:0]  s1  [s1:1] ──── [s2:1]  s2  [s2:2]
 *   (edge)       (link)       (link)      (edge)
 * ```
 *
 * Both switches run basic_table.p4. s1 forwards etherType 0x0800 to port 1 (toward s2). s2
 * forwards etherType 0x0800 to port 2 (edge). A packet injected at s1:0 should exit at s2:2.
 */
class NetworkSimulatorTest {

  private val config by lazy {
    loadPipelineConfig(runfilePath(CONFIG_PKG, "basic_table.txtpb"))
  }

  private fun loadSwitch(network: NetworkSimulator, id: String, stfFile: String) {
    val sim = network.addSwitch(id)
    sim.loadPipeline(config)
    val stf = StfFile.parse(runfilePath(TEST_PKG, stfFile))
    installStfEntries(sim, stf, config.p4Info)
  }

  @Test
  fun `packet traverses two switches via link`() {
    val topology = NetworkTopology(links = listOf(Link(Endpoint("s1", 1), Endpoint("s2", 1))))
    val network = NetworkSimulator(topology)
    loadSwitch(network, "s1", "s1.stf")
    loadSwitch(network, "s2", "s2.stf")

    val payload = hexToBytes("FFFFFFFFFFFF 000000000001 0800 DEADBEEF")
    val root = network.processPacket("s1", 0, payload)

    // s1 forwards to port 1 (link) → no edge outputs, one next hop.
    assertEquals("s1", root.switchId)
    assertEquals(0, root.ingressPort)
    assertTrue("s1 should have no edge outputs", root.edgeOutputs.isEmpty())
    assertEquals("s1 should forward to one next hop", 1, root.nextHops.size)

    // s2 receives on port 1, forwards to port 2 (edge).
    val hop2 = root.nextHops[0]
    assertEquals("s2", hop2.switchId)
    assertEquals(1, hop2.ingressPort)
    assertTrue("s2 should have no further hops", hop2.nextHops.isEmpty())
    assertEquals("s2 should produce one edge output", 1, hop2.edgeOutputs.size)

    val output = hop2.edgeOutputs[0]
    assertEquals("s2", output.switchId)
    assertEquals(2, output.egressPort)
    assertEquals(ByteString.copyFrom(payload), output.payload)
  }

  @Test
  fun `dropped packet produces no output`() {
    val topology = NetworkTopology(links = listOf(Link(Endpoint("s1", 1), Endpoint("s2", 1))))
    val network = NetworkSimulator(topology)

    // s1 has no table entry for etherType 0x0806 → default action is drop.
    loadSwitch(network, "s1", "s1.stf")
    loadSwitch(network, "s2", "s2.stf")

    val payload = hexToBytes("FFFFFFFFFFFF 000000000001 0806 DEADBEEF")
    val root = network.processPacket("s1", 0, payload)

    assertTrue("dropped packet should have no edge outputs", root.edgeOutputs.isEmpty())
    assertTrue("dropped packet should have no next hops", root.nextHops.isEmpty())
  }

  @Test
  fun `edge port output does not cross link`() {
    val topology = NetworkTopology(links = listOf(Link(Endpoint("s1", 1), Endpoint("s2", 1))))
    val network = NetworkSimulator(topology)

    // s2 forwards to port 2 (edge) — use s2.stf for s1 so it forwards to an edge port (port 2).
    loadSwitch(network, "s1", "s2.stf")
    loadSwitch(network, "s2", "s2.stf")

    val payload = hexToBytes("FFFFFFFFFFFF 000000000001 0800 DEADBEEF")
    val root = network.processPacket("s1", 0, payload)

    // s1 forwards to port 2 (edge, not connected to link).
    assertEquals("packet exits on s1 edge port", 1, root.edgeOutputs.size)
    assertEquals(2, root.edgeOutputs[0].egressPort)
    assertTrue("no packet reaches s2", root.nextHops.isEmpty())
  }

  companion object {
    private const val CONFIG_PKG = "e2e_tests/basic_table"
    private const val TEST_PKG = "e2e_tests/network"
  }
}

private fun hexToBytes(hex: String): ByteArray {
  val clean = hex.replace(" ", "")
  return ByteArray(clean.length / 2) { i -> clean.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
