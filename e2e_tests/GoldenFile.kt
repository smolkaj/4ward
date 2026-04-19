package fourward.e2e

import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.fail

/**
 * Asserts that [actual] matches the content of a golden file.
 * - [goldenFileName]: e.g. "my_test.golden.txtpb". Resolved relative to the test's package
 *   directory in runfiles.
 * - [pkg]: the Bazel package path, e.g. "e2e_tests/trace_tree".
 * - [actual]: the string to compare against the golden file.
 *
 * In update mode (`bazel run <target> -- --update`), writes [actual] to the source golden file
 * instead of comparing.
 */
fun assertMatchesGoldenFile(goldenFileName: String, pkg: String, actual: String) {
  val goldenPath = runfilePath(pkg, goldenFileName)

  if (isUpdateMode()) {
    val workspace =
      System.getenv("BUILD_WORKSPACE_DIRECTORY")
        ?: error("BUILD_WORKSPACE_DIRECTORY not set. Run via `bazel run`, not `bazel test`.")
    val sourcePath = Paths.get(workspace, pkg, goldenFileName)
    sourcePath.toFile().writeText(actual)
    println("Updated: $sourcePath")
    return
  }

  val expected = goldenPath.toFile().readText()
  if (expected != actual) {
    val target = System.getenv("TEST_TARGET") ?: "<this target>"
    fail(
      "Golden file mismatch: $goldenFileName\n" +
        "To update: bazel run $target -- --update\n\n" +
        "Expected:\n$expected\n" +
        "Actual:\n$actual"
    )
  }
}

/** Returns true if `-- --update` was passed on the command line. */
fun isUpdateMode(): Boolean = System.getProperty("sun.java.command")?.contains("--update") == true

/** Resolves a file path relative to a Bazel package in runfiles. */
fun runfilePath(pkg: String, fileName: String): Path = RunfilesHelper.rlocation("$pkg/$fileName")
