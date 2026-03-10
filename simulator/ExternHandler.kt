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
  /** Number of arguments in this call. */
  val argCount: Int

  /** Evaluates argument [index] and returns its runtime value. */
  fun evalArg(index: Int): Value

  /** Returns the IR type of argument [index]. */
  fun argType(index: Int): Type

  /** Writes [value] back to the lvalue at argument [index] (for out/inout params). */
  fun writeOutArg(index: Int, value: Value)

  /** Returns the default (zero-initialized) value for argument [index]'s type. */
  fun defaultArgValue(index: Int): Value

  /**
   * Builds a [TraceEvent.Builder] pre-populated with the current statement's [SourceInfo].
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
 * The interpreter delegates extern free-function calls (those not in the action table and not core
 * P4 language constructs like `verify`) and extern object method calls (e.g., `register.read`,
 * `counter.count`) to this handler. Each P4 architecture provides its own implementation covering
 * its extern library.
 */
interface ExternHandler {
  /**
   * Executes the extern function [name] using [eval] to access arguments.
   *
   * @return the function's result value ([UnitVal] for void externs).
   * @throws IllegalStateException for unrecognised extern names.
   */
  fun call(name: String, eval: ExternEvaluator): Value

  /**
   * Executes a method call on an extern object instance.
   *
   * @param externType the extern type name (e.g., "register", "counter", "direct_meter").
   * @param instanceName the extern instance name (e.g., "my_register").
   * @param method the method being called (e.g., "read", "write", "count").
   * @param eval evaluator for accessing method arguments.
   * @return the method's result value ([UnitVal] for void methods).
   * @throws IllegalStateException for unrecognised extern types or methods.
   */
  fun callMethod(
    externType: String,
    instanceName: String,
    method: String,
    eval: ExternEvaluator,
  ): Value = error("unhandled extern method: $externType.$method on $instanceName")

  companion object {
    /** Creates an [ExternHandler] that only handles free-function calls (via [call]). */
    operator fun invoke(handler: (String, ExternEvaluator) -> Value): ExternHandler =
      object : ExternHandler {
        override fun call(name: String, eval: ExternEvaluator) = handler(name, eval)
      }
  }
}
