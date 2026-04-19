package fourward.simulator

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unit tests for [HeaderVal], focusing on setInvalid() field zeroing (P4 §8.17). */
class HeaderValTest {

  @Test
  fun `setInvalid zeros all field types`() {
    val hdr =
      HeaderVal(
        "test_t",
        mutableMapOf(
          "addr" to BitVal(0xFFL, 8),
          "offset" to IntVal(SignedBitVector(BigInteger.valueOf(-3), 16)),
          "flag" to BoolVal(true),
          "err" to ErrorVal("PacketTooShort"),
        ),
        valid = true,
      )
    hdr.setInvalid()
    assertFalse(hdr.valid)
    assertEquals(BitVal(0L, 8), hdr.fields["addr"])
    assertEquals(IntVal(SignedBitVector(BigInteger.ZERO, 16)), hdr.fields["offset"])
    assertEquals(BoolVal(false), hdr.fields["flag"])
    assertEquals(ErrorVal.NO_ERROR, hdr.fields["err"])
  }
}
