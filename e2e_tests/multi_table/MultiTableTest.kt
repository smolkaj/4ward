package fourward.e2e.multitable

import fourward.e2e.StfRunner
import fourward.e2e.TestResult
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for multi_table.p4: ACL and forwarding tables applied sequentially. */
class MultiTableTest {

  @Test
  fun `ACL and forwarding tables run in sequence`() {
    val runfiles = System.getenv("JAVA_RUNFILES") ?: "."

    val simulatorBinary = Paths.get(runfiles, "_main/simulator/simulator")
    val pipelineConfig = Paths.get(runfiles, "_main/e2e_tests/multi_table/multi_table.txtpb")
    val stfFile = Paths.get(runfiles, "_main/e2e_tests/multi_table/multi_table.stf")

    val result = StfRunner(simulatorBinary, pipelineConfig).run(stfFile)
    if (result is TestResult.Failure) {
      fail(result.message)
    }
  }
}
