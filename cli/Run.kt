package fourward.cli

import java.nio.file.Path

/**
 * `4ward run program.p4 test.stf` — compile and simulate in one step.
 *
 * Compiles the P4 source to a temporary pipeline config, then runs the STF test against it. This is
 * the "hello world" entry point for newcomers. Returns an exit code (does not call `exitProcess`).
 */
fun run(
  p4Source: Path,
  stfPath: Path,
  format: OutputFormat,
  includeDirs: List<Path>,
  color: Boolean,
): Int {
  val (pipelinePath, compileCode) = compileToTemp(p4Source, includeDirs)
  if (pipelinePath == null) return compileCode
  return simulate(pipelinePath, stfPath, format, color)
}
