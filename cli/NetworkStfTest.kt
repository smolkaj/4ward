package fourward.cli

import fourward.simulator.Endpoint
import fourward.simulator.Link
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [NetworkStf] parser. */
class NetworkStfTest {

  @Test
  fun `parses minimal nstf file`() {
    val nstf = parseNstf(
      """
      switch s1 pipeline.txtpb entries.stf
      link s1:1 s1:2
      packet s1:0 DEADBEEF
      expect s1:3 CAFEBABE
      """
    )
    assertEquals(1, nstf.switches.size)
    assertEquals("s1", nstf.switches[0].id)
    assertEquals(1, nstf.links.size)
    assertEquals(Link(Endpoint("s1", 1), Endpoint("s1", 2)), nstf.links[0])
    assertEquals(1, nstf.packets.size)
    assertEquals(Endpoint("s1", 0), nstf.packets[0].endpoint)
    assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), nstf.packets[0].payload)
    assertEquals(1, nstf.expects.size)
    assertEquals(Endpoint("s1", 3), nstf.expects[0].endpoint)
  }

  @Test
  fun `skips comments and blank lines`() {
    val nstf = parseNstf(
      """
      # This is a comment.

      switch s1 pipeline.txtpb entries.stf

      # Another comment.
      """
    )
    assertEquals(1, nstf.switches.size)
  }

  @Test
  fun `rejects unknown directive`() {
    val e = assertThrows(IllegalStateException::class.java) {
      parseNstf("switch s1 pipeline.txtpb entries.stf\nbogus directive")
    }
    assertEquals("line 2: unknown directive 'bogus'", e.message)
  }

  @Test
  fun `rejects malformed endpoint`() {
    val e = assertThrows(IllegalArgumentException::class.java) {
      parseNstf("switch s1 pipeline.txtpb entries.stf\nlink s1 s2")
    }
    assertEquals("line 2: expected <switch:port>, got 's1'", e.message)
  }

  @Test
  fun `rejects non-numeric port`() {
    val e = assertThrows(IllegalArgumentException::class.java) {
      parseNstf("switch s1 pipeline.txtpb entries.stf\nlink s1:abc s2:1")
    }
    assertEquals("line 2: invalid port number 'abc'", e.message)
  }

  @Test
  fun `rejects empty file`() {
    val e = assertThrows(IllegalArgumentException::class.java) { parseNstf("") }
    assertEquals("no switches declared", e.message)
  }

  @Test
  fun `rejects undeclared switch in link`() {
    val e = assertThrows(IllegalArgumentException::class.java) {
      parseNstf("switch s1 pipeline.txtpb entries.stf\nlink s1:1 s2:1")
    }
    assertEquals("link references undeclared switch 's2'", e.message)
  }

  @Test
  fun `rejects undeclared switch in packet`() {
    val e = assertThrows(IllegalArgumentException::class.java) {
      parseNstf("switch s1 pipeline.txtpb entries.stf\npacket s2:0 DEAD")
    }
    assertEquals("packet references undeclared switch 's2'", e.message)
  }

  @Test
  fun `rejects undeclared switch in expect`() {
    val e = assertThrows(IllegalArgumentException::class.java) {
      parseNstf("switch s1 pipeline.txtpb entries.stf\nexpect s2:0 DEAD")
    }
    assertEquals("expect references undeclared switch 's2'", e.message)
  }

  @Test
  fun `line numbers are correct with comments and blanks`() {
    val e = assertThrows(IllegalStateException::class.java) {
      parseNstf("# comment\n\nswitch s1 pipeline.txtpb entries.stf\n\nbogus")
    }
    assertEquals("line 5: unknown directive 'bogus'", e.message)
  }

  /** Writes content to a temp file and parses it. */
  private fun parseNstf(content: String): NetworkStf {
    val file = Files.createTempFile("test-", ".nstf")
    file.toFile().deleteOnExit()
    file.toFile().writeText(content.trimIndent())
    return NetworkStf.parse(file)
  }
}
