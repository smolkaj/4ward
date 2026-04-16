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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concurrency tests for [NetworkSimulator]. The switch map was changed from `mutableMapOf` to
 * `ConcurrentHashMap` and `addSwitch` to `putIfAbsent`; these tests verify the contract.
 */
class NetworkSimulatorTest {

  @Test
  fun `concurrent addSwitch with distinct ids all succeed`() {
    val network = NetworkSimulator(NetworkTopology(links = emptyList()))
    val errors = ConcurrentLinkedQueue<Throwable>()
    val latch = CountDownLatch(1)
    val n = 32

    val workers =
      (0 until n).map { i ->
        Thread {
            try {
              latch.await()
              val sim = network.addSwitch("s$i")
              assertNotNull(sim)
            } catch (
              @Suppress("TooGenericExceptionCaught")
              t: Throwable) { // any failure is a race symptom
              errors.add(t)
            }
          }
          .also { it.start() }
      }
    latch.countDown()
    workers.forEach { it.join() }

    assertTrue("no errors expected: ${errors.firstOrNull()}", errors.isEmpty())
  }

  @Test
  fun `concurrent addSwitch with duplicate id elects exactly one winner`() {
    val network = NetworkSimulator(NetworkTopology(links = emptyList()))
    val n = 32
    val successes = ConcurrentLinkedQueue<Simulator>()
    val rejections = ConcurrentLinkedQueue<Throwable>()
    val unexpected = ConcurrentLinkedQueue<Throwable>()
    val latch = CountDownLatch(1)

    val workers =
      (0 until n).map {
        Thread {
            try {
              latch.await()
              successes.add(network.addSwitch("dup"))
            } catch (e: IllegalArgumentException) {
              rejections.add(e)
            } catch (
              @Suppress("TooGenericExceptionCaught")
              t: Throwable) { // any other failure is a race symptom
              unexpected.add(t)
            }
          }
          .also { it.start() }
      }
    latch.countDown()
    workers.forEach { it.join() }

    assertTrue("unexpected exceptions: ${unexpected.firstOrNull()}", unexpected.isEmpty())
    assertEquals("exactly one addSwitch should succeed", 1, successes.size)
    assertEquals("the rest should be rejected as duplicate", n - 1, rejections.size)
    // A subsequent addSwitch must also fail — the slot is permanently taken by the winner.
    assertTrue(
      runCatching { network.addSwitch("dup") }.exceptionOrNull() is IllegalArgumentException
    )
  }
}
