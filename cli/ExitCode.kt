package fourward.cli

/** Process exit codes used by the 4ward CLI. */
object ExitCode {
  const val SUCCESS = 0
  const val TEST_FAILURE = 1
  const val USAGE_ERROR = 2
  const val COMPILE_ERROR = 3
  const val INTERNAL_ERROR = 4
}
