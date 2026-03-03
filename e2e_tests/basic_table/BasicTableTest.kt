package fourward.e2e.basictable

import fourward.e2e.StfRunner
import fourward.e2e.TestResult
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for basic_table.p4: exact-match table with forward and drop actions. */
class BasicTableTest {

  @Test
  fun `exact match table forwards matching packet and drops non-matching`() {
    val runfiles = System.getenv("JAVA_RUNFILES") ?: "."

    val simulatorBinary = Paths.get(runfiles, "_main/simulator/simulator")
    val pipelineConfig = Paths.get(runfiles, "_main/e2e_tests/basic_table/basic_table.txtpb")
    val stfFile = Paths.get(runfiles, "_main/e2e_tests/basic_table/basic_table.stf")

    val result = StfRunner(simulatorBinary, pipelineConfig).run(stfFile)
    if (result is TestResult.Failure) {
      fail(result.message)
    }
  }
}
