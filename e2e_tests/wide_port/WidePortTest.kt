package fourward.e2e.wideport

import fourward.stf.TestResult
import fourward.stf.runStfTest
import org.junit.Assert.fail
import org.junit.Test

class WidePortTest {
  @Test
  fun `16-bit ports forward to 1000 and 511, drop on 65535 and mark_to_drop`() {
    val result = runStfTest("wide_port")
    if (result is TestResult.Failure) fail(result.message)
  }
}
