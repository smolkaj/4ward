package fourward.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [matchOutputAgainstExpects]. */
class RunnerTest {

  private fun expect(port: Int, vararg bytes: Int) =
    StfExpectedOutput(
      port,
      payload = ByteArray(bytes.size) { bytes[it].toByte() },
      mask = ByteArray(bytes.size) { 0xFF.toByte() },
    )

  private fun received(egressPort: Int, vararg bytes: Int) =
    ReceivedPacket(egressPort, ByteArray(bytes.size) { bytes[it].toByte() })

  @Test
  fun `all expects matched with no leftover output`() {
    val failures =
      matchOutputAgainstExpects(listOf(expect(1, 0xAA)), mutableListOf(received(1, 0xAA)))
    assertTrue(failures.isEmpty())
  }

  @Test
  fun `missing expected output is a failure`() {
    val failures = matchOutputAgainstExpects(listOf(expect(1, 0xAA)), mutableListOf())
    assertEquals(1, failures.size)
    assertTrue(failures[0].contains("expected packet on port 1 but got none"))
  }

  @Test
  fun `unexpected output rejected when expects present`() {
    val failures =
      matchOutputAgainstExpects(
        listOf(expect(1, 0xAA)),
        mutableListOf(received(1, 0xAA), received(2, 0xBB)),
      )
    assertEquals(1, failures.size)
    assertTrue(failures[0].contains("unexpected packet on port 2"))
  }

  @Test
  fun `unexpected output ignored when no expects (send-only test)`() {
    val failures =
      matchOutputAgainstExpects(emptyList(), mutableListOf(received(1, 0xAA), received(2, 0xBB)))
    assertTrue(failures.isEmpty())
  }

  @Test
  fun `payload mismatch is a failure`() {
    val failures =
      matchOutputAgainstExpects(listOf(expect(1, 0xAA)), mutableListOf(received(1, 0xBB)))
    assertEquals(1, failures.size)
    assertTrue(failures[0].contains("payload mismatch"))
  }

  @Test
  fun `cross-port matching ignores order`() {
    val failures =
      matchOutputAgainstExpects(
        listOf(expect(2, 0xBB), expect(1, 0xAA)),
        mutableListOf(received(1, 0xAA), received(2, 0xBB)),
      )
    assertTrue(failures.isEmpty())
  }

  @Test
  fun `same-port outputs matched FIFO`() {
    val failures =
      matchOutputAgainstExpects(
        listOf(expect(1, 0xAA), expect(1, 0xBB)),
        mutableListOf(received(1, 0xAA), received(1, 0xBB)),
      )
    assertTrue(failures.isEmpty())
  }

  @Test
  fun `same-port FIFO mismatch when order swapped`() {
    val failures =
      matchOutputAgainstExpects(
        listOf(expect(1, 0xAA), expect(1, 0xBB)),
        mutableListOf(received(1, 0xBB), received(1, 0xAA)),
      )
    // FIFO pairs first-to-first and second-to-second — both mismatch.
    assertEquals(2, failures.size)
    assertTrue(failures.all { it.contains("payload mismatch") })
  }
}
