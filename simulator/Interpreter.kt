package fourward.simulator

import fourward.ir.v1.BinaryOperator
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.Direction
import fourward.ir.v1.Expr
import fourward.ir.v1.Literal
import fourward.ir.v1.MethodCall
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.ParserDecl
import fourward.ir.v1.Stmt
import fourward.ir.v1.TableApplyExpr
import fourward.ir.v1.TableBehavior
import fourward.ir.v1.Type
import fourward.ir.v1.UnaryOperator
import fourward.sim.v1.ActionExecutionEvent
import fourward.sim.v1.BranchEvent
import fourward.sim.v1.ParserTransitionEvent
import fourward.sim.v1.TableLookupEvent
import fourward.sim.v1.TraceEvent
import java.math.BigInteger

/**
 * The core P4 interpreter.
 *
 * Walks the proto IR tree for a single packet traversal. Variable scopes live in [Environment];
 * packet-level state (input buffer, output buffer, trace) lives in [PacketContext]; program-global
 * state (table entries, extern instances) lives in [TableStore].
 *
 * The interpreter is deliberately simple: it pattern-matches on proto oneof fields and dispatches
 * to focused methods. There is no bytecode compilation or optimisation — correctness and
 * readability are the goals.
 */
class Interpreter(
  private val config: P4BehavioralConfig,
  private val tableStore: TableStore,
  private val packetCtx: PacketContext? = null,
) {
  private val parsers: Map<String, ParserDecl> = config.parsersList.associateBy { it.name }

  private val controls: Map<String, ControlDecl> = config.controlsList.associateBy { it.name }

  // Actions may be declared either at the top level or as local actions inside controls.
  // After the midend, all relevant actions end up in control.localActionsList.
  //
  // Indexing rules:
  // - Every action is indexed under name (= originalName) so that table dispatch
  //   (which resolves via p4info aliases = originalNames) always finds it. We use
  //   putIfAbsent so that a renamed duplicate (e.g. the second "do_thing" copy that
  //   the midend renames to "do_thing_1") does not overwrite the authoritative first
  //   declaration, which carries the actual action body.
  // - If the midend renamed the action (currentName != ""), it is also indexed under
  //   currentName so that direct call sites using the post-midend name can resolve it.
  private val actions: Map<String, fourward.ir.v1.ActionDecl> = buildMap {
    fun index(action: fourward.ir.v1.ActionDecl) {
      if (!containsKey(action.name)) put(action.name, action)
      if (action.currentName.isNotEmpty()) put(action.currentName, action)
    }
    config.actionsList.forEach { index(it) }
    config.controlsList.forEach { ctrl -> ctrl.localActionsList.forEach { index(it) } }
  }

  private val tables: Map<String, TableBehavior> = config.tablesList.associateBy { it.name }

  /** Non-null packet context; throws a clear error if packet I/O is attempted without one. */
  private val packet: PacketContext
    get() = packetCtx ?: error("packet I/O requires a PacketContext")

  private val types = config.typesList.associateBy { it.name }

  // -------------------------------------------------------------------------
  // Parser
  // -------------------------------------------------------------------------

  fun runParser(parserName: String, env: Environment) {
    val parser = parsers[parserName] ?: error("unknown parser: $parserName")
    runParserState(parser, "start", env)
  }

  private fun runParserState(parser: ParserDecl, startState: String, env: Environment) {
    // Index states by name for O(1) lookup during traversal.
    val statesByName = parser.statesList.associateBy { it.name }

    var stateName = startState
    while (stateName != "accept" && stateName != "reject") {
      val state =
        statesByName[stateName] ?: error("unknown parser state: $stateName in ${parser.name}")

      for (stmt in state.stmtsList) execStmt(stmt, env)

      val nextState =
        when {
          state.transition.hasSelect() -> evalSelect(state.transition.select, env)
          else -> state.transition.nextState
        }

      packetCtx?.addTraceEvent(
        TraceEvent.newBuilder()
          .setParserTransition(
            ParserTransitionEvent.newBuilder()
              .setParserName(parser.name)
              .setFromState(stateName)
              .setToState(nextState)
          )
          .build()
      )

      stateName = nextState
    }
  }

  private fun evalSelect(select: fourward.ir.v1.SelectTransition, env: Environment): String {
    val keyValues = select.keysList.map { evalExpr(it, env) }
    // Keyset expressions in parser select are always compile-time constants; a single
    // empty environment is correct for all of them.
    val constEnv = Environment()
    for (case in select.casesList) {
      if (keyValues.zip(case.keysetsList).all { (v, k) -> matchesKeyset(v, k, constEnv) }) {
        return case.nextState
      }
    }
    return select.defaultState
  }

  private fun matchesKeyset(
    value: Value,
    keyset: fourward.ir.v1.KeysetExpr,
    constEnv: Environment,
  ): Boolean =
    when {
      keyset.hasDefaultCase() -> true
      keyset.hasExact() -> value == evalExpr(keyset.exact, constEnv)
      keyset.hasMask() -> {
        val v = (value as BitVal).bits
        val mask = (evalExpr(keyset.mask.mask, constEnv) as BitVal).bits
        val want = (evalExpr(keyset.mask.value, constEnv) as BitVal).bits
        (v and mask) == (want and mask)
      }
      keyset.hasRange() -> {
        val v = (value as BitVal).bits
        val lo = (evalExpr(keyset.range.lo, constEnv) as BitVal).bits
        val hi = (evalExpr(keyset.range.hi, constEnv) as BitVal).bits
        v >= lo && v <= hi
      }
      else -> error("unhandled keyset kind: $keyset")
    }

  // -------------------------------------------------------------------------
  // Control
  // -------------------------------------------------------------------------

  fun runControl(controlName: String, env: Environment) {
    val control = controls[controlName] ?: error("unknown control: $controlName")
    env.pushScope()
    try {
      for (varDecl in control.localVarsList) {
        val init =
          if (varDecl.hasInitializer()) evalExpr(varDecl.initializer, env)
          else defaultValue(varDecl.type, types)
        env.define(varDecl.name, init)
      }
      execBlock(control.applyBodyList, env)
    } finally {
      env.popScope()
    }
  }

  // -------------------------------------------------------------------------
  // Statements
  // -------------------------------------------------------------------------

  private fun execBlock(stmts: List<Stmt>, env: Environment) {
    for (stmt in stmts) execStmt(stmt, env)
  }

  private fun execStmt(stmt: Stmt, env: Environment) {
    when {
      stmt.hasAssignment() -> execAssignment(stmt.assignment, env)
      stmt.hasMethodCall() -> evalExpr(stmt.methodCall.call, env) // result discarded
      stmt.hasIfStmt() -> execIf(stmt.ifStmt, env)
      stmt.hasSwitchStmt() -> execSwitch(stmt.switchStmt, env)
      stmt.hasBlock() -> execBlock(stmt.block.stmtsList, env)
      stmt.hasExit() -> throw ExitException()
      stmt.hasReturnStmt() -> throw ReturnException(evalExpr(stmt.returnStmt.value, env))
      else -> error("unhandled statement kind: $stmt")
    }
  }

  private fun execAssignment(assign: fourward.ir.v1.AssignmentStmt, env: Environment) {
    val rval = evalExpr(assign.rhs, env)
    setLValue(assign.lhs, rval, env)
  }

  private fun execIf(ifStmt: fourward.ir.v1.IfStmt, env: Environment) {
    val condition = (evalExpr(ifStmt.condition, env) as BoolVal).value

    // The control name is not directly available in the Stmt proto; we use
    // a placeholder here. A richer trace could include source location info.
    packetCtx?.addTraceEvent(
      TraceEvent.newBuilder().setBranch(BranchEvent.newBuilder().setTaken(condition)).build()
    )

    if (condition) {
      execBlock(ifStmt.thenBlock.stmtsList, env)
    } else {
      execBlock(ifStmt.elseBlock.stmtsList, env)
    }
  }

  private fun execSwitch(switchStmt: fourward.ir.v1.SwitchStmt, env: Environment) {
    val tableResult = applyTable(switchStmt.subject.tableApply.tableName, env)
    val matchedCase = switchStmt.casesList.find { it.actionName == tableResult.actionName }
    if (matchedCase != null) {
      execBlock(matchedCase.block.stmtsList, env)
    } else {
      execBlock(switchStmt.defaultBlock.stmtsList, env)
    }
  }

  // -------------------------------------------------------------------------
  // Expressions
  // -------------------------------------------------------------------------

  fun evalExpr(expr: Expr, env: Environment): Value =
    when {
      expr.hasLiteral() -> evalLiteral(expr.literal, expr.type)
      expr.hasNameRef() ->
        env.lookup(expr.nameRef.name) ?: error("undefined variable: ${expr.nameRef.name}")
      expr.hasFieldAccess() -> evalFieldAccess(expr.fieldAccess, env)
      expr.hasArrayIndex() -> evalArrayIndex(expr.arrayIndex, env)
      expr.hasSlice() -> evalSlice(expr.slice, env)
      expr.hasConcat() -> evalConcat(expr.concat, env)
      expr.hasCast() -> evalCast(expr.cast, env)
      expr.hasBinaryOp() -> evalBinaryOp(expr.binaryOp, env)
      expr.hasUnaryOp() -> evalUnaryOp(expr.unaryOp, env)
      expr.hasMethodCall() -> evalMethodCall(expr.methodCall, env)
      expr.hasMux() -> evalMux(expr.mux, env)
      expr.hasTableApply() -> {
        val result = applyTable(expr.tableApply.tableName, env)
        when (expr.tableApply.accessKind) {
          TableApplyExpr.AccessKind.HIT -> BoolVal(result.hit)
          TableApplyExpr.AccessKind.MISS -> BoolVal(!result.hit)
          else -> UnitVal // RESULT / default: switch context
        }
      }
      else -> error("unhandled expression kind: $expr")
    }

  private fun evalLiteral(lit: Literal, type: fourward.ir.v1.Type): Value =
    when {
      lit.hasBoolean() -> BoolVal(lit.boolean)
      lit.hasErrorMember() -> ErrorVal(lit.errorMember)
      lit.hasInteger() -> {
        val v = BigInteger.valueOf(lit.integer.toLong())
        if (type.hasBit()) BitVal(BitVector(v, type.bit.width))
        else InfIntVal(v) // compile-time constant integer (P4 spec §8.1)
      }
      lit.hasBigInteger() -> {
        val width = type.bit.width
        BitVal(BitVector(BigInteger(1, lit.bigInteger.toByteArray()), width))
      }
      else -> error("unhandled literal kind: $lit")
    }

  private fun evalFieldAccess(fa: fourward.ir.v1.FieldAccess, env: Environment): Value {
    // Special case: table.apply().hit / .miss — the p4c midend may restructure
    // the apply call such that the backend emits FieldAccess{TableApplyExpr, "hit"}
    // rather than TableApplyExpr{access_kind=HIT}.
    if (fa.expr.hasTableApply()) {
      val result = applyTable(fa.expr.tableApply.tableName, env)
      return when (fa.fieldName) {
        "hit" -> BoolVal(result.hit)
        "miss" -> BoolVal(!result.hit)
        else -> error("unknown field '${fa.fieldName}' on table apply result")
      }
    }
    val target = evalExpr(fa.expr, env)
    return when (target) {
      is HeaderVal ->
        target.fields[fa.fieldName]
          ?: error("field ${fa.fieldName} not found in header ${target.typeName}")
      is StructVal ->
        target.fields[fa.fieldName]
          ?: error("field ${fa.fieldName} not found in struct ${target.typeName}")
      is HeaderStackVal -> evalHeaderStackProperty(target, fa.fieldName)
      else -> error("field access on non-aggregate value: $target")
    }
  }

  /** P4 spec §8.18: header stack built-in properties. */
  private fun evalHeaderStackProperty(stack: HeaderStackVal, name: String): Value =
    when (name) {
      "next" -> {
        require(stack.nextIndex < stack.headers.size) {
          "header stack overflow: nextIndex=${stack.nextIndex}, size=${stack.headers.size}"
        }
        stack.headers[stack.nextIndex].also { stack.nextIndex++ }
      }
      "last" -> stack.headers[(stack.nextIndex - 1).coerceAtLeast(0)]
      "lastIndex" -> BitVal(stack.headers.size.toLong() - 1, 32)
      "size" -> BitVal(stack.headers.size.toLong(), 32)
      else -> error("unknown header stack property: $name")
    }

  private fun evalArrayIndex(ai: fourward.ir.v1.ArrayIndex, env: Environment): Value {
    val stack = evalExpr(ai.expr, env) as? HeaderStackVal ?: error("array index on non-stack value")
    val index = intValue(evalExpr(ai.index, env))
    return stack.headers[index]
  }

  private fun evalSlice(slice: fourward.ir.v1.Slice, env: Environment): Value {
    val bits = (evalExpr(slice.expr, env) as BitVal).bits
    return BitVal(bits.slice(slice.hi, slice.lo))
  }

  private fun evalConcat(concat: fourward.ir.v1.Concat, env: Environment): Value {
    val left = (evalExpr(concat.left, env) as BitVal).bits
    val right = (evalExpr(concat.right, env) as BitVal).bits
    return BitVal(left.concat(right))
  }

  private fun evalCast(cast: fourward.ir.v1.Cast, env: Environment): Value {
    val inner = evalExpr(cast.expr, env)
    return when {
      cast.targetType.hasBit() -> {
        val targetWidth = cast.targetType.bit.width
        val sourceBits =
          when (inner) {
            is BitVal -> inner.bits.value
            is InfIntVal -> inner.value
            is IntVal -> inner.bits.toUnsigned().value
            is BoolVal -> if (inner.value) BigInteger.ONE else BigInteger.ZERO
            else -> error("cannot cast $inner to bit<$targetWidth>")
          }
        BitVal(BitVector(sourceBits.mod(BigInteger.TWO.pow(targetWidth)), targetWidth))
      }
      cast.targetType.hasSignedInt() -> {
        val targetWidth = cast.targetType.signedInt.width
        val sourceBits =
          when (inner) {
            is BitVal -> inner.bits.value
            is InfIntVal -> inner.value
            else -> error("cannot cast $inner to int<$targetWidth>")
          }
        IntVal(SignedBitVector.fromUnsignedBits(sourceBits, targetWidth))
      }
      cast.targetType.boolean -> {
        val v =
          when (inner) {
            is BitVal -> inner.bits.value
            is InfIntVal -> inner.value
            else -> error("cannot cast $inner to bool")
          }
        BoolVal(v != BigInteger.ZERO)
      }
      else -> error("unsupported cast target type: ${cast.targetType}")
    }
  }

  private fun evalBinaryOp(op: fourward.ir.v1.BinaryOp, env: Environment): Value {
    // P4 spec §8.1: compile-time integers adopt the width of the other operand.
    val (left, right) = coerceInfInts(evalExpr(op.left, env), evalExpr(op.right, env))
    return when (op.op) {
      BinaryOperator.ADD -> BitVal((left as BitVal).bits + (right as BitVal).bits)
      BinaryOperator.SUB -> BitVal((left as BitVal).bits - (right as BitVal).bits)
      BinaryOperator.MUL -> BitVal((left as BitVal).bits * (right as BitVal).bits)
      BinaryOperator.DIV -> BitVal((left as BitVal).bits / (right as BitVal).bits)
      BinaryOperator.MOD -> BitVal((left as BitVal).bits % (right as BitVal).bits)
      BinaryOperator.ADD_SAT -> BitVal((left as BitVal).bits.addSat((right as BitVal).bits))
      BinaryOperator.SUB_SAT -> BitVal((left as BitVal).bits.subSat((right as BitVal).bits))
      BinaryOperator.BIT_AND -> BitVal((left as BitVal).bits and (right as BitVal).bits)
      BinaryOperator.BIT_OR -> BitVal((left as BitVal).bits or (right as BitVal).bits)
      BinaryOperator.BIT_XOR -> BitVal((left as BitVal).bits xor (right as BitVal).bits)
      BinaryOperator.SHL -> BitVal((left as BitVal).bits.shl((right as BitVal).bits.value.toInt()))
      BinaryOperator.SHR -> BitVal((left as BitVal).bits.shr((right as BitVal).bits.value.toInt()))
      BinaryOperator.EQ -> BoolVal(left == right)
      BinaryOperator.NEQ -> BoolVal(left != right)
      BinaryOperator.LT -> BoolVal((left as BitVal).bits < (right as BitVal).bits)
      BinaryOperator.GT -> BoolVal((left as BitVal).bits > (right as BitVal).bits)
      BinaryOperator.LE -> BoolVal((left as BitVal).bits <= (right as BitVal).bits)
      BinaryOperator.GE -> BoolVal((left as BitVal).bits >= (right as BitVal).bits)
      BinaryOperator.AND -> BoolVal((left as BoolVal).value && (right as BoolVal).value)
      BinaryOperator.OR -> BoolVal((left as BoolVal).value || (right as BoolVal).value)
      else -> error("unhandled binary operator: ${op.op}")
    }
  }

  /** Extract a small integer from a [BitVal] or [InfIntVal]. */
  private fun intValue(v: Value): Int =
    when (v) {
      is BitVal -> v.bits.value.toInt()
      is InfIntVal -> v.value.toInt()
      else -> error("expected integer value: $v")
    }

  /** P4 spec §8.1: InfInt adopts the width of the fixed-width operand. */
  private fun coerceInfInts(left: Value, right: Value): Pair<Value, Value> =
    when {
      left is InfIntVal && right is BitVal -> left.toBitVal(right.bits.width) to right
      right is InfIntVal && left is BitVal -> left to right.toBitVal((left as BitVal).bits.width)
      else -> left to right
    }

  private fun evalUnaryOp(op: fourward.ir.v1.UnaryOp, env: Environment): Value {
    val inner = evalExpr(op.expr, env)
    return when (op.op) {
      // Two's-complement negation: (2^N - x) mod 2^N = (0 - x) using wrapping subtraction.
      // BitVector.ofInt(-1, width) would violate the non-negative value invariant.
      UnaryOperator.NEG ->
        when (inner) {
          is InfIntVal -> InfIntVal(inner.value.negate())
          is BitVal -> BitVal(BitVector.ofInt(0, inner.bits.width) - inner.bits)
          else -> error("NEG on non-numeric: $inner")
        }
      UnaryOperator.BIT_NOT -> BitVal((inner as BitVal).bits.inv())
      UnaryOperator.NOT -> BoolVal(!(inner as BoolVal).value)
      else -> error("unhandled unary operator: ${op.op}")
    }
  }

  private fun evalMux(mux: fourward.ir.v1.MuxExpr, env: Environment): Value =
    if ((evalExpr(mux.condition, env) as BoolVal).value) evalExpr(mux.thenExpr, env)
    else evalExpr(mux.elseExpr, env)

  private fun evalMethodCall(call: MethodCall, env: Environment): Value {
    return when (call.method) {
      // Header validity methods: target is the header instance.
      "isValid" -> {
        when (val target = evalExpr(call.target, env)) {
          is HeaderVal -> BoolVal(target.valid)
          is StructVal -> BoolVal(target.isUnionValid())
          else -> error("isValid on non-header: $target")
        }
      }
      "setValid" -> {
        (evalExpr(call.target, env) as HeaderVal).valid = true
        UnitVal
      }
      "setInvalid" -> {
        when (val target = evalExpr(call.target, env)) {
          is HeaderVal -> target.setInvalid()
          is StructVal -> target.invalidateUnion()
          else -> error("setInvalid on non-header: $target")
        }
        UnitVal
      }
      // packet_in.extract(hdr) / packet_out.emit(hdr): target is the extern object
      // (not in env); the header is the first argument.
      "extract" -> execExtract(call, env)
      "emit" -> execEmit(call, env)
      // "__call__" is used for free functions and direct action calls. Check actions first;
      // fall back to extern handling (mark_to_drop, etc.) for unrecognised names.
      "__call__" -> {
        val funcName = call.target.nameRef.name
        if (funcName in actions) execInlineActionCall(funcName, call.argsList, env)
        else execExternCall(call, env)
      }
      else -> error("unhandled method call: ${call.method} on ${call.target}")
    }
  }

  // -------------------------------------------------------------------------
  // Table application
  // -------------------------------------------------------------------------

  private data class TableResult(val hit: Boolean, val actionName: String)

  private fun applyTable(tableName: String, env: Environment): TableResult {
    val tableBehavior = tables[tableName] ?: error("unknown table: $tableName")
    val keyValues = tableBehavior.keysList.map { key -> key.fieldName to evalExpr(key.expr, env) }

    val (hit, entry, actionName) = tableStore.lookup(tableName, keyValues)

    packetCtx?.addTraceEvent(
      TraceEvent.newBuilder()
        .setTableLookup(
          TableLookupEvent.newBuilder()
            .setTableName(tableName)
            .setHit(hit)
            .setActionName(actionName)
            .also { if (entry != null) it.setMatchedEntry(entry) }
        )
        .build()
    )

    execAction(actionName, entry?.action?.action?.paramsList ?: emptyList(), env)
    return TableResult(hit, actionName)
  }

  private fun execAction(
    actionName: String,
    paramProtos: List<p4.v1.P4RuntimeOuterClass.Action.Param>,
    env: Environment,
  ) {
    // NoAction is a P4 built-in no-op (P4 spec §12.7) that is implicitly available in
    // every table. It may appear in const entries without being listed in the actions block,
    // so it might not have been compiled into the IR. Handle it directly rather than
    // requiring the backend to emit an explicit empty ActionDecl for it.
    if (actionName == "NoAction") {
      packetCtx?.addTraceEvent(
        TraceEvent.newBuilder()
          .setActionExecution(ActionExecutionEvent.newBuilder().setActionName(actionName))
          .build()
      )
      return
    }

    val actionDecl = actions[actionName] ?: error("unknown action: $actionName")

    val paramMap = mutableMapOf<String, com.google.protobuf.ByteString>()

    env.pushScope()
    try {
      actionDecl.paramsList.forEachIndexed { i, paramDecl ->
        val paramProto = paramProtos.getOrNull(i)
        val value: Value =
          if (paramProto != null) {
            val width = paramDecl.type.bit.width
            paramMap[paramDecl.name] = paramProto.value
            BitVal(BitVector.ofBytes(paramProto.value.toByteArray(), width))
          } else {
            UnitVal
          }
        env.define(paramDecl.name, value)
      }

      packetCtx?.addTraceEvent(
        TraceEvent.newBuilder()
          .setActionExecution(
            ActionExecutionEvent.newBuilder()
              .setActionName(actionName)
              .putAllParams(paramMap.mapValues { it.value })
          )
          .build()
      )

      execBlock(actionDecl.bodyList, env)
    } finally {
      env.popScope()
    }
  }

  /**
   * Executes a direct (non-table-mediated) action call such as `do_thing(h.h.b)`.
   *
   * Binds argument values to the action's parameter names, runs the body, then writes back any
   * `inout`/`out` parameters to the corresponding call-site lvalues (call-by-value-result
   * semantics, as required by the P4 spec).
   */
  private fun execInlineActionCall(actionName: String, args: List<Expr>, env: Environment): Value {
    val actionDecl = actions[actionName]!!
    val argValues = args.map { evalExpr(it, env) }

    env.pushScope()
    try {
      actionDecl.paramsList.forEachIndexed { i, param ->
        env.define(param.name, argValues.getOrElse(i) { UnitVal })
      }
      try {
        execBlock(actionDecl.bodyList, env)
      } catch (_: ReturnException) {
        // P4 action return exits the body without a value; fall through to writeback.
      }
      // Write back inout/out parameters before the scope is popped.
      actionDecl.paramsList.forEachIndexed { i, param ->
        if (
          (param.direction == Direction.INOUT || param.direction == Direction.OUT) && i < args.size
        ) {
          setLValue(args[i], env.lookup(param.name)!!, env)
        }
      }
    } finally {
      env.popScope()
    }
    return UnitVal
  }

  // -------------------------------------------------------------------------
  // Extern function calls  (method == "__call__", target is a NameRef)
  // -------------------------------------------------------------------------

  private fun execExternCall(call: MethodCall, env: Environment): Value {
    val funcName = call.target.nameRef.name
    return when (funcName) {
      // mark_to_drop(standard_metadata): sets egress_spec to the v1model drop port (511).
      "mark_to_drop" -> {
        val smeta = evalExpr(call.argsList[0], env) as StructVal
        smeta.fields["egress_spec"] =
          BitVal(V1ModelArchitecture.DROP_PORT.toLong(), V1ModelArchitecture.PORT_BITS)
        UnitVal
      }
      else -> error("unhandled extern call: $funcName")
    }
  }

  // -------------------------------------------------------------------------
  // Packet extract / emit
  // -------------------------------------------------------------------------

  private fun execExtract(call: MethodCall, env: Environment): Value {
    // packet_in.extract(hdr.field): the header to extract into is args[0], not the target.
    // P4's don't-care extract `p.extract<T>(_)` compiles to extract(arg) where
    // `arg` is an undeclared temporary. Create a throw-away header to consume bytes.
    val arg = call.argsList[0]
    val header =
      if (arg.hasNameRef() && env.lookup(arg.nameRef.name) == null && arg.type.hasNamed()) {
        defaultValue(arg.type.named, types) as? HeaderVal
          ?: error("type not found for don't-care extract: ${arg.type.named}")
      } else {
        evalExpr(arg, env) as HeaderVal
      }
    val headerDecl = (types[header.typeName] ?: error("type not found: ${header.typeName}")).header

    // The 2-argument form b.extract(hdr, varbitBits) is used when the header contains a varbit
    // field. The second argument gives the varbit field's runtime length in bits.
    val varbitBits: Int =
      if (call.argsCount > 1) (evalExpr(call.argsList[1], env) as BitVal).bits.value.toInt() else 0

    // Read the entire header at once and load it into a single BigInteger (MSB-first).
    // This handles sub-byte fields (e.g. IPv4's bit<4> version and ihl) correctly:
    // both fields live in the same byte and must be unpacked by shift+mask, not by
    // reading separate bytes.
    val totalBits = headerDecl.fieldsList.sumOf { fieldWireWidth(it.type, varbitBits) }
    val allBits = BigInteger(1, packet.extractBytes((totalBits + 7) / 8))

    val newFields = mutableMapOf<String, Value>()
    var bitOffset = 0
    for (field in headerDecl.fieldsList) {
      val width = fieldWireWidth(field.type, varbitBits)
      if (width == 0) continue // skip zero-width fields (unrecognised types)
      val mask = BigInteger.ONE.shiftLeft(width) - BigInteger.ONE
      val raw = (allBits shr (totalBits - bitOffset - width)) and mask
      newFields[field.name] = bitsToValue(field.type, raw, width)
      bitOffset += width
    }
    header.setValid(newFields)
    return UnitVal
  }

  /**
   * Returns the on-wire bit-width of a header field type.
   *
   * P4 spec §8.9.2: bool in a header occupies exactly 1 bit on the wire. int<N> and bit<N> occupy N
   * bits. varbit<N> occupies [varbitBits] bits (caller must supply the runtime value). Serializable
   * enums are looked up by name and use their declared underlying width.
   */
  private fun fieldWireWidth(type: Type, varbitBits: Int = 0): Int =
    when {
      type.hasBit() -> type.bit.width
      type.hasSignedInt() -> type.signedInt.width
      type.hasBoolean() -> 1
      type.hasVarbit() -> varbitBits
      type.hasNamed() -> {
        val decl = types[type.named]
        when {
          decl != null && decl.hasEnum() -> decl.enum.width
          else -> 0
        }
      }
      else -> 0
    }

  /**
   * Converts raw extracted bits to the appropriate [Value] for a header field.
   *
   * bit<N> → [BitVal], bool → [BoolVal], int<N> → [IntVal], varbit / enum → [BitVal].
   */
  private fun bitsToValue(type: Type, raw: BigInteger, width: Int): Value =
    when {
      type.hasBoolean() -> BoolVal(raw != BigInteger.ZERO)
      type.hasSignedInt() -> IntVal(SignedBitVector.fromUnsignedBits(raw, width))
      else -> BitVal(BitVector(raw, width))
    }

  private fun execEmit(call: MethodCall, env: Environment): Value {
    emitValue(evalExpr(call.argsList[0], env))
    return UnitVal
  }

  /**
   * Recursively emits a value to the packet output buffer.
   * - [HeaderVal]: packs all fields into bytes (MSB-first) and appends if valid.
   * - [StructVal]: iterates fields in declaration order (looking up the TypeDecl) and emits each.
   * - Other types: no-op (non-emittable values such as BoolVal in metadata structs).
   *
   * This handles both `pkt.emit(hdr.ethernet)` (single header) and `pkt.emit(hdr)` where `hdr` is a
   * struct containing multiple headers, as required by the P4 deparser model.
   */
  private fun emitValue(value: Value) {
    when (value) {
      is HeaderVal -> {
        if (!value.valid) return
        val headerDecl =
          (types[value.typeName] ?: error("type not found: ${value.typeName}")).header
        // Compute total wire bits from field declarations; varbit fields use their stored BitVal
        // width since we don't have the runtime length separately at emit time.
        val totalBits =
          headerDecl.fieldsList.sumOf { field ->
            when (val v = value.fields[field.name]) {
              is BitVal -> v.bits.width
              is BoolVal -> 1
              is IntVal -> v.bits.width
              else -> 0
            }
          }
        var packedBits = BigInteger.ZERO
        var bitOffset = 0
        for (field in headerDecl.fieldsList) {
          val fieldBits: BigInteger
          val width: Int
          when (val fieldVal = value.fields[field.name]) {
            is BitVal -> {
              fieldBits = fieldVal.bits.value
              width = fieldVal.bits.width
            }
            is BoolVal -> {
              // P4 spec §8.9.2: bool occupies 1 bit on the wire.
              fieldBits = if (fieldVal.value) BigInteger.ONE else BigInteger.ZERO
              width = 1
            }
            is IntVal -> {
              fieldBits = fieldVal.bits.toUnsigned().value
              width = fieldVal.bits.width
            }
            else -> continue // UnitVal (e.g. varbit placeholder) — skip
          }
          if (totalBits > 0 && width > 0) {
            val shift = totalBits - bitOffset - width
            packedBits = packedBits or fieldBits.shiftLeft(shift)
          }
          bitOffset += width
        }
        if (totalBits > 0) packet.emitBytes(BitVector(packedBits, totalBits).toByteArray())
      }
      is StructVal -> {
        // Emit in the declaration order from the TypeDecl; fall back to map order if unknown.
        val typeDecl = types[value.typeName]
        val fieldDecls =
          when {
            typeDecl != null && typeDecl.hasStruct() -> typeDecl.struct.fieldsList
            typeDecl != null && typeDecl.hasHeaderUnion() -> typeDecl.headerUnion.fieldsList
            else -> null
          }
        if (fieldDecls != null) {
          for (field in fieldDecls) {
            emitValue(value.fields[field.name] ?: continue)
          }
        } else {
          for (fieldVal in value.fields.values) emitValue(fieldVal)
        }
      }
      is HeaderStackVal -> {
        for (header in value.headers) emitValue(header)
      }
      else -> {} // BoolVal, BitVal outside a header, UnitVal — not emittable
    }
  }

  // -------------------------------------------------------------------------
  // LValue assignment
  // -------------------------------------------------------------------------

  private fun setLValue(lhs: Expr, value: Value, env: Environment) {
    when {
      lhs.hasNameRef() -> {
        // P4 assignment is copy-by-value. Headers and structs carry a mutable fields map,
        // so we copy them here to prevent aliasing (e.g. `x = h.h; x.a = 2` must not
        // modify `h.h.a`).
        val copy =
          when (value) {
            is HeaderVal -> value.copy()
            is StructVal -> value.copy()
            else -> value
          }
        env.update(lhs.nameRef.name, copy)
      }
      lhs.hasFieldAccess() -> {
        val target = evalExpr(lhs.fieldAccess.expr, env)
        when (target) {
          is HeaderVal -> target.fields[lhs.fieldAccess.fieldName] = value
          is StructVal -> target.fields[lhs.fieldAccess.fieldName] = value
          else -> error("field assignment on non-aggregate: $target")
        }
      }
      lhs.hasArrayIndex() -> {
        val stack = evalExpr(lhs.arrayIndex.expr, env) as HeaderStackVal
        val index = intValue(evalExpr(lhs.arrayIndex.index, env))
        val copy = if (value is HeaderVal) value.copy() else value
        stack.headers[index] = copy as HeaderVal
      }
      lhs.hasSlice() -> {
        // Slice assignment: update [hi:lo] bits of the target.
        val target = evalExpr(lhs.slice.expr, env) as BitVal
        val src = value as BitVal
        val hi = lhs.slice.hi
        val lo = lhs.slice.lo
        val mask =
          BitVector(
            BigInteger.TWO.pow(hi - lo + 1).minus(BigInteger.ONE).shiftLeft(lo),
            target.bits.width,
          )
        val shifted = BitVector(src.bits.value.shiftLeft(lo), target.bits.width)
        val result = (target.bits and mask.inv()) or (shifted and mask)
        setLValue(lhs.slice.expr, BitVal(result), env)
      }
      else -> error("unhandled lvalue kind: $lhs")
    }
  }
}

/**
 * Thrown by an `exit` statement; unwinds the call stack to the top of the current pipeline stage.
 */
class ExitException : Exception()

/** Thrown by a `return` statement inside an action or function. */
class ReturnException(val value: Value) : Exception()
