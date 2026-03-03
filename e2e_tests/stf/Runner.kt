package fourward.e2e

import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.LoadPipelineRequest
import fourward.sim.v1.ProcessPacketRequest
import fourward.sim.v1.SimRequest
import fourward.sim.v1.SimResponse
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path

/**
 * Runs a single STF test against the 4ward simulator.
 *
 * An STF (Simple Test Framework) test consists of:
 *   - A compiled PipelineConfig proto file (produced by p4c-4ward).
 *   - A .stf file with table entries, input packets, and expected output packets.
 *
 * The runner:
 *   1. Launches the simulator subprocess.
 *   2. Loads the pipeline config.
 *   3. Installs table entries.
 *   4. Sends each input packet and compares output to expected.
 *   5. Reports pass/fail.
 */
class StfRunner(
    private val simulatorBinary: Path,
    private val pipelineConfigPath: Path,
) {
    fun run(stfPath: Path): TestResult {
        val stf = StfFile.parse(stfPath)
        val builder = PipelineConfig.newBuilder()
        com.google.protobuf.TextFormat.merge(pipelineConfigPath.toFile().readText(), builder)
        val config = builder.build()

        val process = ProcessBuilder(simulatorBinary.toString())
            .redirectErrorStream(false)
            .start()

        val input  = DataInputStream(process.inputStream.buffered())
        val output = DataOutputStream(process.outputStream.buffered())

        try {
            // Load the pipeline.
            sendRequest(output, SimRequest.newBuilder()
                .setLoadPipeline(LoadPipelineRequest.newBuilder().setConfig(config))
                .build())
            val loadResp = readResponse(input)
            if (loadResp.hasError()) {
                return TestResult.Failure("LoadPipeline failed: ${loadResp.error.message}")
            }

            // Install table entries.
            for (entry in stf.tableEntries) {
                sendRequest(output, SimRequest.newBuilder().setWriteEntry(entry).build())
                val writeResp = readResponse(input)
                if (writeResp.hasError()) {
                    return TestResult.Failure("WriteEntry failed: ${writeResp.error.message}")
                }
            }

            // Send packets and check outputs.
            val failures = mutableListOf<String>()
            for (packet in stf.packets) {
                sendRequest(output, SimRequest.newBuilder()
                    .setProcessPacket(
                        ProcessPacketRequest.newBuilder()
                            .setIngressPort(packet.ingressPort)
                            .setPayload(com.google.protobuf.ByteString.copyFrom(packet.payload))
                    )
                    .build())
                val resp = readResponse(input)
                if (resp.hasError()) {
                    failures += "ProcessPacket failed: ${resp.error.message}"
                    continue
                }
                val result = resp.processPacket

                for (expected in packet.expectedOutputs) {
                    val actual = result.outputPacketsList.find { it.egressPort == expected.port }
                    when {
                        actual == null ->
                            failures += "expected packet on port ${expected.port} but got none"
                        !actual.payload.toByteArray().contentEquals(expected.payload) ->
                            failures += "port ${expected.port}: payload mismatch\n" +
                                "  expected: ${expected.payload.hex()}\n" +
                                "  actual:   ${actual.payload.toByteArray().hex()}"
                    }
                }
            }

            return if (failures.isEmpty()) TestResult.Pass else TestResult.Failure(failures.joinToString("\n"))
        } finally {
            process.destroy()
        }
    }

    private fun sendRequest(output: DataOutputStream, request: SimRequest) {
        val bytes = request.toByteArray()
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readResponse(input: DataInputStream): SimResponse {
        val length = input.readInt()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return SimResponse.parseFrom(bytes)
    }
}

sealed class TestResult {
    object Pass : TestResult()
    data class Failure(val message: String) : TestResult()
}

/** A parsed .stf file. */
data class StfFile(
    val tableEntries: List<fourward.sim.v1.WriteEntryRequest>,
    val packets: List<StfPacket>,
) {
    companion object {
        /**
         * Parses a minimal STF file. Supported directives:
         *   packet <port> <hex bytes>          — send a packet on ingress port
         *   expect <port> <hex bytes>          — expect a packet on egress port
         *   add <table> <key:value> <action>   — install a table entry (TODO)
         *   # comment
         */
        fun parse(path: Path): StfFile {
            val lines = path.toFile().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            val packets = mutableListOf<StfPacket>()
            var current: StfPacket? = null

            for (line in lines) {
                val parts = line.split(Regex("\\s+"), limit = 3)
                when (parts[0].lowercase()) {
                    "packet" -> {
                        current?.let { packets += it }
                        val port = parts[1].toInt()
                        val payload = parts.getOrNull(2)?.decodeHex() ?: byteArrayOf()
                        current = StfPacket(port, payload, mutableListOf())
                    }
                    "expect" -> {
                        val port = parts[1].toInt()
                        val payload = parts.getOrNull(2)?.decodeHex() ?: byteArrayOf()
                        current?.expectedOutputs?.add(StfExpectedOutput(port, payload))
                    }
                    // TODO: "add" for table entries
                }
            }
            current?.let { packets += it }

            return StfFile(emptyList(), packets)
        }
    }
}

data class StfPacket(
    val ingressPort: Int,
    val payload: ByteArray,
    val expectedOutputs: MutableList<StfExpectedOutput>,
)

data class StfExpectedOutput(val port: Int, val payload: ByteArray)

private fun String.decodeHex(): ByteArray {
    val clean = replace(" ", "")
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
