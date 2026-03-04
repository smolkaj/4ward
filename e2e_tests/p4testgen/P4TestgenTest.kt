package fourward.e2e.p4testgen

import fourward.e2e.TestResult
import fourward.e2e.runStf
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parameterized test that runs all p4testgen-generated STF tests for a single P4 program.
 *
 * Each macro-generated test target gets its own tree artifact directory containing one or more .stf
 * files (one per execution path explored by p4testgen). This class discovers them at runtime so the
 * Bazel target count stays fixed regardless of how many paths p4testgen finds.
 */
@RunWith(Parameterized::class)
class P4TestgenTest(private val testName: String) {

  companion object {
    private val runfiles = System.getenv("JAVA_RUNFILES") ?: "."
    private val baseName: String by lazy {
      val target = System.getenv("TEST_TARGET") ?: error("TEST_TARGET not set")
      target.substringAfterLast(":").removeSuffix("_test")
    }
    private val pkg = "_main/e2e_tests/p4testgen"

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testCases(): List<Array<String>> {
      val stfDir = Paths.get(runfiles, "$pkg/${baseName}_stfs")
      return Files.list(stfDir).use { stream ->
        stream
          .filter { it.toString().endsWith(".stf") }
          .map { arrayOf(it.fileName.toString().removeSuffix(".stf")) }
          .sorted(Comparator.comparing { it[0] })
          .toList()
      }
    }
  }

  @Test
  fun test() {
    val configPath = Paths.get(runfiles, "$pkg/$baseName.txtpb")
    val stfPath = Paths.get(runfiles, "$pkg/${baseName}_stfs/$testName.stf")
    val result = runStf(runfiles, configPath, stfPath)
    if (result is TestResult.Failure) fail(result.message)
  }
}
