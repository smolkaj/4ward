package fourward.e2e.multitable

import fourward.stf.TestResult
import fourward.stf.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for multi_table.p4: ACL and forwarding tables applied sequentially. */
class MultiTableTest {

  @Test
  fun `ACL and forwarding tables run in sequence`() {
    val result = runStfTest("multi_table")
    if (result is TestResult.Failure) fail(result.message)
  }
}
