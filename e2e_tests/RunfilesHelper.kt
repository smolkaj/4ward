package fourward.e2e

import com.google.devtools.build.runfiles.Runfiles
import java.nio.file.Files
import java.nio.file.Path

/** Portable runfiles resolution across OSS Bazel and google3/blaze. */
object RunfilesHelper {
  private val runfiles: Runfiles? =
    try {
      Runfiles.preload().withSourceRepository("_main")
    } catch (_: Exception) {
      null
    }

  /**
   * Resolves a repo-relative path (e.g. "e2e_tests/corpus/foo.stf") to an absolute Path using the
   * Bazel Runfiles library. Falls back to the legacy JAVA_RUNFILES env var if the library is
   * unavailable.
   */
  fun rlocation(repoRelativePath: String): Path {
    // 1. Runfiles library (portable across OSS Bazel and google3).
    runfiles?.rlocation(repoRelativePath)?.let { resolved ->
      val path = Path.of(resolved)
      if (Files.exists(path)) return path
    }
    // 2. Legacy fallback for environments without the manifest.
    val root = System.getenv("JAVA_RUNFILES") ?: "."
    return Path.of(root, "_main", repoRelativePath)
  }
}
