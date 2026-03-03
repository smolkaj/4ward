package fourward.e2e.switchactionrun

import fourward.e2e.TestResult
import fourward.e2e.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for switch_action_run.p4: post-table switch on action_run. */
class SwitchActionRunTest {

  @Test
  fun `switch on action_run executes the matching case block`() {
    val result = runStfTest("switch_action_run")
    if (result is TestResult.Failure) fail(result.message)
  }
}
