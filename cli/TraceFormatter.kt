package fourward.cli

import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree

/** Renders a [TraceTree] as a human-readable indented string, optionally with ANSI colors. */
object TraceFormatter {

  fun format(tree: TraceTree, color: Boolean = false): String = buildString {
    appendTree(tree, prefix = "", color)
  }

  private fun StringBuilder.appendTree(tree: TraceTree, prefix: String, color: Boolean) {
    for (event in tree.eventsList) {
      appendEvent(event, prefix, color)
    }
    when {
      tree.hasForkOutcome() -> {
        val fork = tree.forkOutcome
        appendLine("$prefix${style("fork (${fork.reason.humanName()})", MAGENTA, color)}")
        for ((i, branch) in fork.branchesList.withIndex()) {
          val isLast = i == fork.branchesList.lastIndex
          if (color) {
            val connector = if (isLast) "└─ " else "├─ "
            val childPrefix = prefix + if (isLast) "   " else "│  "
            appendLine("$prefix${style(connector + branch.label, MAGENTA, color)}")
            appendTree(branch.subtree, childPrefix, color)
          } else {
            appendLine("$prefix  branch: ${branch.label}")
            appendTree(branch.subtree, "$prefix    ", color)
          }
        }
      }
      tree.hasPacketOutcome() -> {
        val outcome = tree.packetOutcome
        when {
          outcome.hasOutput() -> {
            val out = outcome.output
            val text = "output port ${out.egressPort}, ${out.payload.size()} bytes"
            appendLine("$prefix${style(text, BOLD_GREEN, color)}")
          }
          outcome.hasDrop() -> {
            val text = "drop (reason: ${outcome.drop.reason.humanName()})"
            appendLine("$prefix${style(text, BOLD_RED, color)}")
          }
        }
      }
    }
  }

  private fun StringBuilder.appendEvent(event: TraceEvent, prefix: String, color: Boolean) {
    val arrow = if (color) "→" else "->"
    when {
      event.hasParserTransition() -> {
        val pt = event.parserTransition
        appendLine("$prefix${style("parse: ${pt.fromState} $arrow ${pt.toState}", CYAN, color)}")
      }
      event.hasTableLookup() -> {
        val tl = event.tableLookup
        val result = if (tl.hit) "hit" else "miss"
        val text = "table ${tl.tableName}: $result $arrow ${tl.actionName}"
        appendLine("$prefix${style(text, if (tl.hit) GREEN else RED, color)}")
      }
      event.hasActionExecution() -> {
        val ae = event.actionExecution
        val text =
          if (ae.paramsMap.isEmpty()) {
            "action ${ae.actionName}"
          } else {
            val params = ae.paramsMap.entries.joinToString(", ") { (k, v) -> "$k=${v.decimal()}" }
            "action ${ae.actionName}($params)"
          }
        appendLine("$prefix${style(text, YELLOW, color)}")
      }
      event.hasBranch() -> {
        val b = event.branch
        val dir = if (b.taken) "then" else "else"
        appendLine("${prefix}branch ${b.controlName}: $dir")
      }
      event.hasExternCall() -> {
        val ec = event.externCall
        appendLine("${prefix}extern ${ec.externInstanceName}.${ec.method}()")
      }
      event.hasMarkToDrop() -> appendLine("$prefix${style("mark_to_drop()", RED, color)}")
      event.hasClone() ->
        appendLine("$prefix${style("clone session ${event.clone.sessionId}", MAGENTA, color)}")
    }
  }

  // -- ANSI helpers --

  private const val RESET = "\u001b[0m"
  private const val CYAN = "\u001b[36m"
  private const val GREEN = "\u001b[32m"
  private const val RED = "\u001b[31m"
  private const val YELLOW = "\u001b[33m"
  private const val MAGENTA = "\u001b[35m"
  private const val BOLD_GREEN = "\u001b[1;32m"
  private const val BOLD_RED = "\u001b[1;31m"

  private fun style(text: String, code: String, color: Boolean): String =
    if (color) "$code$text$RESET" else text

  // -- Formatting helpers --

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
