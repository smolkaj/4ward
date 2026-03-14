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

package fourward.dvaas

import fourward.ir.PipelineConfig
import p4.config.v1.P4InfoOuterClass

/** Shared test utilities for DVaaS tests. */
object DvaasTestUtil {

  /** Loads a PipelineConfig from a Bazel runfiles-relative text proto path. */
  fun loadConfig(relativePath: String): PipelineConfig {
    val r = System.getenv("JAVA_RUNFILES") ?: "."
    val path = java.nio.file.Paths.get(r, "_main/$relativePath")
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
    return builder.build()
  }

  /** Finds a table by alias in p4info, or throws with a clear error. */
  fun findTable(config: PipelineConfig, alias: String): P4InfoOuterClass.Table =
    config.p4Info.tablesList.find { it.preamble.alias == alias }
      ?: error("table '$alias' not found in p4info")

  /** Finds an action by alias in p4info, or throws with a clear error. */
  fun findAction(config: PipelineConfig, alias: String): P4InfoOuterClass.Action =
    config.p4Info.actionsList.find { it.preamble.alias == alias }
      ?: error("action '$alias' not found in p4info")

  /** Finds a match field ID by name in a table, or throws with a clear error. */
  fun matchFieldId(table: P4InfoOuterClass.Table, name: String): Int =
    table.matchFieldsList.find { it.name == name }?.id
      ?: error("match field '$name' not found in table '${table.preamble.alias}'")

  /** Finds an action param ID by name, or throws with a clear error. */
  fun paramId(action: P4InfoOuterClass.Action, name: String): Int =
    action.paramsList.find { it.name == name }?.id
      ?: error("param '$name' not found in action '${action.preamble.alias}'")
}
