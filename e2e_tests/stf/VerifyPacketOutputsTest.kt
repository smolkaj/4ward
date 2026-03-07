package fourward.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyPacketOutputsTest {

  @Test
  fun `exact match — no failures`() {
    val outputs = mutableListOf(EgressPacket(1, byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
    val expects =
      listOf(
        StfExpectedOutput(
          1,
          byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
          byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
        )
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(emptyList<String>(), failures)
    assertTrue("matched packet should be consumed", outputs.isEmpty())
  }

  @Test
  fun `payload mismatch — reports failure`() {
    val outputs = mutableListOf(EgressPacket(1, byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
    val expects =
      listOf(
        StfExpectedOutput(
          1,
          byteArrayOf(0xBE.toByte(), 0xEF.toByte()),
          byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
        )
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(1, failures.size)
    assertTrue("failure mentions port", failures[0].contains("port 1"))
    assertTrue("failure mentions mismatch", failures[0].contains("payload mismatch"))
  }

  @Test
  fun `missing expected packet — reports failure`() {
    val outputs = mutableListOf<EgressPacket>()
    val expects =
      listOf(
        StfExpectedOutput(
          1,
          byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
          byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
        )
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(1, failures.size)
    assertTrue("failure mentions missing packet", failures[0].contains("expected packet on port 1"))
  }

  @Test
  fun `masked wildcard — ignores masked bytes`() {
    val outputs = mutableListOf(EgressPacket(1, byteArrayOf(0xDE.toByte(), 0x99.toByte())))
    val expects =
      listOf(
        StfExpectedOutput(
          1,
          byteArrayOf(0xDE.toByte(), 0x00.toByte()),
          byteArrayOf(0xFF.toByte(), 0x00.toByte()), // second byte is wildcard
        )
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(emptyList<String>(), failures)
  }

  @Test
  fun `cross-port matching — finds packet on correct port`() {
    val outputs =
      mutableListOf(
        EgressPacket(2, byteArrayOf(0xAA.toByte())),
        EgressPacket(1, byteArrayOf(0xBB.toByte())),
      )
    val expects =
      listOf(
        StfExpectedOutput(1, byteArrayOf(0xBB.toByte()), byteArrayOf(0xFF.toByte())),
        StfExpectedOutput(2, byteArrayOf(0xAA.toByte()), byteArrayOf(0xFF.toByte())),
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(emptyList<String>(), failures)
    assertTrue("all packets consumed", outputs.isEmpty())
  }

  @Test
  fun `FIFO within same port — matches in order`() {
    val outputs =
      mutableListOf(
        EgressPacket(1, byteArrayOf(0x01.toByte())),
        EgressPacket(1, byteArrayOf(0x02.toByte())),
      )
    val expects =
      listOf(
        StfExpectedOutput(1, byteArrayOf(0x01.toByte()), byteArrayOf(0xFF.toByte())),
        StfExpectedOutput(1, byteArrayOf(0x02.toByte()), byteArrayOf(0xFF.toByte())),
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(emptyList<String>(), failures)
  }

  @Test
  fun `exact length enforced — longer actual fails`() {
    val outputs =
      mutableListOf(EgressPacket(1, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x00.toByte())))
    val expects =
      listOf(
        StfExpectedOutput(
          1,
          byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
          byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
          exactLength = true,
        )
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(1, failures.size)
  }

  @Test
  fun `no exact length — longer actual OK`() {
    val outputs =
      mutableListOf(EgressPacket(1, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x00.toByte())))
    val expects =
      listOf(
        StfExpectedOutput(
          1,
          byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
          byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
          exactLength = false,
        )
      )

    val failures = verifyPacketOutputs(outputs, expects)
    assertEquals(emptyList<String>(), failures)
  }
}
