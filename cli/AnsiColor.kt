package fourward.cli

/**
 * ANSI color helpers.
 *
 * When [enabled] is false, all methods return the input string unchanged — safe for piped output,
 * CI logs, and tests.
 */
class AnsiColor(val enabled: Boolean) {
  fun cyan(s: String): String = wrap(s, "36")

  fun green(s: String): String = wrap(s, "32")

  fun red(s: String): String = wrap(s, "31")

  fun yellow(s: String): String = wrap(s, "33")

  fun magenta(s: String): String = wrap(s, "35")

  fun blue(s: String): String = wrap(s, "34")

  fun dim(s: String): String = wrap(s, "2")

  fun bold(s: String): String = wrap(s, "1")

  private fun wrap(s: String, code: String): String =
    if (enabled) "\u001b[${code}m$s\u001b[0m" else s

  companion object {
    /**
     * Colors on if stdout is a terminal. Falls back to assuming a terminal when running under
     * `bazel run` (which sets BUILD_WORKING_DIRECTORY), since `System.console()` returns null in
     * Bazel's process wrapper even though the user is at a real terminal.
     */
    fun auto(): AnsiColor =
      AnsiColor(System.console() != null || System.getenv("BUILD_WORKING_DIRECTORY") != null)
  }
}
