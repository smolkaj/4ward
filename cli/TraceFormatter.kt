package fourward.cli

import fourward.sim.v1.DropReason
import fourward.sim.v1.TraceEvent
import fourward.sim.v1.TraceTree

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
            val reason =
              when (outcome.drop.reason) {
                DropReason.MARK_TO_DROP -> "mark_to_drop()"
                DropReason.PARSER_REJECT -> "parser reject"
                DropReason.PIPELINE_EXECUTION_LIMIT_REACHED -> "execution limit"
                else -> "dropped"
              }
            appendLine("${pad(indent)}drop ($reason)")
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
          val params = ae.paramsMap.entries.joinToString(", ") { (k, v) -> "$k=${v.hex()}" }
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
    }
  }

  private fun pad(level: Int): String = "  ".repeat(level)

  private fun com.google.protobuf.ByteString.hex(): String =
    toByteArray().joinToString("") { "%02x".format(it) }

  private fun fourward.sim.v1.ForkReason.humanName(): String =
    name.removePrefix("ACTION_").lowercase().replace('_', ' ')
}
