package fourward.cli

import java.nio.file.Path
import kotlin.system.exitProcess

/** Output format for trace trees. */
enum class OutputFormat {
  HUMAN,
  TEXTPROTO,
}

private const val USAGE =
  """Usage: 4ward <command> [options]

Commands:
  compile  program.p4 -o output.txtpb   Compile a P4 program to a pipeline config.
  sim      pipeline.txtpb test.stf       Run an STF test against a compiled pipeline.
  run      program.p4 test.stf           Compile and simulate in one step.

Options:
  --format=human|textproto   Trace output format (default: human).
  --help                     Show this help message."""

private const val SIM_USAGE =
  """Usage: 4ward sim [--format=human|textproto] <pipeline.txtpb> <test.stf>

Loads a compiled pipeline config and runs an STF test against it.
Prints the trace tree for each packet and reports PASS/FAIL.

Options:
  --format=human|textproto   Trace output format (default: human)."""

private const val COMPILE_USAGE =
  """Usage: 4ward compile [options] <program.p4>

Compiles a P4 program to a pipeline config (text-format protobuf).

Options:
  -o <path>            Output file (default: stdout).
  -I <dir>             Add include directory for P4 headers."""

private const val RUN_USAGE =
  """Usage: 4ward run [--format=human|textproto] <program.p4> <test.stf>

Compiles a P4 program and runs an STF test against it in one step.

Options:
  --format=human|textproto   Trace output format (default: human)."""

fun main(args: Array<String>) {
  if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
    println(USAGE)
    exitProcess(if (args.isEmpty()) ExitCode.USAGE_ERROR else ExitCode.SUCCESS)
  }

  val command = args[0]
  val rest = args.drop(1)

  when (command) {
    "sim" -> handleSim(rest)
    "compile" -> handleCompile(rest)
    "run" -> handleRun(rest)
    else -> {
      System.err.println("error: unknown command '$command'")
      System.err.println(USAGE)
      exitProcess(ExitCode.USAGE_ERROR)
    }
  }
}

private fun handleSim(args: List<String>) {
  if (args.any { it == "--help" || it == "-h" }) {
    println(SIM_USAGE)
    exitProcess(ExitCode.SUCCESS)
  }

  var format = OutputFormat.HUMAN
  val positional = mutableListOf<String>()

  for (arg in args) {
    when {
      arg.startsWith("--format=") -> {
        format =
          when (val f = arg.removePrefix("--format=")) {
            "human" -> OutputFormat.HUMAN
            "textproto" -> OutputFormat.TEXTPROTO
            else -> {
              System.err.println("error: unknown format '$f'")
              exitProcess(ExitCode.USAGE_ERROR)
            }
          }
      }
      arg.startsWith("-") -> {
        System.err.println("error: unknown option '$arg'")
        exitProcess(ExitCode.USAGE_ERROR)
      }
      else -> positional += arg
    }
  }

  if (positional.size != 2) {
    System.err.println("error: 'sim' requires exactly 2 arguments: <pipeline.txtpb> <test.stf>")
    System.err.println(SIM_USAGE)
    exitProcess(ExitCode.USAGE_ERROR)
  }

  simulate(Path.of(positional[0]), Path.of(positional[1]), format)
}

private fun handleCompile(args: List<String>) {
  if (args.any { it == "--help" || it == "-h" }) {
    println(COMPILE_USAGE)
    exitProcess(ExitCode.SUCCESS)
  }

  var outputPath: String? = null
  val includeDirs = mutableListOf<String>()
  val positional = mutableListOf<String>()

  var i = 0
  while (i < args.size) {
    when {
      args[i] == "-o" -> {
        i++
        if (i >= args.size) {
          System.err.println("error: -o requires an argument")
          exitProcess(ExitCode.USAGE_ERROR)
        }
        outputPath = args[i]
      }
      args[i] == "-I" -> {
        i++
        if (i >= args.size) {
          System.err.println("error: -I requires an argument")
          exitProcess(ExitCode.USAGE_ERROR)
        }
        includeDirs += args[i]
      }
      args[i].startsWith("-") -> {
        System.err.println("error: unknown option '${args[i]}'")
        exitProcess(ExitCode.USAGE_ERROR)
      }
      else -> positional += args[i]
    }
    i++
  }

  if (positional.size != 1) {
    System.err.println("error: 'compile' requires exactly 1 argument: <program.p4>")
    System.err.println(COMPILE_USAGE)
    exitProcess(ExitCode.USAGE_ERROR)
  }

  compile(Path.of(positional[0]), outputPath?.let { Path.of(it) }, includeDirs.map { Path.of(it) })
}

private fun handleRun(args: List<String>) {
  if (args.any { it == "--help" || it == "-h" }) {
    println(RUN_USAGE)
    exitProcess(ExitCode.SUCCESS)
  }

  var format = OutputFormat.HUMAN
  val includeDirs = mutableListOf<String>()
  val positional = mutableListOf<String>()

  var i = 0
  while (i < args.size) {
    when {
      args[i].startsWith("--format=") -> {
        format =
          when (val f = args[i].removePrefix("--format=")) {
            "human" -> OutputFormat.HUMAN
            "textproto" -> OutputFormat.TEXTPROTO
            else -> {
              System.err.println("error: unknown format '$f'")
              exitProcess(ExitCode.USAGE_ERROR)
            }
          }
      }
      args[i] == "-I" -> {
        i++
        if (i >= args.size) {
          System.err.println("error: -I requires an argument")
          exitProcess(ExitCode.USAGE_ERROR)
        }
        includeDirs += args[i]
      }
      args[i].startsWith("-") -> {
        System.err.println("error: unknown option '${args[i]}'")
        exitProcess(ExitCode.USAGE_ERROR)
      }
      else -> positional += args[i]
    }
    i++
  }

  if (positional.size != 2) {
    System.err.println("error: 'run' requires exactly 2 arguments: <program.p4> <test.stf>")
    System.err.println(RUN_USAGE)
    exitProcess(ExitCode.USAGE_ERROR)
  }

  run(
    Path.of(positional[0]),
    Path.of(positional[1]),
    format,
    includeDirs.map { Path.of(it) },
  )
}
