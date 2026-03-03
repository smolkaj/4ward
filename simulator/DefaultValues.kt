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

import fourward.ir.v1.Type
import fourward.ir.v1.TypeDecl

/**
 * Creates a zero/default [Value] for [type].
 *
 * bit<N> → [BitVal] zero, bool → [BoolVal] false, named types → recursively initialised header
 * (invalid) or struct. Returns [UnitVal] for unrecognised types.
 */
internal fun defaultValue(type: Type, types: Map<String, TypeDecl>): Value =
  when {
    type.hasBit() -> BitVal(0L, type.bit.width)
    type.hasBoolean() -> BoolVal(false)
    type.hasNamed() -> defaultValue(type.named, types)
    else -> UnitVal
  }

/**
 * Creates a zero/default [Value] for the named type.
 *
 * Looks up [typeName] in [types]; returns [UnitVal] if not found. Headers are initially invalid
 * with zeroed fields; structs are recursively initialised.
 */
internal fun defaultValue(typeName: String, types: Map<String, TypeDecl>): Value {
  val typeDecl = types[typeName] ?: return UnitVal
  return when {
    typeDecl.hasHeader() ->
      HeaderVal(
        typeName = typeName,
        fields =
          typeDecl.header.fieldsList
            .associate { f -> f.name to defaultValue(f.type, types) }
            .toMutableMap(),
        valid = false,
      )
    typeDecl.hasStruct() ->
      StructVal(
        typeName = typeName,
        fields =
          typeDecl.struct.fieldsList
            .associate { f -> f.name to defaultValue(f.type, types) }
            .toMutableMap(),
      )
    else -> UnitVal
  }
}
