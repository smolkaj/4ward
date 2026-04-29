package fourward.e2e.p4runtime_diff

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.ReadResponse
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Unit tests for the canonicalize-and-diff helpers in [ResponseDiff].
 *
 * The differential harness itself is gated on a working `simple_switch_grpc` binary
 * (see designs/p4runtime_diff.md §Phase 1) and ships in a follow-up PR. These tests
 * exercise the canonicalization logic on synthetic [TableEntry] / [ReadResponse]
 * protos so the diff machinery is correct on day one of the harness landing.
 */
class ResponseDiffTest {

  @Test
  fun `canonicalizeTableEntry sorts match list by field_id`() {
    val unsorted = entry(matches = listOf(exact(2, 0xCAFE), exact(1, 0xBEEF)))
    val canonical = canonicalizeTableEntry(unsorted)
    assertEquals(
      listOf(1, 2),
      canonical.matchList.map { it.fieldId },
    )
  }

  @Test
  fun `canonicalizeTableEntry returns same instance when already canonical`() {
    val canonical = entry(matches = listOf(exact(1, 0xBEEF), exact(2, 0xCAFE)))
    assertSame(canonical, canonicalizeTableEntry(canonical))
  }

  @Test
  fun `canonicalizeEntity is identity for non-table entries`() {
    val entity =
      Entity.newBuilder()
        .setCounterEntry(p4.v1.P4RuntimeOuterClass.CounterEntry.newBuilder().setCounterId(7))
        .build()
    assertSame(entity, canonicalizeEntity(entity))
  }

  @Test
  fun `canonicalizeReadResponse sorts every nested match list`() {
    val resp =
      ReadResponse.newBuilder()
        .addEntities(asEntity(entry(matches = listOf(exact(2, 0x01), exact(1, 0x02)))))
        .addEntities(asEntity(entry(matches = listOf(exact(3, 0x03), exact(1, 0x04)))))
        .build()
    val canonical = canonicalizeReadResponse(resp)
    assertEquals(
      listOf(1, 2),
      canonical.getEntities(0).tableEntry.matchList.map { it.fieldId },
    )
    assertEquals(
      listOf(1, 3),
      canonical.getEntities(1).tableEntry.matchList.map { it.fieldId },
    )
  }

  @Test
  fun `assertProtosEqual passes on equal messages`() {
    val a = entry(matches = listOf(exact(1, 0xBEEF)))
    val b = entry(matches = listOf(exact(1, 0xBEEF)))
    assertProtosEqual(a, b)
  }

  @Test
  fun `assertProtosEqual throws with text-format diff on mismatch`() {
    val a = entry(matches = listOf(exact(1, 0xBEEF)))
    val b = entry(matches = listOf(exact(1, 0xCAFE)))
    val e = assertThrows(AssertionError::class.java) { assertProtosEqual(a, b) }
    val msg = e.message ?: ""
    assert(msg.contains("--- expected ---")) { "expected text-format header missing in: $msg" }
    assert(msg.contains("--- actual   ---")) { "actual text-format header missing in: $msg" }
  }

  @Test
  fun `out-of-order match lists become equal after canonicalization`() {
    val a = entry(matches = listOf(exact(1, 0x01), exact(2, 0x02)))
    val b = entry(matches = listOf(exact(2, 0x02), exact(1, 0x01)))
    assertNotEquals("different proto order should not be equal raw", a, b)
    assertEquals(canonicalizeTableEntry(a), canonicalizeTableEntry(b))
  }

  // ---------------------------------------------------------------------------

  private fun entry(matches: List<FieldMatch>): TableEntry =
    TableEntry.newBuilder().setTableId(1).addAllMatch(matches).build()

  private fun exact(fieldId: Int, value: Int): FieldMatch =
    FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(
        FieldMatch.Exact.newBuilder()
          .setValue(ByteString.copyFrom(byteArrayOf(value.toByte())))
      )
      .build()

  private fun asEntity(entry: TableEntry): Entity =
    Entity.newBuilder().setTableEntry(entry).build()
}
