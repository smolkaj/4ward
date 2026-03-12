package fourward.e2e.assertlogmsg

import fourward.e2e.TestResult
import fourward.e2e.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for assert(), assume(), and log_msg() extern support. */
class AssertLogMsgTest {

  @Test
  fun `assert and log_msg pass through correctly`() {
    val result = runStfTest("assert_log_msg")
    if (result is TestResult.Failure) fail(result.message)
  }
}
