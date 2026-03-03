package fourward.simulator

import fourward.ir.v1.BinaryOperator
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.Expr
import fourward.ir.v1.Literal
import fourward.ir.v1.MethodCall
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.ParserDecl
import fourward.ir.v1.Stmt
import fourward.ir.v1.TableBehavior
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
 * Walks the proto IR tree for a single packet traversal. All mutable packet-local state lives in
 * the [Environment]; program-global state (table entries, extern instances) lives in [TableStore]
 * and is passed in.
 *
 * The interpreter is deliberately simple: it pattern-matches on proto oneof fields and dispatches
 * to focused methods. There is no bytecode compilation or optimisation — correctness and
 * readability are the goals.
 */
class Interpreter(private val config: P4BehavioralConfig, private val tableStore: TableStore) {
  private val parsers: Map<String, ParserDecl> = config.parsersList.associateBy { it.name }

  private val controls: Map<String, ControlDecl> = config.controlsList.associateBy { it.name }

  private val actions: Map<String, fourward.ir.v1.ActionDecl> =
    config.actionsList.associateBy { it.name }

  private val tables: Map<String, TableBehavior> = config.tablesList.associateBy { it.name }

  // -------------------------------------------------------------------------
  // Parser
  // -------------------------------------------------------------------------

  fun runParser(parserName: String, env: Environment) {
    val parser = parsers[parserName] ?: error("unknown parser: $parserName")
    runParserState(parser, "start", env)
  }

  private fun runParserState(parser: ParserDecl, stateName: String, env: Environment) {
    if (stateName == "accept" || stateName == "reject") return

    val state =
      parser.statesList.find { it.name == stateName }
        ?: error("unknown parser state: $stateName in ${parser.name}")

    for (stmt in state.stmtsList) {
      execStmt(stmt, env)
    }

    val nextState =
      when {
        state.transition.hasSelect() -> evalSelect(state.transition.select, env)
        else -> state.transition.nextState
      }

    env.addTraceEvent(
      TraceEvent.newBuilder()
        .setParserTransition(
          ParserTransitionEvent.newBuilder()
            .setParserName(parser.name)
            .setFromState(stateName)
            .setToState(nextState)
        )
        .build()
    )

    runParserState(parser, nextState, env)
  }

  private fun evalSelect(select: fourward.ir.v1.SelectTransition, env: Environment): String {
    val keyValues = select.keysList.map { evalExpr(it, env) }

    for (case in select.casesList) {
      if (keyValues.zip(case.keysetsList).all { (v, k) -> matchesKeyset(v, k) }) {
        return case.nextState
      }
    }
    return select.defaultState
  }

  private fun matchesKeyset(value: Value, keyset: fourward.ir.v1.KeysetExpr): Boolean =
    when {
      keyset.hasDefaultCase() -> true
      keyset.hasExact() -> value == evalExpr(keyset.exact, Environment(byteArrayOf()))
      keyset.hasMask() -> {
        val v = (value as BitVal).bits
        val mask = (evalExpr(keyset.mask.mask, Environment(byteArrayOf())) as BitVal).bits
        val want = (evalExpr(keyset.mask.value, Environment(byteArrayOf())) as BitVal).bits
        (v and mask) == (want and mask)
      }
      keyset.hasRange() -> {
        val v = (value as BitVal).bits
        val lo = (evalExpr(keyset.range.lo, Environment(byteArrayOf())) as BitVal).bits
        val hi = (evalExpr(keyset.range.hi, Environment(byteArrayOf())) as BitVal).bits
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
        val init = if (varDecl.hasInitializer()) evalExpr(varDecl.initializer, env) else UnitVal
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
    env.addTraceEvent(
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
      expr.hasTableApply() -> applyTable(expr.tableApply.tableName, env).let { UnitVal }
      else -> error("unhandled expression kind: $expr")
    }

  private fun evalLiteral(lit: Literal, type: fourward.ir.v1.Type): Value =
    when {
      lit.hasBoolean() -> BoolVal(lit.boolean)
      lit.hasErrorMember() -> ErrorVal(lit.errorMember)
      lit.hasInteger() -> {
        val width = if (type.hasBit()) type.bit.width else error("integer literal without bit type")
        BitVal(BitVector(BigInteger.valueOf(lit.integer.toLong()), width))
      }
      lit.hasBigInteger() -> {
        val width = type.bit.width
        BitVal(BitVector(BigInteger(1, lit.bigInteger.toByteArray()), width))
      }
      else -> error("unhandled literal kind: $lit")
    }

  private fun evalFieldAccess(fa: fourward.ir.v1.FieldAccess, env: Environment): Value {
    val target = evalExpr(fa.expr, env)
    return when (target) {
      is HeaderVal ->
        target.fields[fa.fieldName]
          ?: error("field ${fa.fieldName} not found in header ${target.typeName}")
      is StructVal ->
        target.fields[fa.fieldName]
          ?: error("field ${fa.fieldName} not found in struct ${target.typeName}")
      else -> error("field access on non-aggregate value: $target")
    }
  }

  private fun evalArrayIndex(ai: fourward.ir.v1.ArrayIndex, env: Environment): Value {
    val stack = evalExpr(ai.expr, env) as? HeaderStackVal ?: error("array index on non-stack value")
    val index = (evalExpr(ai.index, env) as BitVal).bits.value.toInt()
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
            is IntVal -> inner.bits.toUnsigned().value
            is BoolVal -> if (inner.value) BigInteger.ONE else BigInteger.ZERO
            else -> error("cannot cast $inner to bit<$targetWidth>")
          }
        BitVal(BitVector(sourceBits.mod(BigInteger.TWO.pow(targetWidth)), targetWidth))
      }
      cast.targetType.hasSignedInt() -> {
        val targetWidth = cast.targetType.signedInt.width
        val sourceBits = (inner as BitVal).bits.value
        IntVal(SignedBitVector.fromUnsignedBits(sourceBits, targetWidth))
      }
      cast.targetType.boolean -> {
        val bits = (inner as BitVal).bits
        BoolVal(bits.value != BigInteger.ZERO)
      }
      else -> error("unsupported cast target type: ${cast.targetType}")
    }
  }

  private fun evalBinaryOp(op: fourward.ir.v1.BinaryOp, env: Environment): Value {
    val left = evalExpr(op.left, env)
    val right = evalExpr(op.right, env)
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

  private fun evalUnaryOp(op: fourward.ir.v1.UnaryOp, env: Environment): Value {
    val inner = evalExpr(op.expr, env)
    return when (op.op) {
      UnaryOperator.NEG -> BitVal((inner as BitVal).bits * BitVector.ofInt(-1, inner.bits.width))
      UnaryOperator.BIT_NOT -> BitVal((inner as BitVal).bits.inv())
      UnaryOperator.NOT -> BoolVal(!(inner as BoolVal).value)
      else -> error("unhandled unary operator: ${op.op}")
    }
  }

  private fun evalMethodCall(call: MethodCall, env: Environment): Value {
    return when (call.method) {
      // Header validity methods: target is the header instance.
      "isValid" -> BoolVal((evalExpr(call.target, env) as HeaderVal).valid)
      "setValid" -> {
        (evalExpr(call.target, env) as HeaderVal).valid = true
        UnitVal
      }
      "setInvalid" -> {
        (evalExpr(call.target, env) as HeaderVal).setInvalid()
        UnitVal
      }
      // packet_in.extract(hdr) / packet_out.emit(hdr): target is the extern object
      // (not in env); the header is the first argument.
      "extract" -> execExtract(call, env)
      "emit" -> execEmit(call, env)
      else -> error("unhandled method call: ${call.method} on ${evalExpr(call.target, env)}")
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

    env.addTraceEvent(
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

      env.addTraceEvent(
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

  // -------------------------------------------------------------------------
  // Packet extract / emit
  // -------------------------------------------------------------------------

  private fun execExtract(call: MethodCall, env: Environment): Value {
    // packet_in.extract(hdr.field): the header to extract into is args[0], not the target.
    val header = evalExpr(call.argsList[0], env) as HeaderVal
    val typeName = header.typeName
    val typeDecl =
      config.typesList.find { it.name == typeName } ?: error("type not found: $typeName")
    val headerDecl = typeDecl.header

    val newFields = mutableMapOf<String, Value>()
    for (field in headerDecl.fieldsList) {
      val width = field.type.bit.width
      val bytes = env.extractBytes((width + BitVector.BITS_PER_BYTE - 1) / BitVector.BITS_PER_BYTE)
      newFields[field.name] = BitVal(BitVector.ofBytes(bytes, width))
    }
    header.setValid(newFields)
    return UnitVal
  }

  private fun execEmit(call: MethodCall, env: Environment): Value {
    // packet_out.emit(hdr.field): the header to emit is args[0], not the target.
    val header = evalExpr(call.argsList[0], env) as HeaderVal
    if (!header.valid) return UnitVal // invalid headers are not emitted

    val typeName = header.typeName
    val typeDecl =
      config.typesList.find { it.name == typeName } ?: error("type not found: $typeName")

    for (field in typeDecl.header.fieldsList) {
      val value = header.fields[field.name] as BitVal
      env.emitBytes(value.bits.toByteArray())
    }
    return UnitVal
  }

  // -------------------------------------------------------------------------
  // LValue assignment
  // -------------------------------------------------------------------------

  private fun setLValue(lhs: Expr, value: Value, env: Environment) {
    when {
      lhs.hasNameRef() -> env.update(lhs.nameRef.name, value)
      lhs.hasFieldAccess() -> {
        val target = evalExpr(lhs.fieldAccess.expr, env)
        when (target) {
          is HeaderVal -> target.fields[lhs.fieldAccess.fieldName] = value
          is StructVal -> target.fields[lhs.fieldAccess.fieldName] = value
          else -> error("field assignment on non-aggregate: $target")
        }
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
