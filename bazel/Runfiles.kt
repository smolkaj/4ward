package fourward.bazel

import com.google.devtools.build.runfiles.Runfiles
import java.nio.file.Files
import java.nio.file.Path

// unmapped(): no repo-mapping translation — paths are looked up as-is in the
// runfiles directory or manifest. Works in both OSS Bazel (where main-repo
// paths start with `_main/`) and google3 (where copybara rewrites `_main` to
// the google3 prefix). External-repo paths must use canonical names or be
// injected by the BUILD rule via $(rlocationpath ...).
private val runfiles: Runfiles = Runfiles.preload().unmapped()

/**
 * Resolves a runfiles path to an absolute [Path].
 *
 * [path] must start with a repo directory prefix. Bare paths will not resolve.
 * - Main repo: `"_main/web/frontend/index.html"`
 * - External repo: inject via `$(rlocationpath ...)` in BUILD
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
