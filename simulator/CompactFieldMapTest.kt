package fourward.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactFieldMapTest {

  private fun bit(value: Long, width: Int): Value = BitVal(value, width)

  private fun sample() =
    CompactFieldMap.of(
      listOf(
        "srcAddr" to bit(0xAABBCCDD, 32),
        "dstAddr" to bit(0x11223344, 32),
        "ttl" to bit(64, 8),
      )
    )

  @Test
  fun `get returns values by field name`() {
    val map = sample()
    assertEquals(bit(0xAABBCCDD, 32), map["srcAddr"])
    assertEquals(bit(64, 8), map["ttl"])
  }

  @Test
  fun `get returns null for unknown field`() {
    assertNull(sample()["nonexistent"])
  }

  @Test
  fun `put updates existing field`() {
    val map = sample()
    map["ttl"] = bit(63, 8)
    assertEquals(bit(63, 8), map["ttl"])
  }

  @Test(expected = IllegalArgumentException::class)
  fun `put rejects unknown field`() {
    sample()["newField"] = bit(0, 8)
  }

  @Test
  fun `size matches field count`() {
    assertEquals(3, sample().size)
  }

  @Test
  fun `containsKey for present and absent keys`() {
    val map = sample()
    assertTrue(map.containsKey("srcAddr"))
    assertFalse(map.containsKey("nonexistent"))
  }

  @Test
  fun `iteration preserves declaration order`() {
    val keys = sample().entries.map { it.key }
    assertEquals(listOf("srcAddr", "dstAddr", "ttl"), keys)
  }

  @Test
  fun `copy produces independent mutations`() {
    val original = sample()
    val copy = original.copy()

    copy["ttl"] = bit(1, 8)

    assertEquals(bit(64, 8), original["ttl"])
    assertEquals(bit(1, 8), copy["ttl"])
  }

  @Test
  fun `copy shares field names array`() {
    val original = sample()
    val copy = original.copy()
    // Both should report the same keys — structural, not identity, check.
    assertEquals(original.keys, copy.keys)
  }

  @Test
  fun `clear nulls all values then putAll restores`() {
    // This is the pattern used by HeaderVal.setValid: clear() then putAll(newFields).
    val map = sample()
    map.clear()

    // All values are null after clear (get returns null for present keys).
    assertNull(map["srcAddr"])
    assertNull(map["ttl"])
    // But keys are still present (containsKey still true — the key set is fixed).
    assertTrue(map.containsKey("srcAddr"))

    map.putAll(mapOf("srcAddr" to bit(1, 32), "dstAddr" to bit(2, 32), "ttl" to bit(3, 8)))
    assertEquals(bit(1, 32), map["srcAddr"])
    assertEquals(bit(3, 8), map["ttl"])
  }

  @Test
  fun `replaceAll transforms values in place`() {
    // This is the pattern used by HeaderVal.setInvalid.
    val map = sample()
    map.replaceAll { _, v ->
      when (v) {
        is BitVal -> BitVal(0L, v.bits.width)
        else -> v
      }
    }
    assertEquals(bit(0, 32), map["srcAddr"])
    assertEquals(bit(0, 8), map["ttl"])
  }
}
