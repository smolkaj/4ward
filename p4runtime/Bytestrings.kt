package fourward.p4runtime

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import java.math.BigInteger
import p4.v1.P4RuntimeOuterClass

/** Interprets a [ByteString] as an unsigned big-endian [BigInteger]. */
internal fun ByteString.toUnsignedBigInteger(): BigInteger = BigInteger(1, toByteArray())

/**
 * Helpers for the P4Runtime bytestring encoding rules in spec §8.3.
 *
 * P4Runtime carries P4 integer values as protobuf `bytes`. The spec says:
 *
 * "Upon receiving a binary string, the P4Runtime receiver (whether the server or the client) does
 * not impose any restrictions on the maximum length of the string itself. Instead, the receiver
 * verifies that the value encoded by the string fits within the expected type (signed or unsigned)
 * and P4Info-specified bitwidth for the P4 object value."
 *
 * "The canonical binary string representation uses the shortest string that fits the encoded
 * integer value. […] read-write symmetry requires that the encoder of a P4Runtime request or reply
 * uses the shortest strings that fit the encoded integer values."
 *
 * 4ward currently exposes only unsigned (`bit<W>`) values on the wire — `int<W>` appears only in
 * `P4Data` (§8.4.3), which 4ward does not surface — so the helpers below are unsigned-only.
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
  if (value.toUnsignedBigInteger().bitLength() > bitwidth) {
    throw outOfRange("$label 0x${value.toHex()} does not fit in $bitwidth bits")
  }
}

/**
 * Returns the canonical (shortest) unsigned big-endian encoding of [value], per spec §8.3.
 *
 * Strips leading zero bytes; preserves a single `\x00` for the value zero. Bitwidth-agnostic, so
 * works for any width (no [Int] / [Long] truncation hazard).
 *
 * Empty input is returned as-is. §8.3's integer rules say empty is invalid for `bit<W>` fields, but
 * the validator catches that before this function is reached. For non-integer fields
 * (`@p4runtime_translation` with `sdn_string`, where the bytes carry a UTF-8 string), an empty
 * value represents the empty string and is already canonical.
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
fun canonicalizeParam(param: P4RuntimeOuterClass.Action.Param): P4RuntimeOuterClass.Action.Param =
  param.toBuilder().setValue(canonicalize(param.value)).build()

/**
 * Returns a copy of [update] with all bytestrings canonicalized per spec §8.3.
 *
 * Canonicalizes the entity types that store client-supplied bytestrings as part of the entry's
 * identity or payload — `TableEntry` (match fields + action params) and `ActionProfileMember`
 * (action params). For every other entity type, the bytestring fields are either absent or already
 * in a normal form (counters/meters carry indices and numeric data, not match keys), so the update
 * is returned unchanged. If a future entity type starts storing client bytestrings as a match key,
 * add a case here — the exhaustive `when` will fail at compile time so it can't be forgotten.
 */
fun canonicalizeBytestrings(update: P4RuntimeOuterClass.Update): P4RuntimeOuterClass.Update {
  val entity = update.entity
  return when (entity.entityCase) {
    P4RuntimeOuterClass.Entity.EntityCase.TABLE_ENTRY ->
      update
        .toBuilder()
        .setEntity(entity.toBuilder().setTableEntry(canonicalizeTableEntry(entity.tableEntry)))
        .build()
    P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_MEMBER -> {
      val member = entity.actionProfileMember
      update
        .toBuilder()
        .setEntity(
          entity
            .toBuilder()
            .setActionProfileMember(member.toBuilder().setAction(canonicalizeAction(member.action)))
        )
        .build()
    }
    // Action-profile groups carry only member-ID references — no client bytestrings.
    P4RuntimeOuterClass.Entity.EntityCase.ACTION_PROFILE_GROUP,
    // PRE entries (multicast groups, clone sessions) reference replicas by integer port IDs.
    P4RuntimeOuterClass.Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY,
    // Counters / meters use integer indices and numeric counts, not match keys.
    P4RuntimeOuterClass.Entity.EntityCase.COUNTER_ENTRY,
    P4RuntimeOuterClass.Entity.EntityCase.DIRECT_COUNTER_ENTRY,
    P4RuntimeOuterClass.Entity.EntityCase.METER_ENTRY,
    P4RuntimeOuterClass.Entity.EntityCase.DIRECT_METER_ENTRY,
    // Registers, value sets, digests, externs aren't surfaced by 4ward today.
    P4RuntimeOuterClass.Entity.EntityCase.REGISTER_ENTRY,
    P4RuntimeOuterClass.Entity.EntityCase.VALUE_SET_ENTRY,
    P4RuntimeOuterClass.Entity.EntityCase.DIGEST_ENTRY,
    P4RuntimeOuterClass.Entity.EntityCase.EXTERN_ENTRY,
    // Should not happen: the validator rejects updates with no entity set before we reach here.
    P4RuntimeOuterClass.Entity.EntityCase.ENTITY_NOT_SET,
    null -> update
  }
}

/** Returns a copy of [entry] with match-field and action-parameter bytestrings canonicalized. */
fun canonicalizeTableEntry(entry: P4RuntimeOuterClass.TableEntry): P4RuntimeOuterClass.TableEntry {
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
): P4RuntimeOuterClass.TableAction =
  when (action.typeCase) {
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION ->
      action.toBuilder().setAction(canonicalizeAction(action.action)).build()
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_ACTION_SET -> {
      val rebuilt =
        action.actionProfileActionSet.actionProfileActionsList.map { profileAction ->
          profileAction.toBuilder().setAction(canonicalizeAction(profileAction.action)).build()
        }
      action
        .toBuilder()
        .setActionProfileActionSet(
          action.actionProfileActionSet
            .toBuilder()
            .clearActionProfileActions()
            .addAllActionProfileActions(rebuilt)
        )
        .build()
    }
    // ID-only references carry no client bytestrings — nothing to canonicalize.
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_MEMBER_ID,
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_GROUP_ID,
    P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET,
    null -> action
  }

private fun canonicalizeAction(action: P4RuntimeOuterClass.Action): P4RuntimeOuterClass.Action {
  if (action.paramsCount == 0) return action
  val builder = action.toBuilder().clearParams()
  for (p in action.paramsList) builder.addParams(canonicalizeParam(p))
  return builder.build()
}

private fun outOfRange(msg: String): StatusException =
  Status.OUT_OF_RANGE.withDescription(msg).asException()
