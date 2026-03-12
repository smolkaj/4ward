package fourward.web

import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.Expr
import fourward.ir.v1.ParserDecl
import fourward.web.ControlGraphExtractor.ControlGraph
import fourward.web.ControlGraphExtractor.Edge
import fourward.web.ControlGraphExtractor.Node

/**
 * Extracts a state-transition graph from a [ParserDecl].
 *
 * Nodes are parser states; edges are transitions (direct or select-case). The result reuses
 * [ControlGraph] so the frontend can render parser and control graphs uniformly.
 */
object ParserGraphExtractor {

  fun extract(config: BehavioralConfig): List<ControlGraph> =
    config.parsersList.map { extractParser(it) }

  private fun extractParser(parser: ParserDecl): ControlGraph {
    val nodes = mutableListOf<Node>()
    val edges = mutableListOf<Edge>()
    val stateNames = parser.statesList.map { it.name }.toSet()

    for (state in parser.statesList) {
      val type = when (state.name) {
        "accept", "reject" -> "exit"
        else -> "state"
      }
      nodes += Node(state.name, type, state.name)

      val transition = state.transition
      when {
        transition.hasNextState() -> {
          edges += Edge(state.name, transition.nextState)
        }
        transition.hasSelect() -> {
          val sel = transition.select
          for (case in sel.casesList) {
            val label = case.keysetsList.joinToString(", ") { keysetLabel(it) }
            if (edges.none { it.from == state.name && it.to == case.nextState && it.label == label }) {
              edges += Edge(state.name, case.nextState, label)
            }
          }
          if (sel.defaultState.isNotEmpty()) {
            edges += Edge(state.name, sel.defaultState, "default")
          }
        }
      }
    }

    // Add implicit accept/reject nodes if they're referenced but not declared as states.
    for (name in listOf("accept", "reject")) {
      if (name !in stateNames && edges.any { it.to == name }) {
        nodes += Node(name, "exit", name)
      }
    }

    // Remove unreachable nodes (e.g. "reject" when no transition targets it).
    val connected = edges.flatMapTo(mutableSetOf()) { listOf(it.from, it.to) }
    nodes.removeAll { it.name !in connected }

    return ControlGraph(parser.name, nodes, edges)
  }

  /** Human-readable label for a keyset expression. */
  private fun keysetLabel(keyset: fourward.ir.v1.KeysetExpr): String =
    when {
      keyset.hasExact() -> exprLabel(keyset.exact)
      keyset.hasMask() -> "${exprLabel(keyset.mask.value)} &&& ${exprLabel(keyset.mask.mask)}"
      keyset.hasRange() -> "${exprLabel(keyset.range.lo)}..${exprLabel(keyset.range.hi)}"
      else -> "_"
    }

  private fun exprLabel(expr: Expr): String =
    when {
      expr.hasLiteral() -> {
        val lit = expr.literal
        when {
          lit.hasInteger() -> "0x${lit.integer.toString(16).uppercase()}"
          lit.hasBoolean() -> lit.boolean.toString()
          else -> lit.toString().trim()
        }
      }
      expr.hasNameRef() -> expr.nameRef.name
      else -> "?"
    }
}
