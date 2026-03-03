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
import fourward.e2e.runStfTestFromEnv
import org.junit.Assert.fail
import org.junit.Test

/**
 * Generic test class for p4c corpus STF tests.
 *
 * The compiled pipeline config and STF file paths are injected via the environment variables
 * FOURWARD_TXTPB and FOURWARD_STF (rlocation paths set by the p4_stf_test Bazel macro). A single
 * instance of this class is reused for every corpus test target.
 */
class CorpusStfTest {

  @Test
  fun `corpus stf test`() {
    val result = runStfTestFromEnv()
    if (result is TestResult.Failure) fail(result.message)
  }
}
