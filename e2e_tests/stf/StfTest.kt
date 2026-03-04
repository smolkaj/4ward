package fourward.e2e

import org.junit.Assert.fail
import org.junit.Test

/**
 * Generic test class for STF-based end-to-end tests.
 *
 * Discovers the pipeline config and STF file paths from Bazel's `TEST_TARGET` environment variable.
 * Used by per-test macros like `p4_testgen_test`.
 */
class StfTest {

  @Test
  fun `stf test`() {
    val result = runStfTestFromEnv()
    if (result is TestResult.Failure) fail(result.message)
  }
}
