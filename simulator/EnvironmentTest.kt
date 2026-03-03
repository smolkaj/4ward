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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [Environment]: variable scoping, packet buffer, and output buffer. */
class EnvironmentTest {

  // ---------------------------------------------------------------------------
  // Variable bindings
  // ---------------------------------------------------------------------------

  @Test
  fun `lookup returns null for undefined name`() {
    val env = Environment(byteArrayOf())
    assertNull(env.lookup("x"))
  }

  @Test
  fun `define and lookup in same scope`() {
    val env = Environment(byteArrayOf())
    env.define("x", BitVal(7, 8))
    assertEquals(BitVal(7, 8), env.lookup("x"))
  }

  @Test
  fun `inner scope shadows outer binding`() {
    val env = Environment(byteArrayOf())
    env.define("x", BitVal(1, 8))
    env.pushScope()
    env.define("x", BitVal(99, 8))
    assertEquals(BitVal(99, 8), env.lookup("x"))
    env.popScope()
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `inner scope variable is not visible after popScope`() {
    val env = Environment(byteArrayOf())
    env.pushScope()
    env.define("inner", BitVal(5, 8))
    env.popScope()
    assertNull(env.lookup("inner"))
  }

  @Test
  fun `update modifies variable in the nearest enclosing scope`() {
    val env = Environment(byteArrayOf())
    env.define("x", BitVal(1, 8))
    env.pushScope()
    env.update("x", BitVal(2, 8))
    assertEquals(BitVal(2, 8), env.lookup("x"))
    env.popScope()
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `update throws for undefined variable`() {
    val env = Environment(byteArrayOf())
    assertThrows(IllegalStateException::class.java) { env.update("missing", BitVal(0, 8)) }
  }

  // ---------------------------------------------------------------------------
  // Packet buffer (input)
  // ---------------------------------------------------------------------------

  @Test
  fun `extractBytes reads exactly N bytes from the front of the packet`() {
    val env = Environment(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    assertArrayEquals(byteArrayOf(0x01, 0x02), env.extractBytes(2))
  }

  @Test
  fun `extractBytes advances the cursor so the next call gets the next bytes`() {
    val env = Environment(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    env.extractBytes(2)
    assertArrayEquals(byteArrayOf(0x03, 0x04), env.extractBytes(2))
  }

  @Test
  fun `drainRemainingInput returns bytes not yet extracted`() {
    val env = Environment(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    env.extractBytes(1)
    assertArrayEquals(byteArrayOf(0x02, 0x03, 0x04), env.drainRemainingInput())
  }

  @Test
  fun `extractBytes throws when fewer bytes remain than requested`() {
    val env = Environment(byteArrayOf(0x01))
    assertThrows(IllegalArgumentException::class.java) { env.extractBytes(2) }
  }

  // ---------------------------------------------------------------------------
  // Output buffer
  // ---------------------------------------------------------------------------

  @Test
  fun `outputPayload is empty before any emitBytes call`() {
    val env = Environment(byteArrayOf())
    assertArrayEquals(byteArrayOf(), env.outputPayload())
  }

  @Test
  fun `emitBytes appends bytes to the output buffer`() {
    val env = Environment(byteArrayOf())
    env.emitBytes(byteArrayOf(0x08, 0x00))
    assertArrayEquals(byteArrayOf(0x08, 0x00), env.outputPayload())
  }

  @Test
  fun `multiple emitBytes calls concatenate in order`() {
    val env = Environment(byteArrayOf())
    env.emitBytes(byteArrayOf(0x01, 0x02))
    env.emitBytes(byteArrayOf(0x03, 0x04))
    assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), env.outputPayload())
  }
}
