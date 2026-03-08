package fourward.cli

import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree

/**
 * Renders a [TraceTree] as a human-readable string with optional ANSI colors and tree-drawing
 * characters.
 */
object TraceFormatter {

  /** Format without colors (for tests, piped output, backward compatibility). */
  fun format(tree: TraceTree): String = format(tree, AnsiColor(enabled = false))

  /** Format with the given color settings. */
  fun format(tree: TraceTree, c: AnsiColor): String = buildString {
    appendTree(tree, indent = 0, c = c)
  }

  private fun StringBuilder.appendTree(tree: TraceTree, indent: Int, c: AnsiColor) {
    for (event in tree.eventsList) {
      appendEvent(event, indent, c)
    }
    when {
      tree.hasForkOutcome() -> {
        val fork = tree.forkOutcome
        appendLine("${pad(indent)}${c.magenta("fork")} (${fork.reason.humanName()})")
        val branches = fork.branchesList
        for ((i, branch) in branches.withIndex()) {
          val isLast = i == branches.lastIndex
          val connector = if (isLast) "└── " else "├── "
          val continuation = if (isLast) "    " else "│   "
          appendLine("${pad(indent)}${c.dim(connector)}${c.bold(branch.label)}")
          appendTree(branch.subtree, indent, c, continuation)
        }
      }
      tree.hasPacketOutcome() -> {
        val outcome = tree.packetOutcome
        when {
          outcome.hasOutput() -> {
            val out = outcome.output
            appendLine(
              "${pad(indent)}${c.green("output")} port ${out.egressPort}, ${out.payload.size()} bytes"
            )
          }
          outcome.hasDrop() -> {
            appendLine(
              "${pad(indent)}${c.red("drop")} (reason: ${outcome.drop.reason.humanName()})"
            )
          }
        }
      }
    }
  }

  /**
   * Appends a subtree under a fork branch, prepending [branchPrefix] to each line to maintain the
   * tree-drawing continuation lines (│ or blank).
   */
  private fun StringBuilder.appendTree(
    tree: TraceTree,
    indent: Int,
    c: AnsiColor,
    branchPrefix: String,
  ) {
    val prefix = pad(indent) + c.dim(branchPrefix)
    for (event in tree.eventsList) {
      appendEvent(event, prefix, c)
    }
    when {
      tree.hasForkOutcome() -> {
        val fork = tree.forkOutcome
        appendLine("${prefix}${c.magenta("fork")} (${fork.reason.humanName()})")
        val branches = fork.branchesList
        for ((i, branch) in branches.withIndex()) {
          val isLast = i == branches.lastIndex
          val connector = if (isLast) "└── " else "├── "
          val continuation = if (isLast) "    " else "│   "
          appendLine("${prefix}${c.dim(connector)}${c.bold(branch.label)}")
          appendTree(branch.subtree, indent, c, branchPrefix + continuation)
        }
      }
      tree.hasPacketOutcome() -> {
        val outcome = tree.packetOutcome
        when {
          outcome.hasOutput() -> {
            val out = outcome.output
            appendLine(
              "${prefix}${c.green("output")} port ${out.egressPort}, ${out.payload.size()} bytes"
            )
          }
          outcome.hasDrop() -> {
            appendLine("${prefix}${c.red("drop")} (reason: ${outcome.drop.reason.humanName()})")
          }
        }
      }
    }
  }

  private fun StringBuilder.appendEvent(event: TraceEvent, indent: Int, c: AnsiColor) {
    appendEvent(event, pad(indent), c)
  }

  private fun StringBuilder.appendEvent(event: TraceEvent, prefix: String, c: AnsiColor) {
    when {
      event.hasParserTransition() -> {
        val pt = event.parserTransition
        appendLine("${prefix}${c.cyan("parse")}: ${pt.fromState} -> ${pt.toState}")
      }
      event.hasTableLookup() -> {
        val tl = event.tableLookup
        val result = if (tl.hit) c.green("hit") else c.yellow("miss")
        appendLine("${prefix}table ${tl.tableName}: $result -> ${tl.actionName}")
      }
      event.hasActionExecution() -> {
        val ae = event.actionExecution
        if (ae.paramsMap.isEmpty()) {
          appendLine("${prefix}action ${ae.actionName}")
        } else {
          val params = ae.paramsMap.entries.joinToString(", ") { (k, v) -> "$k=${v.decimal()}" }
          appendLine("${prefix}action ${ae.actionName}($params)")
        }
      }
      event.hasBranch() -> {
        val b = event.branch
        val dir = if (b.taken) "then" else "else"
        appendLine("${prefix}branch ${b.controlName}: $dir")
      }
      event.hasExternCall() -> {
        val ec = event.externCall
        appendLine("${prefix}${c.blue("extern")} ${ec.externInstanceName}.${ec.method}()")
      }
      event.hasMarkToDrop() -> appendLine("${prefix}${c.red("mark_to_drop()")}")
      event.hasClone() ->
        appendLine("${prefix}${c.magenta("clone")} session ${event.clone.sessionId}")
    }
  }

  private fun pad(level: Int): String = "  ".repeat(level)

  private fun com.google.protobuf.ByteString.decimal(): String =
    java.math.BigInteger(1, toByteArray()).toString()

  private fun DropReason.humanName(): String =
    when (this) {
      DropReason.MARK_TO_DROP -> "mark_to_drop"
      DropReason.PARSER_REJECT -> "parser reject"
      DropReason.PIPELINE_EXECUTION_LIMIT_REACHED -> "execution limit"
      else -> "unknown"
    }

  private fun fourward.sim.v1.SimulatorProto.ForkReason.humanName(): String =
    name.removePrefix("ACTION_").lowercase().replace('_', ' ')
}
