package fourward.e2e.passthrough

import fourward.e2e.TestResult
import fourward.e2e.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for the passthrough P4 program. This is the walking-skeleton test. */
class PassthroughTest {

  @Test
  fun `passthrough program forwards packet to port 1`() {
    val result = runStfTest("passthrough")
    if (result is TestResult.Failure) fail(result.message)
  }
}
