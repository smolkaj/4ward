package fourward.e2e.passthrough

import fourward.e2e.StfRunner
import fourward.e2e.TestResult
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Paths

/** End-to-end test for the passthrough P4 program. This is the walking-skeleton test. */
class PassthroughTest {

    @Test
    fun `passthrough program forwards packet to port 1`() {
        val runfiles = System.getenv("JAVA_RUNFILES") ?: "."

        // Under Bzlmod the main module's canonical repo name is always "_main".
        val simulatorBinary = Paths.get(runfiles, "_main/simulator/simulator")
        val pipelineConfig  = Paths.get(runfiles, "_main/e2e_tests/passthrough/passthrough.txtpb")
        val stfFile         = Paths.get(runfiles, "_main/e2e_tests/passthrough/passthrough.stf")

        val result = StfRunner(simulatorBinary, pipelineConfig).run(stfFile)
        if (result is TestResult.Failure) {
            fail(result.message)
        }
    }
}
