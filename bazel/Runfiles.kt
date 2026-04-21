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
 * Resolves a runfile path supplied by BUILD via `jvm_flags = ["-D<key>=$(rlocationpath <label>)"]`.
 * This is the preferred way to locate runfiles: the rlocationpath expansion yields a canonical
 * prefix that works uniformly in OSS Bazel (`_main/...`), BCR consumers (`fourward+/...`), and
 * google3 (`third_party/fourward/...`), so Kotlin never needs to know the current repo name.
 *
 * @throws IllegalStateException if the property is unset or the file is not in runfiles.
 */
fun resolveRunfileProperty(key: String): Path = resolveRunfile(requireRunfileProperty(key))

/**
 * Like [resolveRunfileProperty] but returns null if the property is unset or the file is missing.
 */
fun resolveRunfilePropertyOrNull(key: String): Path? =
  System.getProperty(key)?.let(::resolveRunfileOrNull)

/**
 * Returns the value of a system property that the BUILD rule sets via `jvm_flags =
 * ["-D<key>=$(rlocationpath <label>)"]`. Prefer [resolveRunfileProperty] unless you need the raw
 * string (e.g., to pass to a subprocess).
 */
fun requireRunfileProperty(key: String): String =
  checkNotNull(System.getProperty(key)) {
    "$key system property not set. Expected BUILD to pass " +
      "-D$key=\$(rlocationpath <label>) in jvm_flags."
  }

/**
 * Resolves a runfiles path to an absolute [Path]. Prefer [resolveRunfileProperty] for new code — it
 * avoids the portability footgun of hardcoding `_main/...` paths that break under BCR and google3.
 *
 * [path] must start with a repo directory prefix. Bare paths will not resolve.
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
