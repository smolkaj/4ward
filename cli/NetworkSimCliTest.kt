package fourward.cli

import fourward.bazel.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Test

/** End-to-end test for the `4ward network` CLI command. */
class NetworkSimCliTest {

  @Test
  fun `two-switch network passes`() {
    val nstfPath = repoRoot.resolve("$PKG/two_switch.nstf")
    val exitCode = networkSim(nstfPath, OutputFormat.HUMAN)
    assertEquals("expected PASS", ExitCode.SUCCESS, exitCode)
  }

  @Test
  fun `inline table entries work`() {
    val nstfPath = repoRoot.resolve("$PKG/two_switch_inline.nstf")
    val exitCode = networkSim(nstfPath, OutputFormat.HUMAN)
    assertEquals("expected PASS", ExitCode.SUCCESS, exitCode)
  }

  @Test
  fun `mixed external and inline entries work`() {
    val nstfPath = repoRoot.resolve("$PKG/mixed_entries.nstf")
    val exitCode = networkSim(nstfPath, OutputFormat.HUMAN)
    assertEquals("expected PASS", ExitCode.SUCCESS, exitCode)
  }

  @Test
  fun `inline multicast PRE directives work`() {
    val nstfPath = repoRoot.resolve("$PKG/inline_multicast.nstf")
    val exitCode = networkSim(nstfPath, OutputFormat.HUMAN)
    assertEquals("expected PASS", ExitCode.SUCCESS, exitCode)
  }

  @Test
  fun `wrong expectation fails`() {
    val nstfPath = repoRoot.resolve("$PKG/two_switch_fail.nstf")
    val exitCode = networkSim(nstfPath, OutputFormat.HUMAN)
    assertEquals("expected FAIL", ExitCode.TEST_FAILURE, exitCode)
  }

  companion object {
    private const val PKG = "e2e_tests/network"
  }
}
