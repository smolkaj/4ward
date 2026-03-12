package fourward.e2e.bmv2

import fourward.e2e.StfFile
import fourward.e2e.hex
import fourward.e2e.installStfEntries
import fourward.e2e.loadPipelineConfig
import fourward.simulator.Simulator
import java.io.File
import java.nio.file.Paths
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Differential test: runs STF test vectors through both the 4ward simulator and BMv2's
 * simple_switch, then compares output packets bit-for-bit.
 *
 * Both simulators receive the same table entries and input packets (translated from each STF file).
 * Output packets are sorted by (port, payload) before comparison, since cross-port ordering is
 * unspecified.
 */
@RunWith(Parameterized::class)
class Bmv2DiffTest(private val testName: String) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun testCases(): List<Array<String>> {
      val r = System.getenv("JAVA_RUNFILES") ?: "."
      val dir = File("$r/_main/e2e_tests/bmv2_diff")
      return dir
        .listFiles { f -> f.extension == "stf" }
        ?.map { arrayOf(it.nameWithoutExtension) }
        ?.sortedBy { it[0] } ?: emptyList()
    }
  }

  @Test
  fun test() {
    val r = System.getenv("JAVA_RUNFILES") ?: "."
    val pkg = "e2e_tests/bmv2_diff"
    val stfPath = Paths.get(r, "_main/$pkg/$testName.stf")
    val jsonPath = Paths.get(r, "_main/$pkg/$testName.json")
    val configPath = Paths.get(r, "_main/$pkg/$testName.txtpb")
    val driverBinary = Paths.get(r, "_main/$pkg/bmv2_driver")

    val stf = StfFile.parse(stfPath)
    val config = loadPipelineConfig(configPath)

    // --- Run through 4ward ---
    val fourwardOutputs = mutableListOf<Pair<Int, ByteArray>>()
    val sim = Simulator()
    sim.loadPipeline(config)
    installStfEntries(sim, stf, config.p4Info)
    for (packet in stf.packets) {
      val result = sim.processPacket(packet.ingressPort, packet.payload)
      for (pkt in result.outputPackets) {
        fourwardOutputs.add(pkt.egressPort to pkt.payload.toByteArray())
      }
    }

    // --- Run through BMv2 ---
    val bmv2Outputs = mutableListOf<Pair<Int, ByteArray>>()
    Bmv2Runner(driverBinary, jsonPath, config.p4Info).use { bmv2 ->
      bmv2.installEntries(stf)
      for (packet in stf.packets) {
        // sendPacketExploring handles action selectors via round-robin exploration
        // (temporarily reducing groups to single members); falls back to a normal
        // sendPacket when no selector groups are present.
        bmv2Outputs.addAll(bmv2.sendPacketExploring(packet.ingressPort, packet.payload))
      }
    }

    // --- Compare outputs ---
    // Sort by (port, payload hex) for deterministic comparison since cross-port
    // ordering is unspecified by both simulators.
    val sortKey: (Pair<Int, ByteArray>) -> String = { (port, payload) ->
      "%04d:%s".format(port, payload.hex())
    }
    val fourwardSorted = fourwardOutputs.sortedBy(sortKey)
    val bmv2Sorted = bmv2Outputs.sortedBy(sortKey)

    val mismatches = mutableListOf<String>()
    if (fourwardSorted.size != bmv2Sorted.size) {
      mismatches.add("Output count mismatch: 4ward=${fourwardSorted.size}, bmv2=${bmv2Sorted.size}")
    }
    val n = minOf(fourwardSorted.size, bmv2Sorted.size)
    for (i in 0 until n) {
      val (fPort, fPayload) = fourwardSorted[i]
      val (bPort, bPayload) = bmv2Sorted[i]
      if (fPort != bPort || !fPayload.contentEquals(bPayload)) {
        mismatches.add(
          "Output $i differs:\n" +
            "  4ward: port=$fPort payload=${fPayload.hex()}\n" +
            "  bmv2:  port=$bPort payload=${bPayload.hex()}"
        )
      }
    }

    if (mismatches.isNotEmpty()) {
      Assert.fail(mismatches.joinToString("\n"))
    }
  }
}
