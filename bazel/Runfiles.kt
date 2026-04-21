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
 * Returns the `fourward.p4include` system property. BUILD must set it via `jvm_flags =
 * ["-Dfourward.p4include=$(rlocationpath @p4c//p4include:core.p4)"]`.
 */
fun requireP4IncludeProperty(): String = requireRunfileProperty("fourward.p4include")

/**
 * Returns the value of a system property that the BUILD rule sets via `jvm_flags =
 * ["-D<key>=$(rlocationpath <label>)"]`. This is how runfile paths are passed from BUILD to Kotlin
 * without hardcoding a repo prefix — works uniformly under OSS Bazel (`_main/...`), BCR consumers
 * (`fourward+/...`), and google3 (`third_party/fourward/...`).
 */
fun requireRunfileProperty(key: String): String =
  checkNotNull(System.getProperty(key)) {
    "$key system property not set. Expected BUILD to pass " +
      "-D$key=\$(rlocationpath <label>) in jvm_flags."
  }
