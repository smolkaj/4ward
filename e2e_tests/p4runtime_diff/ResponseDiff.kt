package fourward.e2e.p4runtime_diff

import com.google.protobuf.Message
import com.google.protobuf.TextFormat
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Canonicalize-then-compare logic for P4Runtime responses, per `designs/p4runtime_diff.md`
 * §"Canonicalizations before diff".
 *
 * The design enumerates four allowed divergences that must be canonicalized away before equality:
 * 1. Field ordering in repeated fields — sort by `field_id`.
 * 2. Error message text — compared via gRPC status code only (handled by callers).
 * 3. Server-assigned IDs — recorded and substituted by callers; not canonicalized here.
 * 4. Counter/meter timing values — initial scenarios avoid these; not canonicalized here.
 *
 * Anything not anticipated by these canonicalizations is reported as a divergence — the design
 * makes that an explicit failure, not a silent ignore.
 */

/** Returns a copy of [entity] with all match-list orderings canonicalized. */
fun canonicalizeEntity(entity: Entity): Entity =
  when (entity.entityCase) {
    Entity.EntityCase.TABLE_ENTRY ->
      entity.toBuilder().setTableEntry(canonicalizeTableEntry(entity.tableEntry)).build()
    else -> entity
  }

/** Returns a copy of [entry] with match fields sorted by `field_id`. */
fun canonicalizeTableEntry(entry: TableEntry): TableEntry {
  val sortedMatches: List<FieldMatch> = entry.matchList.sortedBy { it.fieldId }
  if (sortedMatches == entry.matchList) return entry
  return entry.toBuilder().clearMatch().addAllMatch(sortedMatches).build()
}

/** Returns a copy of [response] with each entity's match list sorted. */
fun canonicalizeReadResponse(response: ReadResponse): ReadResponse {
  val canonical = response.entitiesList.map(::canonicalizeEntity)
  if (canonical == response.entitiesList) return response
  return response.toBuilder().clearEntities().addAllEntities(canonical).build()
}

/**
 * Asserts two protobuf messages are equal after canonicalization. Throws [AssertionError] with a
 * structural diff suitable for surfacing the actual divergence in test failures.
 */
fun assertProtosEqual(expected: Message, actual: Message, label: String = "responses") {
  if (expected == actual) return
  throw AssertionError(
    "$label diverged:\n" +
      "--- expected ---\n${TextFormat.printer().printToString(expected)}" +
      "--- actual   ---\n${TextFormat.printer().printToString(actual)}"
  )
}
