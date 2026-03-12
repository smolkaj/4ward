package fourward.simulator

import fourward.ir.v1.Type
import fourward.sim.v1.SimulatorProto.TraceEvent

/**
 * Describes the extern being called — either a free function or an object method.
 *
 * P4 externs come in two forms: free functions (`mark_to_drop(sm)`, dispatched via `__call__`) and
 * object methods (`my_reg.read(dst, idx)`, dispatched via the method name). The interpreter creates
 * the appropriate variant; the [ExternHandler] pattern-matches on it.
 */
sealed class ExternCall {
  /** A free-function extern call, e.g. `mark_to_drop(sm)` or `hash(...)`. */
  data class FreeFunction(val name: String) : ExternCall()

  /**
   * A method call on an extern object instance, e.g. `my_reg.read(dst, idx)`.
   *
   * @property externType the declared extern type name (e.g. "register", "counter",
   *   "direct_meter").
   * @property instanceName the P4 instance name (e.g. "my_reg").
   * @property method the method name (e.g. "read", "write", "count").
   */
  data class Method(val externType: String, val instanceName: String, val method: String) :
    ExternCall()
}

/**
 * Callback interface the interpreter provides to extern handlers for evaluating arguments and
 * writing back out/inout parameters.
 *
 * Each extern call creates a fresh [ExternEvaluator] bound to that call's arguments and the current
 * interpreter state. The architecture's [ExternHandler] uses it to read arguments by index, write
 * back out-params, and emit trace events — without needing direct access to interpreter internals.
 */
interface ExternEvaluator {
  /**
   * Returns the IR return type of the extern call (for non-void methods like PSA register.read).
   */
  fun returnType(): Type

  /** Evaluates argument [index] and returns its runtime value. */
  fun evalArg(index: Int): Value

  /** Returns the IR type of argument [index]. */
  fun argType(index: Int): Type

  /** Writes [value] back to the lvalue at argument [index] (for out/inout params). */
  fun writeOutArg(index: Int, value: Value)

  /** Returns the zero/default value for [type] (e.g. for uninitialized register reads). */
  fun defaultValue(type: Type): Value

  /**
   * Builds a [TraceEvent.Builder] pre-populated with the current statement's source info.
   *
   * Extern handlers should use this instead of constructing [TraceEvent.newBuilder] directly, so
   * that trace events carry accurate source location information.
   */
  fun traceEventBuilder(): TraceEvent.Builder

  /** Appends a trace event to the current packet's trace. */
  fun addTraceEvent(event: TraceEvent)

  /** Peeks at remaining unparsed input bytes (for `_with_payload` checksum variants). */
  fun peekRemainingInput(): ByteArray
}

/**
 * Architecture-provided handler for extern functions and extern object methods.
 *
 * The interpreter delegates all extern calls — free functions (e.g. `mark_to_drop`) and object
 * methods (e.g. `register.read`) — to a single [handle] method. Each P4 architecture provides its
 * own implementation covering its extern library.
 */
fun interface ExternHandler {
  /**
   * Executes the extern described by [call] using [eval] to access arguments.
   *
   * @return the call's result value ([UnitVal] for void externs).
   * @throws IllegalStateException for unrecognised externs.
   */
  fun handle(call: ExternCall, eval: ExternEvaluator): Value
}
