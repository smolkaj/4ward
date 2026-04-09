package fourward.cli

import fourward.e2e.decodeHex
import fourward.e2e.runfilePath
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [startNetworkServers]. */
class NetworkServeTest {

  @Test
  fun `starts one server per switch`() {
    val nstf = NetworkStf.parse(runfilePath(PKG, "two_switch_inline.nstf"))
    val servers = startNetworkServers(nstf, BASE_PORT)
    try {
      assertEquals(2, servers.size)
      assertEquals(BASE_PORT, servers[0].port())
      assertEquals(BASE_PORT + 1, servers[1].port())
    } finally {
      servers.forEach { it.stop() }
    }
  }

  @Test
  fun `servers are pre-loaded with pipelines`() {
    val nstf = NetworkStf.parse(runfilePath(PKG, "two_switch_inline.nstf"))
    val servers = startNetworkServers(nstf, BASE_PORT + 10)
    try {
      // Verify the simulators have loaded pipelines by processing a packet.
      // s1 forwards etherType 0x0800 to port 1, s2 forwards to port 2.
      val s1Result = servers[0].simulator.processPacket(0, PAYLOAD.decodeHex())
      val s1Outputs = s1Result.possibleOutcomes.single()
      assertEquals(1, s1Outputs.size)
      assertEquals(1, s1Outputs[0].dataplaneEgressPort)

      val s2Result = servers[1].simulator.processPacket(1, PAYLOAD.decodeHex())
      val s2Outputs = s2Result.possibleOutcomes.single()
      assertEquals(1, s2Outputs.size)
      assertEquals(2, s2Outputs[0].dataplaneEgressPort)
    } finally {
      servers.forEach { it.stop() }
    }
  }

  companion object {
    private const val PKG = "e2e_tests/network"
    private const val BASE_PORT = 19559
    private const val PAYLOAD = "FFFFFFFFFFFF 000000000001 0800 DEADBEEF"
  }
}
