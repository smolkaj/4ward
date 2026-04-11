package fourward.e2e.ternaryacl

import fourward.stf.TestResult
import fourward.stf.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for ternary_acl.p4: ternary match with priority-based entry selection. */
class TernaryAclTest {

  @Test
  fun `ternary table selects highest-priority matching entry`() {
    val result = runStfTest("ternary_acl")
    if (result is TestResult.Failure) fail(result.message)
  }
}
