package fourward.e2e.p4testgen

import fourward.stf.TestResult
import fourward.stf.runStf
import java.nio.file.Files
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parameterized test that runs p4testgen-generated STF tests for all P4 programs in a single JVM.
 *
 * Discovers all `*_stfs/` directories in the runfiles package, scans each for `.stf` files, and
 * parameterizes as `"programName/stfName"`. The txtpb path is derived from the program name.
 */
@RunWith(Parameterized::class)
class P4TestgenSuiteTest(private val testName: String) {

  companion object {
    private const val PKG = "e2e_tests/p4testgen"

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testCases(): List<Array<String>> {
      val pkgDir = fourward.bazel.repoRoot.resolve(PKG)
      return Files.list(pkgDir)
        .use { it.toList() }
        .filter { Files.isDirectory(it) && it.fileName.toString().endsWith("_stfs") }
        .sorted()
        .flatMap { stfDir ->
          val program = stfDir.fileName.toString().removeSuffix("_stfs")
          Files.list(stfDir)
            .use { it.toList() }
            .filter { it.toString().endsWith(".stf") }
            .map { arrayOf("$program/${it.fileName.toString().removeSuffix(".stf")}") }
            .sortedBy { it[0] }
        }
    }
  }

  @Test
  fun test() {
    val (program, stfName) = testName.split("/", limit = 2)
    val configPath = fourward.bazel.repoRoot.resolve("$PKG/$program.txtpb")
    val stfPath = fourward.bazel.repoRoot.resolve("$PKG/${program}_stfs/$stfName.stf")
    val result = runStf(configPath, stfPath)
    if (result is TestResult.Failure) fail(result.message)
  }
}
