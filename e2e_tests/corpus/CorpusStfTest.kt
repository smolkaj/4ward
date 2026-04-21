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

import fourward.stf.TestResult
import fourward.stf.runStfTest
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Parameterized test that runs all p4c corpus STF tests in a single JVM.
 *
 * Test names are enumerated at BUILD time (via the `corpus_test_suite` macro) and passed via
 * `-Dfourward.corpus_testcases=...`. Runtime file-listing is avoided because hermetic sandboxes
 * (google3, remote executors) serve runfiles via manifest, not as a real directory tree.
 */
@RunWith(Parameterized::class)
class CorpusStfTest(private val testName: String) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testCases(): List<Array<String>> =
      System.getProperty("fourward.corpus_testcases")
        .orEmpty()
        .split(",")
        .filter { it.isNotEmpty() }
        .sorted()
        .map { arrayOf(it) }
  }

  @Test
  fun test() {
    val result = runStfTest(testName, "e2e_tests/corpus")
    if (result is TestResult.Failure) fail(result.message)
  }
}
