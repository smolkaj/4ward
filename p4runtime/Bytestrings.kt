package fourward.p4runtime

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import p4.v1.P4RuntimeOuterClass

/**
 * Helpers for the P4Runtime bytestring encoding rules in spec §8.3.
 *
 * P4Runtime carries P4 integer values as protobuf `bytes`. The spec says:
 *
 *   "Upon receiving a binary string, the P4Runtime receiver (whether the server or
 *    the client) does not impose any restrictions on the maximum length of the string
 *    itself. Instead, the receiver verifies that the value encoded by the string fits
 *    within the expected type (signed or unsigned) and P4Info-specified bitwidth for
 *    the P4 object value."
 *
 *   "The canonical binary string representation uses the shortest string that fits
 *    the encoded integer value. […] read-write symmetry requires that the encoder of
 *    a P4Runtime request or reply uses the shortest strings that fit the encoded
 *    integer values."
 *
 * 4ward currently exposes only unsigned (`bit<W>`) values on the wire — `int<W>`
 * appears only in `P4Data` (§8.4.3), which 4ward does not surface — so the helpers
 * below are unsigned-only.
 */

/**
 * Validates that [value] encodes an unsigned integer that fits in [bitwidth] bits, per spec §8.3.
 *
 * Rejects:
 * - empty bytestrings (always invalid per §8.3),
 * - values whose magnitude exceeds 2^bitwidth − 1, regardless of how many bytes were used to send
 *   them (e.g. `\x01\x00\x00` for `bit<16>` is rejected because 0x10000 > 0xFFFF).
 *
 * Accepts arbitrarily long bytestrings as long as the leading bytes/bits are zero — this is what
 * allows a `bit<9>` server to interoperate with a `bit<8>` client during rolling upgrades.
 *
 * On rejection throws [StatusException] with [Status.OUT_OF_RANGE] (§8.3 explicitly mandates this
 * code).
 *
 * [bitwidth] of 0 marks the field as non-integer (e.g. `@p4runtime_translation` with `sdn_string`,
 * where the bytes carry a UTF-8 string and §8.3's integer rules do not apply). In that case
 * validation is skipped entirely, including the empty check — an empty UTF-8 string is valid.
 */
fun requireFitsInBitwidth(value: ByteString, bitwidth: Int, label: String) {
  if (bitwidth <= 0) return // string-typed field; §8.3 does not apply.
  if (value.isEmpty) {
    throw outOfRange("$label is empty (P4Runtime spec §8.3 requires a non-zero-length bytestring)")
  }
  // Walk the high bytes and count how many bits are actually occupied.
  // We could BigInteger.bitLength() but that allocates; this scan is O(bytes) and allocation-free.
  val bytes = value
  val expectedHighByteMask = highByteMask(bitwidth)
  val expectedHighBytePos = bytes.size() - bytesNeeded(bitwidth)
  for (i in 0 until bytes.size()) {
    val b = bytes.byteAt(i).toInt() and 0xFF
    when {
      i < expectedHighBytePos -> {
        if (b != 0) {
          throw outOfRange(
            "$label 0x${value.toHex()} does not fit in $bitwidth bits " +
              "(byte $i is 0x${"%02x".format(b)}, expected 0)"
          )
        }
      }
      i == expectedHighBytePos -> {
        if (b and expectedHighByteMask.inv() and 0xFF != 0) {
          throw outOfRange(
            "$label 0x${value.toHex()} does not fit in $bitwidth bits " +
              "(top bits of byte $i are non-zero)"
          )
        }
        return
      }
    }
  }
}

/**
 * Returns the canonical (shortest) unsigned big-endian encoding of [value], per spec §8.3.
 *
 * Strips leading zero bytes; preserves a single `\x00` for the value zero. Bitwidth-agnostic, so
 * works for any width (no [Int] / [Long] truncation hazard).
 */
fun canonicalize(value: ByteString): ByteString {
  if (value.isEmpty) return value
  var first = 0
  while (first < value.size() - 1 && value.byteAt(first).toInt() == 0) first++
  return if (first == 0) value else value.substring(first)
}

/**
 * Returns a copy of [match] with all bytestring values canonicalized per spec §8.3.
 *
 * Applied between validation and storage so that the simulator stores a single normal form, which
 * (a) keeps `TableEntry.sameKey` consistent across encodings of the same logical value, and (b)
 * makes reads return canonical form for free, satisfying the §8.3 read-write symmetry requirement.
 */
fun canonicalizeMatch(match: P4RuntimeOuterClass.FieldMatch): P4RuntimeOuterClass.FieldMatch =
  when (match.fieldMatchTypeCase) {
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.EXACT ->
      match
        .toBuilder()
        .setExact(
          P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
            .setValue(canonicalize(match.exact.value))
        )
        .build()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.TERNARY ->
      match
        .toBuilder()
        .setTernary(
          P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
            .setValue(canonicalize(match.ternary.value))
            .setMask(canonicalize(match.ternary.mask))
        )
        .build()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.LPM ->
      match
        .toBuilder()
        .setLpm(
          P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
            .setValue(canonicalize(match.lpm.value))
            .setPrefixLen(match.lpm.prefixLen)
        )
        .build()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.RANGE ->
      match
        .toBuilder()
        .setRange(
          P4RuntimeOuterClass.FieldMatch.Range.newBuilder()
            .setLow(canonicalize(match.range.low))
            .setHigh(canonicalize(match.range.high))
        )
        .build()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OPTIONAL ->
      match
        .toBuilder()
        .setOptional(
          P4RuntimeOuterClass.FieldMatch.Optional.newBuilder()
            .setValue(canonicalize(match.optional.value))
        )
        .build()
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.OTHER,
    P4RuntimeOuterClass.FieldMatch.FieldMatchTypeCase.FIELDMATCHTYPE_NOT_SET,
    null -> match
  }

/** Returns a copy of [param] with its `value` canonicalized per spec §8.3. */
fun canonicalizeParam(
  param: P4RuntimeOuterClass.Action.Param
): P4RuntimeOuterClass.Action.Param =
  param.toBuilder().setValue(canonicalize(param.value)).build()

/**
 * Returns a copy of [update] with all bytestrings canonicalized per spec §8.3.
 *
 * Currently only [P4RuntimeOuterClass.TableEntry]'s match fields and action params are
 * canonicalized — that is the surface where the §8.3 read-write asymmetry would otherwise leak
 * into [fourward.simulator.TableStore.sameKey]'s raw proto-equality compare. Other entity types
 * (counters, registers, multicast groups, …) are passed through; if they ever start storing
 * client-supplied bytestrings as match keys, extend this function.
 */
fun canonicalizeBytestrings(update: P4RuntimeOuterClass.Update): P4RuntimeOuterClass.Update {
  val entity = update.entity
  return when (entity.entityCase) {
    P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY -> {
      val canonicalEntry = canonicalizeTableEntry(entity.tableEntry)
      update
        .toBuilder()
        .setEntity(entity.toBuilder().setTableEntry(canonicalEntry))
        .build()
    }
    P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER -> {
      val member = entity.actionProfileMember
      val canonicalAction = canonicalizeAction(member.action)
      update
        .toBuilder()
        .setEntity(
          entity.toBuilder().setActionProfileMember(member.toBuilder().setAction(canonicalAction))
        )
        .build()
    }
    else -> update
  }
}

private fun canonicalizeTableEntry(
  entry: P4RuntimeOuterClass.TableEntry
): P4RuntimeOuterClass.TableEntry {
  val builder = entry.toBuilder()
  if (entry.matchCount > 0) {
    builder.clearMatch()
    for (m in entry.matchList) builder.addMatch(canonicalizeMatch(m))
  }
  if (entry.hasAction()) builder.setAction(canonicalizeTableAction(entry.action))
  return builder.build()
}

private fun canonicalizeTableAction(
  action: P4RuntimeOuterClass.TableAction
): P4RuntimeOuterClass.TableAction {
  if (action.hasAction()) {
    return action.toBuilder().setAction(canonicalizeAction(action.action)).build()
  }
  if (action.hasActionProfileActionSet()) {
    val set = action.actionProfileActionSet.toBuilder()
    val rebuilt =
      action.actionProfileActionSet.actionProfileActionsList.map { profileAction ->
        profileAction.toBuilder().setAction(canonicalizeAction(profileAction.action)).build()
      }
    set.clearActionProfileActions()
    rebuilt.forEach { set.addActionProfileActions(it) }
    return action.toBuilder().setActionProfileActionSet(set).build()
  }
  return action
}

private fun canonicalizeAction(
  action: P4RuntimeOuterClass.Action
): P4RuntimeOuterClass.Action {
  if (action.paramsCount == 0) return action
  val builder = action.toBuilder().clearParams()
  for (p in action.paramsList) builder.addParams(canonicalizeParam(p))
  return builder.build()
}

/** Number of bytes needed to encode a value of [bitwidth] bits. */
private fun bytesNeeded(bitwidth: Int): Int = (bitwidth + 7) / 8

/** Mask of the bits actually occupied within the highest bytestring byte for [bitwidth]. */
private fun highByteMask(bitwidth: Int): Int {
  val occupiedInHighByte = ((bitwidth - 1) % 8) + 1
  return (1 shl occupiedInHighByte) - 1
}

private fun outOfRange(msg: String): StatusException =
  Status.OUT_OF_RANGE.withDescription(msg).asException()
