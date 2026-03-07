package fourward.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `4ward compile program.p4 -o output.txtpb` — compile a P4 program to a pipeline config.
 *
 * Spawns p4c-4ward as a subprocess. Locates the binary via:
 * 1. Bazel runfiles (when invoked through `bazel run`).
 * 2. `P4C_4WARD_PATH` environment variable.
 * 3. `PATH` lookup.
 *
 * Automatically adds the p4c standard include directory (containing `core.p4`, `v1model.p4`) from
 * runfiles when available.
 */
fun compile(p4Source: Path, outputPath: Path?, includeDirs: List<Path>) {
  val p4c = findP4c4ward()
  val includeArgs = resolveIncludeDirs(p4c, includeDirs)
  val output = outputPath ?: p4Source.resolveSibling(p4Source.fileName.toString().replace(".p4", ".txtpb"))

  val cmd = mutableListOf(p4c.toString())
  cmd += includeArgs.flatMap { listOf("-I", it.toString()) }
  cmd += listOf("-o", output.toString())
  cmd += p4Source.toString()

  val process =
    ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
  // Forward stdout so the user sees any compiler diagnostics.
  process.inputStream.copyTo(System.out)
  val exitCode = process.waitFor()
  if (exitCode != 0) {
    System.err.println("error: p4c-4ward exited with code $exitCode")
    exitProcess(ExitCode.COMPILE_ERROR)
  }
}

/**
 * Compiles [p4Source] to a temporary file and returns its path.
 *
 * Used by the `run` subcommand to compile-then-simulate in one shot.
 */
fun compileToTemp(p4Source: Path, includeDirs: List<Path>): Path {
  val tmp = Files.createTempFile("4ward-", ".txtpb")
  tmp.toFile().deleteOnExit()
  compile(p4Source, tmp, includeDirs)
  return tmp
}

/** Locates the p4c-4ward binary: runfiles → env → PATH. */
private fun findP4c4ward(): Path {
  // 1. Bazel runfiles: works when invoked via `bazel run //cli:4ward`.
  val runfiles = System.getenv("JAVA_RUNFILES")
  if (runfiles != null) {
    val candidate = Path.of(runfiles, "_main/p4c_backend/p4c-4ward")
    if (Files.isExecutable(candidate)) return candidate
  }

  // 2. Explicit env var.
  val envPath = System.getenv("P4C_4WARD_PATH")
  if (envPath != null) {
    val candidate = Path.of(envPath)
    if (Files.isExecutable(candidate)) return candidate
    System.err.println("warning: P4C_4WARD_PATH=$envPath is not executable, trying PATH")
  }

  // 3. PATH lookup.
  val pathDirs = System.getenv("PATH")?.split(":") ?: emptyList()
  for (dir in pathDirs) {
    val candidate = Path.of(dir, "p4c-4ward")
    if (Files.isExecutable(candidate)) return candidate
  }

  System.err.println("error: p4c-4ward not found. Set P4C_4WARD_PATH or use 'bazel run //cli:4ward'.")
  exitProcess(ExitCode.COMPILE_ERROR)
}

/**
 * Resolves include directories. Adds the p4c standard include dir from runfiles when available,
 * so `#include <core.p4>` and `#include <v1model.p4>` work out of the box.
 */
private fun resolveIncludeDirs(p4c: Path, userDirs: List<Path>): List<Path> {
  val dirs = mutableListOf<Path>()

  // p4c standard includes from runfiles (core.p4, v1model.p4, etc.).
  // Bzlmod maps `@p4c` to `p4c+` in the runfiles tree.
  val runfiles = System.getenv("JAVA_RUNFILES")
  if (runfiles != null) {
    val p4include = Path.of(runfiles, "p4c+/p4include")
    if (Files.isDirectory(p4include)) {
      dirs.add(p4include)
    }
  }

  dirs.addAll(userDirs)
  return dirs
}
