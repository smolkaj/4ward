package fourward.e2e.switchactionrun

import fourward.e2e.StfRunner
import fourward.e2e.TestResult
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test

/** End-to-end test for switch_action_run.p4: post-table switch on action_run. */
class SwitchActionRunTest {

  @Test
  fun `switch on action_run executes the matching case block`() {
    val runfiles = System.getenv("JAVA_RUNFILES") ?: "."

    val simulatorBinary = Paths.get(runfiles, "_main/simulator/simulator")
    val pipelineConfig =
      Paths.get(runfiles, "_main/e2e_tests/switch_action_run/switch_action_run.txtpb")
    val stfFile = Paths.get(runfiles, "_main/e2e_tests/switch_action_run/switch_action_run.stf")

    val result = StfRunner(simulatorBinary, pipelineConfig).run(stfFile)
    if (result is TestResult.Failure) {
      fail(result.message)
    }
  }
}
