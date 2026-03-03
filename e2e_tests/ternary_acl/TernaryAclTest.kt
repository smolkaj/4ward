package fourward.e2e.ternaryacl

import fourward.e2e.StfRunner
import fourward.e2e.TestResult
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for ternary_acl.p4: ternary match with priority-based entry selection. */
class TernaryAclTest {

  @Test
  fun `ternary table selects highest-priority matching entry`() {
    val runfiles = System.getenv("JAVA_RUNFILES") ?: "."

    val simulatorBinary = Paths.get(runfiles, "_main/simulator/simulator")
    val pipelineConfig = Paths.get(runfiles, "_main/e2e_tests/ternary_acl/ternary_acl.txtpb")
    val stfFile = Paths.get(runfiles, "_main/e2e_tests/ternary_acl/ternary_acl.stf")

    val result = StfRunner(simulatorBinary, pipelineConfig).run(stfFile)
    if (result is TestResult.Failure) {
      fail(result.message)
    }
  }
}
