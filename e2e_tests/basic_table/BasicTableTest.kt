package fourward.e2e.basictable

import fourward.e2e.TestResult
import fourward.e2e.runStfTest
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for basic_table.p4: exact-match table with forward and drop actions. */
class BasicTableTest {

  @Test
  fun `exact match table forwards matching packet and drops non-matching`() {
    val result = runStfTest("basic_table")
    if (result is TestResult.Failure) fail(result.message)
  }
}
