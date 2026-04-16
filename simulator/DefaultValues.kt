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

import fourward.ir.Type
import fourward.ir.TypeDecl
import java.math.BigInteger

/**
 * Creates a zero/default [Value] for [type].
 *
 * When [layouts] is non-null, headers and primitive-only structs are constructed via
 * [HeaderVal.bufferBacked] / [StructVal.bufferBacked] — their `fields` maps live as bit ranges in a
 * [PacketBuffer] rather than as entries in a `HashMap`. Types whose layout isn't known (header
 * unions, varbits, non-serialisable enums) fall through to the legacy HashMap path.
 */
internal fun defaultValue(
  type: Type,
  types: Map<String, TypeDecl>,
  layouts: PipelineLayouts? = null,
): Value =
  when {
    type.hasBit() -> BitVal(0L, type.bit.width)
    type.hasSignedInt() -> IntVal(SignedBitVector(BigInteger.ZERO, type.signedInt.width))
    type.hasBoolean() -> BoolVal(false)
    type.hasNamed() -> defaultValue(type.named, types, layouts)
    type.hasHeaderStack() ->
      HeaderStackVal(
        elementTypeName = type.headerStack.elementType,
        headers =
          MutableList(type.headerStack.size.toInt()) {
            defaultValue(type.headerStack.elementType, types, layouts)
          },
      )
    else -> UnitVal
  }

/** Creates a zero/default [Value] for the named type. */
internal fun defaultValue(
  typeName: String,
  types: Map<String, TypeDecl>,
  layouts: PipelineLayouts? = null,
): Value {
  val typeDecl = types[typeName] ?: return UnitVal
  return when {
    typeDecl.hasHeader() -> defaultHeader(typeName, typeDecl, types, layouts)
    typeDecl.hasStruct() -> defaultStruct(typeName, typeDecl.struct.fieldsList, types, layouts)
    typeDecl.hasHeaderUnion() ->
      defaultStruct(typeName, typeDecl.headerUnion.fieldsList, types, layouts)
    typeDecl.hasEnum() && typeDecl.enum.width > 0 -> BitVal(0L, typeDecl.enum.width)
    else -> UnitVal
  }
}

private fun defaultHeader(
  typeName: String,
  typeDecl: TypeDecl,
  types: Map<String, TypeDecl>,
  layouts: PipelineLayouts?,
): HeaderVal {
  val headerLayout = layouts?.headers?.get(typeName)
  if (headerLayout != null) return HeaderVal.bufferBacked(headerLayout)
  return HeaderVal(
    typeName = typeName,
    fields =
      typeDecl.header.fieldsList.associateTo(mutableMapOf()) { f ->
        f.name to defaultValue(f.type, types, layouts)
      },
    valid = false,
  )
}

private fun defaultStruct(
  typeName: String,
  fieldDecls: List<fourward.ir.FieldDecl>,
  types: Map<String, TypeDecl>,
  layouts: PipelineLayouts?,
): StructVal {
  val structLayout = layouts?.structs?.get(typeName)
  if (structLayout != null && fieldDecls.all { isBufferSafeField(it.type) }) {
    return StructVal.bufferBacked(structLayout)
  }
  return StructVal(
    typeName = typeName,
    fields =
      fieldDecls.associateTo(mutableMapOf()) { f -> f.name to defaultValue(f.type, types, layouts) },
  )
}

/**
 * A field type is safe to place in a buffer-backed map iff the interpreter only writes values that
 * the encoder can losslessly round-trip. Primitives (bit/int/bool/error) qualify; enum- typed
 * fields don't because the interpreter sometimes assigns unconverted [InfIntVal]s to them, which
 * the buffer-backed encoder rejects. Header stacks and nested structs are handled through a
 * different path and don't belong in a primitive field map.
 */
private fun isBufferSafeField(type: fourward.ir.Type): Boolean =
  type.hasBit() ||
    type.hasSignedInt() ||
    type.hasBoolean() ||
    type.hasError() ||
    (type.hasNamed() && type.named == "error")
