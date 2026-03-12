package fourward.cli

import fourward.sim.v1.SimulatorProto.DropReason
import fourward.sim.v1.SimulatorProto.TraceEvent
import fourward.sim.v1.SimulatorProto.TraceTree

/** Renders a [TraceTree] as a human-readable indented string. */
object TraceFormatter {

  fun format(tree: TraceTree): String = buildString { appendTree(tree, indent = 0) }

  private fun StringBuilder.appendTree(tree: TraceTree, indent: Int) {
    for (event in tree.eventsList) {
      appendEvent(event, indent)
    }
    when {
      tree.hasForkOutcome() -> {
        val fork = tree.forkOutcome
        appendLine("${pad(indent)}fork (${fork.reason.humanName()})")
        for (branch in fork.branchesList) {
          appendLine("${pad(indent + 1)}branch: ${branch.label}")
          appendTree(branch.subtree, indent + 2)
        }
      }
      tree.hasPacketOutcome() -> {
        val outcome = tree.packetOutcome
        when {
          outcome.hasOutput() -> {
            val out = outcome.output
            appendLine("${pad(indent)}output port ${out.egressPort}, ${out.payload.size()} bytes")
          }
          outcome.hasDrop() -> {
            appendLine("${pad(indent)}drop (reason: ${outcome.drop.reason.humanName()})")
          }
        }
      }
    }
  }

  private fun StringBuilder.appendEvent(event: TraceEvent, indent: Int) {
    val prefix = pad(indent)
    when {
      event.hasParserTransition() -> {
        val pt = event.parserTransition
        appendLine("${prefix}parse: ${pt.fromState} -> ${pt.toState}")
      }
      event.hasTableLookup() -> {
        val tl = event.tableLookup
        val result = if (tl.hit) "hit" else "miss"
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
        appendLine("${prefix}extern ${ec.externInstanceName}.${ec.method}()")
      }
      event.hasMarkToDrop() -> appendLine("${prefix}mark_to_drop()")
      event.hasClone() -> appendLine("${prefix}clone session ${event.clone.sessionId}")
      event.hasLogMessage() -> appendLine("${prefix}log_msg: ${event.logMessage.message}")
      event.hasAssertion() -> {
        val result = if (event.assertion.passed) "passed" else "FAILED"
        appendLine("${prefix}assert: $result")
      }
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
      DropReason.ASSERTION_FAILURE -> "assertion failure"
      else -> "unknown"
    }

  private fun fourward.sim.v1.SimulatorProto.ForkReason.humanName(): String =
    name.removePrefix("ACTION_").lowercase().replace('_', ' ')
}
