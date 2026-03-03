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

import fourward.ir.v1.AssignmentStmt
import fourward.ir.v1.BitType
import fourward.ir.v1.Expr
import fourward.ir.v1.Literal
import fourward.ir.v1.NameRef
import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.ParserDecl
import fourward.ir.v1.ParserState
import fourward.ir.v1.Stmt
import fourward.ir.v1.Transition
import fourward.ir.v1.Type
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [Interpreter.runParser] state machine traversal.
 *
 * Verifies that the iterative parser correctly visits states in order, executes statements in each
 * state, and halts at the "accept" and "reject" terminal states without treating them as user
 * states.
 */
class InterpreterParserTest {

  private fun bitType(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

  private fun bit(value: Long, width: Int): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setInteger(value))
      .setType(bitType(width))
      .build()

  private fun assign(varName: String, value: Expr): Stmt =
    Stmt.newBuilder()
      .setAssignment(
        AssignmentStmt.newBuilder()
          .setLhs(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(varName)))
          .setRhs(value)
      )
      .build()

  private fun state(name: String, nextState: String, vararg stmts: Stmt): ParserState =
    ParserState.newBuilder()
      .setName(name)
      .setTransition(Transition.newBuilder().setNextState(nextState))
      .addAllStmts(stmts.toList())
      .build()

  private fun interp(vararg states: ParserState): Interpreter {
    val parser = ParserDecl.newBuilder().setName("MyParser").addAllStates(states.toList()).build()
    return Interpreter(P4BehavioralConfig.newBuilder().addParsers(parser).build(), TableStore())
  }

  @Test
  fun `single-state parser executes stmts and halts at accept`() {
    val env = Environment(byteArrayOf())
    env.define("x", BitVal(0, 8))

    interp(state("start", "accept", assign("x", bit(1, 8)))).runParser("MyParser", env)

    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `two-state parser traverses states in order`() {
    // start(x=1) → middle(x=2) → accept: both states must run.
    val env = Environment(byteArrayOf())
    env.define("x", BitVal(0, 8))

    interp(
        state("start", "middle", assign("x", bit(1, 8))),
        state("middle", "accept", assign("x", bit(2, 8))),
      )
      .runParser("MyParser", env)

    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `three-state chain visits all states`() {
    // start(x=1) → s1(x=2) → s2(x=3) → accept
    val env = Environment(byteArrayOf())
    env.define("x", BitVal(0, 8))

    interp(
        state("start", "s1", assign("x", bit(1, 8))),
        state("s1", "s2", assign("x", bit(2, 8))),
        state("s2", "accept", assign("x", bit(3, 8))),
      )
      .runParser("MyParser", env)

    assertEquals(BitVal(3, 8), env.lookup("x"))
  }

  @Test
  fun `parser stops at reject without error`() {
    // reject is a terminal state, not an exception — the pipeline drops the packet separately.
    val env = Environment(byteArrayOf())
    interp(state("start", "reject")).runParser("MyParser", env)
  }
}
