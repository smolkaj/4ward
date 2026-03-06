// Copyright 2026 4ward Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fourward.simulator

import fourward.ir.v1.ActionDecl
import fourward.ir.v1.AssignmentStmt
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.BitType
import fourward.ir.v1.Direction
import fourward.ir.v1.Expr
import fourward.ir.v1.Literal
import fourward.ir.v1.MethodCall
import fourward.ir.v1.NameRef
import fourward.ir.v1.ParamDecl
import fourward.ir.v1.Stmt
import fourward.ir.v1.Type
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for inline action calls.
 *
 * P4 uses call-by-value-result for action parameters: [inout] and [out] params are written back to
 * the call-site l-value after the action body executes; [in] params are not.
 */
class InterpreterInlineActionTest {

  private val emptyEnv
    get() = Environment()

  private fun bitType(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

  private fun bit(value: Long, width: Int): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setInteger(value))
      .setType(bitType(width))
      .build()

  private fun nameRef(name: String): Expr =
    Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(name)).build()

  /** Builds `actionName(args...)` as a `__call__` method-call expression. */
  private fun callExpr(actionName: String, vararg args: Expr): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef(actionName))
          .setMethod("__call__")
          .addAllArgs(args.toList())
      )
      .build()

  private fun assignParam(paramName: String, value: Expr): Stmt =
    Stmt.newBuilder()
      .setAssignment(
        AssignmentStmt.newBuilder()
          .setLhs(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(paramName)))
          .setRhs(value)
      )
      .build()

  private fun interp(vararg actions: ActionDecl): Interpreter {
    val config =
      BehavioralConfig.newBuilder().also { cfg -> actions.forEach { cfg.addActions(it) } }.build()
    return Interpreter(config, TableStore())
  }

  @Test
  fun `inout param writeback updates caller variable`() {
    val action =
      ActionDecl.newBuilder()
        .setName("set99")
        .addParams(
          ParamDecl.newBuilder().setName("x").setType(bitType(8)).setDirection(Direction.INOUT)
        )
        .addBody(assignParam("x", bit(99, 8)))
        .build()
    val env = emptyEnv
    env.define("myVar", BitVal(0, 8))

    interp(action).evalExpr(callExpr("set99", nameRef("myVar")), env)

    assertEquals(BitVal(99, 8), env.lookup("myVar"))
  }

  @Test
  fun `in param changes are not written back to caller`() {
    val action =
      ActionDecl.newBuilder()
        .setName("modify_in")
        .addParams(
          ParamDecl.newBuilder().setName("x").setType(bitType(8)).setDirection(Direction.IN)
        )
        .addBody(assignParam("x", bit(99, 8)))
        .build()
    val env = emptyEnv
    env.define("myVar", BitVal(0, 8))

    interp(action).evalExpr(callExpr("modify_in", nameRef("myVar")), env)

    assertEquals(BitVal(0, 8), env.lookup("myVar"))
  }

  @Test
  fun `out param is written back to caller`() {
    val action =
      ActionDecl.newBuilder()
        .setName("init")
        .addParams(
          ParamDecl.newBuilder().setName("x").setType(bitType(8)).setDirection(Direction.OUT)
        )
        .addBody(assignParam("x", bit(42, 8)))
        .build()
    val env = emptyEnv
    env.define("result", BitVal(0, 8))

    interp(action).evalExpr(callExpr("init", nameRef("result")), env)

    assertEquals(BitVal(42, 8), env.lookup("result"))
  }

  @Test
  fun `only inout params are written back when action has mixed directions`() {
    // set(in bit<8> a, inout bit<8> b): b = 42.
    // After call(a=x, b=y): x stays unchanged (in), y becomes 42 (inout).
    val action =
      ActionDecl.newBuilder()
        .setName("set")
        .addParams(
          ParamDecl.newBuilder().setName("a").setType(bitType(8)).setDirection(Direction.IN)
        )
        .addParams(
          ParamDecl.newBuilder().setName("b").setType(bitType(8)).setDirection(Direction.INOUT)
        )
        .addBody(assignParam("b", bit(42, 8)))
        .build()
    val env = emptyEnv
    env.define("x", BitVal(7, 8))
    env.define("y", BitVal(0, 8))

    interp(action).evalExpr(callExpr("set", nameRef("x"), nameRef("y")), env)

    assertEquals(BitVal(7, 8), env.lookup("x")) // in — not written back
    assertEquals(BitVal(42, 8), env.lookup("y")) // inout — written back
  }
}
