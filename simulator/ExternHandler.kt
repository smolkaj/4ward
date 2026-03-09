package fourward.simulator

import fourward.ir.v1.SourceInfo
import fourward.ir.v1.Type
import fourward.sim.v1.SimulatorProto.TraceEvent

/**
 * Callback interface the interpreter provides to extern handlers for evaluating arguments and
 * writing back out/inout parameters.
 *
 * Each extern call creates a fresh [ExternEvaluator] bound to that call's arguments and the current
 * interpreter state. The architecture's [ExternHandler] uses it to read arguments by index, write
 * back out-params, and emit trace events — without needing direct access to interpreter internals.
 */
interface ExternEvaluator {
  /** Evaluates argument [index] and returns its runtime value. */
  fun evalArg(index: Int): Value

  /** Returns the IR type of argument [index]. */
  fun argType(index: Int): Type

  /** Writes [value] back to the lvalue at argument [index] (for out/inout params). */
  fun writeOutArg(index: Int, value: Value)

  /**
   * Builds a [TraceEvent.Builder] pre-populated with the current statement's [SourceInfo].
   *
   * Extern handlers should use this instead of constructing [TraceEvent.newBuilder] directly,
   * so that trace events carry accurate source location information.
   */
  fun traceEventBuilder(): TraceEvent.Builder

  /** Appends a trace event to the current packet's trace. */
  fun addTraceEvent(event: TraceEvent)

  /** Peeks at remaining unparsed input bytes (for `_with_payload` checksum variants). */
  fun peekRemainingInput(): ByteArray
}

/**
 * Architecture-provided handler for extern function calls.
 *
 * The interpreter delegates all extern free-function calls (those not in the action table and not
 * core P4 language constructs like `verify`) to this handler. Each P4 architecture provides its own
 * implementation covering its extern library (v1model: `mark_to_drop`, `clone`, `hash`, etc.).
 */
fun interface ExternHandler {
  /**
   * Executes the extern function [name] using [eval] to access arguments.
   *
   * @return the function's result value ([UnitVal] for void externs).
   * @throws IllegalStateException for unrecognised extern names.
   */
  fun call(name: String, eval: ExternEvaluator): Value
}
