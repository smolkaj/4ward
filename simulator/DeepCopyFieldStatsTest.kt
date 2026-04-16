package fourward.simulator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for [DeepCopyFieldStats] tracking infrastructure. */
class DeepCopyFieldStatsTest {

  @Before
  fun enableTracking() {
    System.setProperty("fourward.simulator.trackFieldReads", "true")
    DeepCopyFieldStats.reset()
  }

  @After
  fun disableTracking() {
    System.clearProperty("fourward.simulator.trackFieldReads")
  }

  @Test
  fun `deepCopy counts total copied fields`() {
    val header = HeaderVal("eth", mutableMapOf("dst" to BitVal(0L, 48), "src" to BitVal(0L, 48)))
    header.deepCopy()
    assertEquals(2, DeepCopyFieldStats.totalFieldsCopied.sum())
  }

  @Test
  fun `reads on deep-copied instance are tracked`() {
    val header =
      HeaderVal(
        "eth",
        mutableMapOf("dst" to BitVal(0L, 48), "src" to BitVal(0L, 48), "type" to BitVal(0L, 16)),
      )
    val copy = header.deepCopy()

    // Read one field.
    copy.fields["dst"]
    assertEquals(1, DeepCopyFieldStats.rawFieldReads.sum())

    // Read again — counts as another raw read.
    copy.fields["dst"]
    assertEquals(2, DeepCopyFieldStats.rawFieldReads.sum())

    // Read a second field.
    copy.fields["type"]
    assertEquals(3, DeepCopyFieldStats.rawFieldReads.sum())

    // No writes yet.
    assertEquals(0, DeepCopyFieldStats.rawFieldWrites.sum())
  }

  @Test
  fun `writes on deep-copied instance are tracked`() {
    val header =
      HeaderVal("eth", mutableMapOf("dst" to BitVal(0L, 48), "src" to BitVal(0L, 48)))
    val copy = header.deepCopy()

    copy.fields["dst"] = BitVal(1L, 48)
    assertEquals(1, DeepCopyFieldStats.rawFieldWrites.sum())
  }

  @Test
  fun `original instance is not tracked`() {
    val header = HeaderVal("eth", mutableMapOf("dst" to BitVal(0L, 48)))
    header.fields["dst"]
    header.fields["dst"] = BitVal(1L, 48)

    assertEquals(0, DeepCopyFieldStats.rawFieldReads.sum())
    assertEquals(0, DeepCopyFieldStats.rawFieldWrites.sum())
    assertEquals(0, DeepCopyFieldStats.totalFieldsCopied.sum())
  }

  @Test
  fun `StructVal deepCopy is also tracked`() {
    val struct =
      StructVal("meta", mutableMapOf("port" to BitVal(0L, 9), "flag" to BoolVal(false)))
    val copy = struct.deepCopy()

    assertEquals(2, DeepCopyFieldStats.totalFieldsCopied.sum())
    copy.fields["port"]
    assertEquals(1, DeepCopyFieldStats.rawFieldReads.sum())
  }

  @Test
  fun `tracking is disabled when flag is off`() {
    System.clearProperty("fourward.simulator.trackFieldReads")

    val header = HeaderVal("eth", mutableMapOf("dst" to BitVal(0L, 48)))
    val copy = header.deepCopy()
    copy.fields["dst"]
    copy.fields["dst"] = BitVal(1L, 48)

    assertEquals(0, DeepCopyFieldStats.totalFieldsCopied.sum())
    assertEquals(0, DeepCopyFieldStats.rawFieldReads.sum())
    assertEquals(0, DeepCopyFieldStats.rawFieldWrites.sum())
  }

  @Test
  fun `report produces readable output`() {
    val header =
      HeaderVal(
        "eth",
        mutableMapOf("dst" to BitVal(0L, 48), "src" to BitVal(0L, 48), "type" to BitVal(0L, 16)),
      )
    val copy = header.deepCopy() // 3 fields copied
    copy.fields["dst"] // 1 read
    copy.fields["src"] = BitVal(1L, 48) // 1 write

    val report = DeepCopyFieldStats.report()
    assertTrue("report should mention total copied fields: $report", report.contains("3"))
    assertTrue("report should mention reads: $report", report.contains("read"))
    assertTrue("report should mention writes: $report", report.contains("write"))
  }
}
