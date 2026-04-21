package fourward.cli

import fourward.bazel.resolveRunfileProperty
import fourward.bazel.resolveRunfilePropertyOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * `4ward compile program.p4 -o output.txtpb` — compile a P4 program to a pipeline config.
 *
 * Spawns p4c-4ward as a subprocess. Locates the binary via:
 * 1. Bazel runfiles (when invoked through `bazel run`).
 * 2. `P4C_4WARD_PATH` environment variable.
 * 3. `PATH` lookup.
 *
 * Automatically adds the p4c standard include directory (containing `core.p4`, `v1model.p4`) from
 * runfiles when available. Returns an exit code (does not call `exitProcess`).
 */
fun compile(p4Source: Path, outputPath: Path?, includeDirs: List<Path>): Int {
  if (!Files.exists(p4Source)) {
    System.err.println("error: file not found: $p4Source")
    return ExitCode.USAGE_ERROR
  }
  val p4c = findP4c4ward()
  if (p4c == null) {
    System.err.println(
      "error: p4c-4ward not found. Set P4C_4WARD_PATH or use 'bazel run //cli:4ward'."
    )
    return ExitCode.COMPILE_ERROR
  }
  val includeArgs = resolveIncludeDirs(includeDirs)
  val output =
    outputPath ?: p4Source.resolveSibling(p4Source.fileName.toString().replace(".p4", ".txtpb"))

  val cmd = mutableListOf(p4c.toString())
  cmd += includeArgs.flatMap { listOf("-I", it.toString()) }
  cmd += listOf("-o", output.toString())
  cmd += p4Source.toString()

  val process = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
  process.inputStream.copyTo(System.out)
  val exitCode = process.waitFor()
  if (exitCode != 0) {
    System.err.println("error: p4c-4ward exited with code $exitCode (see compiler output above)")
    return ExitCode.COMPILE_ERROR
  }
  return ExitCode.SUCCESS
}

/**
 * Compiles [p4Source] to a temporary file and returns its path, or null on failure.
 *
 * Used by the `run` subcommand to compile-then-simulate in one shot.
 */
fun compileToTemp(p4Source: Path, includeDirs: List<Path>): Pair<Path?, Int> {
  val tmp = Files.createTempFile("4ward-", ".txtpb")
  tmp.toFile().deleteOnExit()
  val exitCode = compile(p4Source, tmp, includeDirs)
  return if (exitCode == ExitCode.SUCCESS) tmp to exitCode else null to exitCode
}

/** Locates the p4c-4ward binary: runfiles → env → PATH. Returns null if not found. */
private fun findP4c4ward(): Path? {
  // 1. Bazel runfiles: works when invoked via `bazel run //cli:4ward`.
  resolveRunfilePropertyOrNull("fourward.p4c_4ward")?.let { if (Files.isExecutable(it)) return it }

  // 2. Explicit env var.
  val envPath = System.getenv("P4C_4WARD_PATH")
  if (envPath != null) {
    val envCandidate = Path.of(envPath)
    if (Files.isExecutable(envCandidate)) return envCandidate
    System.err.println("warning: P4C_4WARD_PATH=$envPath is not executable, trying PATH")
  }

  // 3. PATH lookup.
  val pathDirs = System.getenv("PATH")?.split(":") ?: emptyList()
  for (dir in pathDirs) {
    val pathCandidate = Path.of(dir, "p4c-4ward")
    if (Files.isExecutable(pathCandidate)) return pathCandidate
  }

  return null
}

/**
 * Resolves include directories. Adds the p4c standard include dir from runfiles when available, so
 * `#include <core.p4>` and `#include <v1model.p4>` work out of the box.
 */
private fun resolveIncludeDirs(userDirs: List<Path>): List<Path> {
  val dirs = mutableListOf<Path>()

  resolveRunfileProperty("fourward.p4include").parent?.let { dirs.add(it) }

  dirs.addAll(userDirs)
  return dirs
}
