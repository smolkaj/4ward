package fourward.p4runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Uint128

/** Unit tests for [P4RuntimeService] helpers. */
class P4RuntimeServiceTest {

  private fun uint128(high: Long, low: Long): Uint128 =
    Uint128.newBuilder().setHigh(high).setLow(low).build()

  // ---------------------------------------------------------------------------
  // compareUint128
  // ---------------------------------------------------------------------------

  @Test
  fun `equal values return zero`() {
    assertEquals(0, compareUint128(uint128(0, 5), uint128(0, 5)))
  }

  @Test
  fun `higher low wins when high is equal`() {
    assertTrue(compareUint128(uint128(0, 10), uint128(0, 1)) > 0)
  }

  @Test
  fun `lower low loses when high is equal`() {
    assertTrue(compareUint128(uint128(0, 1), uint128(0, 10)) < 0)
  }

  @Test
  fun `high field dominates over low`() {
    // high=1 low=0 > high=0 low=MAX_VALUE
    assertTrue(compareUint128(uint128(1, 0), uint128(0, Long.MAX_VALUE)) > 0)
  }

  @Test
  fun `unsigned semantics for low field`() {
    // -1L as unsigned is ULong.MAX_VALUE, which is larger than 1L.
    assertTrue(compareUint128(uint128(0, -1), uint128(0, 1)) > 0)
  }

  @Test
  fun `unsigned semantics for high field`() {
    // -1L as unsigned high is larger than 0L high.
    assertTrue(compareUint128(uint128(-1, 0), uint128(0, 0)) > 0)
  }

  @Test
  fun `both fields at max are equal`() {
    assertEquals(0, compareUint128(uint128(-1, -1), uint128(-1, -1)))
  }

  @Test
  fun `default Uint128 is zero`() {
    assertEquals(0, compareUint128(Uint128.getDefaultInstance(), uint128(0, 0)))
  }
}
