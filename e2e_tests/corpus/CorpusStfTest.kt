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

package fourward.e2e.corpus

import fourward.e2e.TestResult
import fourward.e2e.runStfTest
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parameterized test that runs all p4c corpus STF tests in a single JVM.
 *
 * Test names are discovered at runtime from the .stf files present in the runfiles directory. Each
 * test launches a fresh simulator subprocess (the simulator resets state on each LoadPipeline), so
 * tests remain isolated despite sharing a JVM.
 */
@RunWith(Parameterized::class)
class CorpusStfTest(private val testName: String) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testCases(): List<Array<String>> {
      val r = System.getenv("JAVA_RUNFILES") ?: "."
      val corpusDir = Paths.get(r, "_main/e2e_tests/corpus")
      return Files.list(corpusDir).use { stream ->
        stream
          .filter { it.toString().endsWith(".stf") }
          .map { arrayOf(it.fileName.toString().removeSuffix(".stf")) }
          .sorted(Comparator.comparing { it[0] })
          .toList()
      }
    }
  }

  @Test
  fun test() {
    // TODO(#220): enable rejectUnexpected once gauntlet_enum_assign-bmv2 is fixed.
    val result = runStfTest(testName, "e2e_tests/corpus", rejectUnexpected = false)
    if (result is TestResult.Failure) fail(result.message)
  }
}
