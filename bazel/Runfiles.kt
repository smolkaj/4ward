package fourward.bazel

import com.google.devtools.build.runfiles.Runfiles
import java.nio.file.Files
import java.nio.file.Path

// "The main repository always has the empty string as the canonical name."
// https://bazel.build/external/overview#canonical-repo-name
private val runfiles: Runfiles = Runfiles.preload().withSourceRepository("")

/**
 * Resolves a runfiles path to an absolute [Path].
 *
 * The first path component identifies the repo in the runfiles directory tree:
 * - Main repo: `resolveRunfile("_main/web/frontend/index.html")`
 * - External: `resolveRunfile("p4c/p4include/core.p4")`
 *
 * `_main` is the workspace name ([ctx.workspace_name]) under bzlmod. External repos use their
 * apparent name (e.g. `p4c` for `@p4c`).
 *
 * @throws IllegalStateException if the path cannot be resolved.
 */
fun resolveRunfile(path: String): Path =
  resolveRunfileOrNull(path)
    ?: error("Cannot resolve runfile '$path'. Are you running inside 'bazel run' or 'bazel test'?")

/** Like [resolveRunfile] but returns null if the file does not exist in the runfiles tree. */
fun resolveRunfileOrNull(path: String): Path? {
  val resolved = runfiles.rlocation(path) ?: return null
  val p = Path.of(resolved)
  return if (Files.exists(p)) p else null
}

/**
 * Returns the `fourward.p4include` system property, which the BUILD rule must set via `jvm_flags =
 * ["-Dfourward.p4include=$(rlocationpath @p4c//p4include:core.p4)"]`.
 */
fun requireP4IncludeProperty(): String =
  checkNotNull(System.getProperty("fourward.p4include")) {
    "fourward.p4include system property not set. " +
      "The kt_jvm_binary must pass -Dfourward.p4include=\$(rlocationpath @p4c//p4include:core.p4)"
  }
