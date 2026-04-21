package fourward.bazel

import com.google.devtools.build.runfiles.Runfiles
import java.nio.file.Path

private val runfiles: Runfiles = Runfiles.preload().unmapped()

/**
 * Runfile root of the main repository. Its prefix varies per build environment — `_main` under OSS
 * root builds, `fourward+` under BCR consumers, `third_party/fourward` under google3 — so
 * hardcoding any literal prefix is a portability bug. Compose with `.resolve("path/to/file")` to
 * locate specific runfiles.
 *
 * Example:
 * ```
 * val config = repoRoot.resolve("e2e_tests/basic_table/basic_table.txtpb")
 * ```
 */
val repoRoot: Path = resolveRlocation(REPO_ROOT_RLOCATIONPATH, "runfiles anchor").parent

/**
 * Resolves a runfile path supplied by BUILD via `jvm_flags = ["-D<key>=$(rlocationpath <label>)"]`.
 * Use this for files in **external** repositories (e.g. `@p4c//p4include:core.p4`) whose canonical
 * name varies per environment. For files in the main repo, use [repoRoot] + `.resolve(...)`.
 */
fun resolveRunfileProperty(key: String): Path {
  val rlocation =
    checkNotNull(System.getProperty(key)) {
      "$key system property not set. Expected BUILD to pass " +
        "-D$key=\$(rlocationpath <label>) in jvm_flags."
    }
  return resolveRlocation(rlocation, "$key ($rlocation)")
}

private fun resolveRlocation(rlocation: String, what: String): Path =
  Path.of(
    checkNotNull(runfiles.rlocation(rlocation)) {
      "$what not found in runfiles tree. Are you running inside 'bazel run' or 'bazel test'?"
    }
  )
