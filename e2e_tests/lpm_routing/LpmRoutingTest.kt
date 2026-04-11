package fourward.e2e.lpmrouting

import fourward.stf.TestResult
import fourward.stf.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for lpm_routing.p4: LPM table with longest-prefix-wins forwarding. */
class LpmRoutingTest {

  @Test
  fun `LPM table selects longest matching prefix`() {
    val result = runStfTest("lpm_routing")
    if (result is TestResult.Failure) fail(result.message)
  }
}
