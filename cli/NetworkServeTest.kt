package fourward.cli

import fourward.e2e.decodeHex
import fourward.e2e.runfilePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [startNetworkServers]. */
class NetworkServeTest {

  @Test
  fun `starts one server per switch`() {
    val nstf = NetworkStf.parse(runfilePath(PKG, "two_switch_inline.nstf"))
    val servers = startNetworkServers(nstf, EPHEMERAL_PORT)
    try {
      assertEquals(2, servers.size)
      assertTrue("server ports should be positive", servers.all { it.port() > 0 })
    } finally {
      servers.forEach { it.stop() }
    }
  }

  @Test
  fun `servers are pre-loaded with pipelines`() {
    val nstf = NetworkStf.parse(runfilePath(PKG, "two_switch_inline.nstf"))
    val servers = startNetworkServers(nstf, EPHEMERAL_PORT)
    try {
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

  @Test
  fun `port collision on second switch cleans up first`() {
    val nstf = NetworkStf.parse(runfilePath(PKG, "two_switch_inline.nstf"))
    // Start a server on a known port, then try to start both switches on that same port.
    val blocker = fourward.p4runtime.P4RuntimeServer(port = EPHEMERAL_PORT).start()
    val blockedPort = blocker.port()
    try {
      val e =
        assertThrows(NetworkServeException::class.java) { startNetworkServers(nstf, blockedPort) }
      assertTrue("should mention failed to start", e.message!!.contains("failed to start"))
    } finally {
      blocker.stop()
    }
  }

  @Test
  fun `cross-switch forwarding via broker`() {
    // two_switch_inline.nstf: s1 forwards to port 1 (link to s2), s2 forwards to port 2 (edge).
    val nstf = NetworkStf.parse(runfilePath(PKG, "two_switch_inline.nstf"))
    val servers = startNetworkServers(nstf, EPHEMERAL_PORT)
    try {
      // Inject a packet into s1's broker. It should traverse s1 → link → s2.
      // s2's subscriber should see the packet exit on port 2.
      val s2Outputs = java.util.concurrent.CopyOnWriteArrayList<Int>()
      servers[1].broker.subscribe { result ->
        for (outputs in result.possibleOutcomes) {
          for (output in outputs) {
            s2Outputs.add(output.dataplaneEgressPort)
          }
        }
      }

      servers[0].broker.processPacket(0, PAYLOAD.decodeHex())

      // s2 should have received the forwarded packet and produced an output on port 2.
      assertEquals(listOf(2), s2Outputs.toList())
    } finally {
      servers.forEach { it.stop() }
    }
  }

  @Test
  fun `routing loop does not hang`() {
    // Both switches forward to port 1 (linked) — routing loop.
    // Forwarding should stop at hop limit without hanging or crashing.
    val nstf = NetworkStf.parse(runfilePath(PKG, "loop_inline.nstf"))
    val servers = startNetworkServers(nstf, EPHEMERAL_PORT)
    try {
      val outputs = java.util.concurrent.CopyOnWriteArrayList<Int>()
      servers[0].broker.subscribe { result ->
        for (o in result.possibleOutcomes) for (p in o) outputs.add(p.dataplaneEgressPort)
      }

      servers[0].broker.processPacket(0, PAYLOAD.decodeHex())

      // The packet bounces back and forth until the hop limit. Both switches forward to port 1,
      // so all outputs are on port 1. The exact count depends on the hop limit but should be > 0
      // and finite.
      assertTrue("should produce some outputs before hop limit", outputs.isNotEmpty())
    } finally {
      servers.forEach { it.stop() }
    }
  }

  companion object {
    private const val PKG = "e2e_tests/network"
    private const val EPHEMERAL_PORT = 0
    private const val PAYLOAD = "FFFFFFFFFFFF 000000000001 0800 DEADBEEF"
  }
}
