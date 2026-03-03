package fourward.e2e.lpmrouting

import fourward.e2e.StfRunner
import fourward.e2e.TestResult
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for lpm_routing.p4: LPM table with longest-prefix-wins forwarding. */
class LpmRoutingTest {

  @Test
  fun `LPM table selects longest matching prefix`() {
    val runfiles = System.getenv("JAVA_RUNFILES") ?: "."

    val simulatorBinary = Paths.get(runfiles, "_main/simulator/simulator")
    val pipelineConfig = Paths.get(runfiles, "_main/e2e_tests/lpm_routing/lpm_routing.txtpb")
    val stfFile = Paths.get(runfiles, "_main/e2e_tests/lpm_routing/lpm_routing.stf")

    val result = StfRunner(simulatorBinary, pipelineConfig).run(stfFile)
    if (result is TestResult.Failure) {
      fail(result.message)
    }
  }
}
