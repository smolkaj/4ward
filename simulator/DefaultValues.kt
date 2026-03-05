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
import java.math.BigInteger

/**
 * Creates a zero/default [Value] for [type].
 *
 * bit<N> → [BitVal] zero, int<N> → [IntVal] zero, bool → [BoolVal] false, named types → recursively
 * initialised header (invalid), struct, or [BitVal] zero for serializable enums. varbit<N> and
 * unrecognised types → [UnitVal].
 */
internal fun defaultValue(type: Type, types: Map<String, TypeDecl>): Value =
  when {
    type.hasBit() -> BitVal(0L, type.bit.width)
    type.hasSignedInt() -> IntVal(SignedBitVector(java.math.BigInteger.ZERO, type.signedInt.width))
    type.hasBoolean() -> BoolVal(false)
    type.hasNamed() -> defaultValue(type.named, types)
    type.hasHeaderStack() ->
      HeaderStackVal(
        elementTypeName = type.headerStack.elementType,
        headers =
          MutableList(type.headerStack.size.toInt()) {
            defaultValue(type.headerStack.elementType, types)
          },
      )
    else -> UnitVal // varbit<N>: variable-length; no fixed default
  }

/**
 * Creates a zero/default [Value] for the named type.
 *
 * Looks up [typeName] in [types]; returns [UnitVal] if not found. Headers are initially invalid
 * with zeroed fields; structs are recursively initialised. Serializable enums default to a zero
 * [BitVal] of their underlying width.
 */
internal fun defaultValue(typeName: String, types: Map<String, TypeDecl>): Value {
  val typeDecl = types[typeName] ?: return UnitVal
  return when {
    typeDecl.hasHeader() ->
      HeaderVal(
        typeName = typeName,
        fields =
          typeDecl.header.fieldsList.associateTo(mutableMapOf()) { f ->
            f.name to defaultValue(f.type, types)
          },
        valid = false,
      )
    typeDecl.hasStruct() -> defaultStruct(typeName, typeDecl.struct.fieldsList, types)
    // Header union (P4 spec §8.20): represented as a StructVal so field access works
    // uniformly; per-member validity is tracked via HeaderVal.valid.
    typeDecl.hasHeaderUnion() -> defaultStruct(typeName, typeDecl.headerUnion.fieldsList, types)
    // Serializable enum: default to zero of the underlying bit width.
    typeDecl.hasEnum() && typeDecl.enum.width > 0 -> BitVal(0L, typeDecl.enum.width)
    else -> UnitVal
  }
}

private fun defaultStruct(
  typeName: String,
  fieldDecls: List<fourward.ir.v1.FieldDecl>,
  types: Map<String, TypeDecl>,
): StructVal =
  StructVal(
    typeName = typeName,
    fields = fieldDecls.associateTo(mutableMapOf()) { f -> f.name to defaultValue(f.type, types) },
  )
