package fourward.web

import fourward.ir.BehavioralConfig
import fourward.ir.Expr
import fourward.ir.ParserDecl
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
      val type =
        when (state.name) {
          "accept",
          "reject" -> "exit"
          else -> "state"
        }
      nodes += Node(state.name, type, state.name)

      val transition = state.transition
      when (transition.kindCase) {
        fourward.ir.Transition.KindCase.NEXT_STATE -> {
          edges += Edge(state.name, transition.nextState)
        }
        fourward.ir.Transition.KindCase.SELECT -> {
          val sel = transition.select
          for (case in sel.casesList) {
            val label = case.keysetsList.joinToString(", ") { keysetLabel(it) }
            if (
              edges.none { it.from == state.name && it.to == case.nextState && it.label == label }
            ) {
              edges += Edge(state.name, case.nextState, label)
            }
          }
          if (sel.defaultState.isNotEmpty()) {
            edges += Edge(state.name, sel.defaultState, "default")
          }
        }
        fourward.ir.Transition.KindCase.KIND_NOT_SET,
        null -> {}
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
  private fun keysetLabel(keyset: fourward.ir.KeysetExpr): String =
    when (keyset.kindCase) {
      fourward.ir.KeysetExpr.KindCase.EXACT -> exprLabel(keyset.exact)
      fourward.ir.KeysetExpr.KindCase.MASK ->
        "${exprLabel(keyset.mask.value)} &&& ${exprLabel(keyset.mask.mask)}"
      fourward.ir.KeysetExpr.KindCase.RANGE ->
        "${exprLabel(keyset.range.lo)}..${exprLabel(keyset.range.hi)}"
      fourward.ir.KeysetExpr.KindCase.DEFAULT_CASE,
      fourward.ir.KeysetExpr.KindCase.VALUE_SET,
      fourward.ir.KeysetExpr.KindCase.KIND_NOT_SET,
      null -> "_"
    }

  private fun exprLabel(expr: Expr): String =
    when (expr.kindCase) {
      Expr.KindCase.LITERAL -> {
        val lit = expr.literal
        when (lit.kindCase) {
          fourward.ir.Literal.KindCase.INTEGER -> "0x${lit.integer.toString(16).uppercase()}"
          fourward.ir.Literal.KindCase.BOOLEAN -> lit.boolean.toString()
          fourward.ir.Literal.KindCase.BIG_INTEGER,
          fourward.ir.Literal.KindCase.ERROR_MEMBER,
          fourward.ir.Literal.KindCase.ENUM_MEMBER,
          fourward.ir.Literal.KindCase.STRING_LITERAL,
          fourward.ir.Literal.KindCase.KIND_NOT_SET,
          null -> lit.toString().trim()
        }
      }
      Expr.KindCase.NAME_REF -> expr.nameRef.name
      Expr.KindCase.FIELD_ACCESS,
      Expr.KindCase.ARRAY_INDEX,
      Expr.KindCase.SLICE,
      Expr.KindCase.CONCAT,
      Expr.KindCase.CAST,
      Expr.KindCase.BINARY_OP,
      Expr.KindCase.UNARY_OP,
      Expr.KindCase.METHOD_CALL,
      Expr.KindCase.TABLE_APPLY,
      Expr.KindCase.MUX,
      Expr.KindCase.STRUCT_EXPR,
      Expr.KindCase.KIND_NOT_SET,
      null -> "?"
    }
}
