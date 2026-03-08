package fourward.simulator

import fourward.ir.v1.AssignmentStmt
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.BitType
import fourward.ir.v1.BlockStmt
import fourward.ir.v1.ControlDecl
import fourward.ir.v1.Expr
import fourward.ir.v1.IfStmt
import fourward.ir.v1.Literal
import fourward.ir.v1.MethodCall
import fourward.ir.v1.MethodCallStmt
import fourward.ir.v1.NameRef
import fourward.ir.v1.SourceInfo
import fourward.ir.v1.Stmt
import fourward.ir.v1.Type

/**
 * Shared proto-building helpers for interpreter unit tests.
 *
 * Extracted from the individual `Interpreter*Test.kt` files where these were duplicated as private
 * functions with identical or near-identical implementations.
 */

/** Unsigned integer literal of [value] in [width] bits. */
fun bit(value: Long, width: Int): Expr =
  Expr.newBuilder()
    .setLiteral(Literal.newBuilder().setInteger(value))
    .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)))
    .build()

fun boolLit(v: Boolean): Expr =
  Expr.newBuilder()
    .setLiteral(Literal.newBuilder().setBoolean(v))
    .setType(Type.newBuilder().setBoolean(true))
    .build()

fun nameRef(name: String, type: Type? = null): Expr =
  Expr.newBuilder()
    .setNameRef(NameRef.newBuilder().setName(name))
    .apply { if (type != null) setType(type) }
    .build()

fun bitType(width: Int): Type =
  Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

fun namedType(name: String): Type = Type.newBuilder().setNamed(name).build()

fun ifStmt(
  condition: Expr,
  thenStmts: List<Stmt> = emptyList(),
  elseStmts: List<Stmt> = emptyList(),
  sourceInfo: SourceInfo? = null,
): Stmt {
  val builder =
    Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(condition)
          .setThenBlock(BlockStmt.newBuilder().addAllStmts(thenStmts))
          .setElseBlock(BlockStmt.newBuilder().addAllStmts(elseStmts))
      )
  if (sourceInfo != null) builder.sourceInfo = sourceInfo
  return builder.build()
}

/** Assignment statement: `varName = rhs`. */
fun assign(varName: String, rhs: Expr): Stmt =
  Stmt.newBuilder()
    .setAssignment(
      AssignmentStmt.newBuilder()
        .setLhs(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(varName)))
        .setRhs(rhs)
    )
    .build()

/**
 * Statement that calls a free extern function: `name(args...)`.
 *
 * In the IR, free function calls use `"__call__"` as the method name (as opposed to instance method
 * calls like `register.read()`).
 */
fun externCall(name: String, vararg args: Expr): Stmt = methodCallStmt(name, "__call__", *args)

/** Statement that calls `target.method(args...)` — for extern method calls. */
fun methodCallStmt(
  target: String,
  method: String,
  vararg args: Expr,
  targetType: Type? = null,
): Stmt =
  Stmt.newBuilder()
    .setMethodCall(
      MethodCallStmt.newBuilder()
        .setCall(
          Expr.newBuilder()
            .setMethodCall(
              MethodCall.newBuilder()
                .setTarget(nameRef(target, targetType))
                .setMethod(method)
                .addAllArgs(args.toList())
            )
        )
    )
    .build()

/** Builds a config with a single control named "MyControl" whose apply body is [stmts]. */
fun controlConfig(vararg stmts: Stmt): BehavioralConfig = controlConfig("MyControl", *stmts)

/** Builds a config with a single control named [controlName] whose apply body is [stmts]. */
fun controlConfig(controlName: String, vararg stmts: Stmt): BehavioralConfig =
  BehavioralConfig.newBuilder()
    .addControls(ControlDecl.newBuilder().setName(controlName).addAllApplyBody(stmts.toList()))
    .build()
