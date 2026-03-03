package fourward.e2e

import com.google.protobuf.ByteString
import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.LoadPipelineRequest
import fourward.sim.v1.ProcessPacketRequest
import fourward.sim.v1.SimRequest
import fourward.sim.v1.SimResponse
import fourward.sim.v1.WriteEntryRequest
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.file.Path
import java.nio.file.Paths
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Runs a single STF test against the 4ward simulator.
 *
 * An STF (Simple Test Framework) test consists of:
 * - A compiled PipelineConfig proto file (produced by p4c-4ward).
 * - A .stf file with table entries, input packets, and expected output packets.
 *
 * The runner:
 * 1. Launches the simulator subprocess.
 * 2. Loads the pipeline config.
 * 3. Installs table entries.
 * 4. Sends each input packet and compares output to expected.
 * 5. Reports pass/fail.
 */
class StfRunner(private val simulatorBinary: Path, private val pipelineConfigPath: Path) {
  // The run function is a single sequential protocol: load pipeline → install entries →
  // send packets → compare. Splitting it would require passing mutable state between
  // helper functions, making the protocol harder to follow.
  @Suppress("NestedBlockDepth")
  fun run(stfPath: Path): TestResult {
    val stf = StfFile.parse(stfPath)
    val builder = PipelineConfig.newBuilder()
    com.google.protobuf.TextFormat.merge(pipelineConfigPath.toFile().readText(), builder)
    val config = builder.build()

    val process = ProcessBuilder(simulatorBinary.toString()).redirectErrorStream(false).start()

    val input = DataInputStream(process.inputStream.buffered())
    val output = DataOutputStream(process.outputStream.buffered())

    try {
      // Load the pipeline.
      sendRequest(
        output,
        SimRequest.newBuilder()
          .setLoadPipeline(LoadPipelineRequest.newBuilder().setConfig(config))
          .build(),
      )
      val loadResp = readResponse(input)
      if (loadResp.hasError()) {
        return TestResult.Failure("LoadPipeline failed: ${loadResp.error.message}")
      }

      // Install table entries.
      for (stfEntry in stf.tableEntries) {
        val writeReq = resolveTableEntry(stfEntry, config.p4Info)
        sendRequest(output, SimRequest.newBuilder().setWriteEntry(writeReq).build())
        val writeResp = readResponse(input)
        if (writeResp.hasError()) {
          return TestResult.Failure("WriteEntry failed: ${writeResp.error.message}")
        }
      }

      // Send packets and check outputs.
      val failures = mutableListOf<String>()
      for (packet in stf.packets) {
        sendRequest(
          output,
          SimRequest.newBuilder()
            .setProcessPacket(
              ProcessPacketRequest.newBuilder()
                .setIngressPort(packet.ingressPort)
                .setPayload(com.google.protobuf.ByteString.copyFrom(packet.payload))
            )
            .build(),
        )
        val resp = readResponse(input)
        if (resp.hasError()) {
          failures += "ProcessPacket failed: ${resp.error.message}"
          continue
        }
        val result = resp.processPacket

        for (expected in packet.expectedOutputs) {
          val actual = result.outputPacketsList.find { it.egressPort == expected.port }
          when {
            actual == null -> failures += "expected packet on port ${expected.port} but got none"
            !actual.payload.toByteArray().contentEquals(expected.payload) ->
              failures +=
                "port ${expected.port}: payload mismatch\n" +
                  "  expected: ${expected.payload.hex()}\n" +
                  "  actual:   ${actual.payload.toByteArray().hex()}"
          }
        }
      }

      return if (failures.isEmpty()) TestResult.Pass
      else TestResult.Failure(failures.joinToString("\n"))
    } finally {
      process.destroy()
    }
  }

  private fun resolveTableEntry(
    stfEntry: StfTableEntry,
    p4info: P4InfoOuterClass.P4Info,
  ): WriteEntryRequest {
    val table =
      p4info.tablesList.find {
        it.preamble.alias == stfEntry.tableName || it.preamble.name == stfEntry.tableName
      } ?: error("unknown table: ${stfEntry.tableName}")

    val matchList =
      stfEntry.matches.map { m ->
        val mf =
          table.matchFieldsList.find { it.name == m.fieldName }
            ?: error("unknown match field '${m.fieldName}' in table '${stfEntry.tableName}'")
        val fmBuilder = P4RuntimeOuterClass.FieldMatch.newBuilder().setFieldId(mf.id)
        when (m.kind) {
          MatchKind.EXACT ->
            fmBuilder.setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(encodeValue(m.value, mf.bitwidth))
            )
          MatchKind.LPM ->
            fmBuilder.setLpm(
              P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
                .setValue(encodeValue(m.value, mf.bitwidth))
                .setPrefixLen(m.prefixLen!!)
            )
          MatchKind.TERNARY ->
            fmBuilder.setTernary(
              P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
                .setValue(encodeValue(m.value, mf.bitwidth))
                .setMask(encodeValue(m.mask!!, mf.bitwidth))
            )
        }
        fmBuilder.build()
      }

    val action =
      p4info.actionsList.find {
        it.preamble.alias == stfEntry.actionName || it.preamble.name == stfEntry.actionName
      } ?: error("unknown action: ${stfEntry.actionName}")

    val paramsList =
      action.paramsList.mapIndexed { i, paramInfo ->
        val rawValue =
          stfEntry.actionParams.getOrNull(i)
            ?: error("missing param ${paramInfo.name} for action ${stfEntry.actionName}")
        P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramInfo.id)
          .setValue(encodeValue(rawValue, paramInfo.bitwidth))
          .build()
      }

    val tableEntry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .addAllMatch(matchList)
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(action.preamble.id)
                .addAllParams(paramsList)
            )
        )

    if (stfEntry.priority != null) tableEntry.setPriority(stfEntry.priority)

    return WriteEntryRequest.newBuilder()
      .setUpdate(
        P4RuntimeOuterClass.Update.newBuilder()
          .setType(P4RuntimeOuterClass.Update.Type.INSERT)
          .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(tableEntry))
      )
      .build()
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

/**
 * Runs the STF test named [testName] using the standard Bazel runfiles layout.
 *
 * Looks for `_main/simulator/simulator`, `_main/e2e_tests/<testName>/<testName>.txtpb`, and
 * `_main/e2e_tests/<testName>/<testName>.stf` under `JAVA_RUNFILES`.
 */
fun runStfTest(testName: String): TestResult {
  val r = System.getenv("JAVA_RUNFILES") ?: "."
  return runStf(
    r,
    Paths.get(r, "_main/e2e_tests/$testName/$testName.txtpb"),
    Paths.get(r, "_main/e2e_tests/$testName/$testName.stf"),
  )
}

/**
 * Runs an STF test whose identity is derived from Bazel's `TEST_TARGET` environment variable.
 *
 * Bazel sets `TEST_TARGET` to the fully qualified label of the running test, e.g.
 * `//e2e_tests/corpus:opassign1-bmv2_test`. This function strips the `_test` suffix to obtain the
 * test name, then looks up `<name>.txtpb` and `<name>.stf` in the same package under
 * `JAVA_RUNFILES/_main/`. Used by [fourward.e2e.corpus.CorpusStfTest] via the `p4_stf_test` Bazel
 * macro.
 */
fun runStfTestFromEnv(): TestResult {
  val r = System.getenv("JAVA_RUNFILES") ?: "."
  val target = System.getenv("TEST_TARGET") ?: error("TEST_TARGET not set")
  // "//e2e_tests/corpus:opassign1-bmv2_test" → pkg = "e2e_tests/corpus", name = "opassign1-bmv2"
  val pkg = target.removePrefix("//").substringBefore(":")
  val testName = target.substringAfterLast(":").removeSuffix("_test")
  return runStf(
    r,
    Paths.get(r, "_main/$pkg/$testName.txtpb"),
    Paths.get(r, "_main/$pkg/$testName.stf"),
  )
}

private fun runStf(runfiles: String, configPath: Path, stfPath: Path): TestResult =
  StfRunner(Paths.get(runfiles, "_main/simulator/simulator"), configPath).run(stfPath)

/** A parsed .stf file. */
data class StfFile(val tableEntries: List<StfTableEntry>, val packets: List<StfPacket>) {
  companion object {
    /**
     * Parses an STF file. Supported directives:
     * - `packet <port> <hex bytes>` — send a packet on ingress port
     * - `expect <port> <hex bytes>` — expect a packet on egress port
     * - `add <table> [priority] <field:value>... <action(params)>` — install table entry
     * - `# comment`
     */
    fun parse(path: Path): StfFile {
      val lines =
        path
          .toFile()
          .readLines()
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") }

      val tableEntries = mutableListOf<StfTableEntry>()
      val packets = mutableListOf<StfPacket>()
      var current: StfPacket? = null

      for (line in lines) {
        val tokens = line.split(Regex("\\s+"))
        when (tokens[0].lowercase()) {
          "packet" -> {
            current?.let { packets += it }
            val port = tokens[1].toInt()
            val payload = tokens.drop(2).joinToString("").decodeHex()
            current = StfPacket(port, payload, mutableListOf())
          }
          "expect" -> {
            val port = tokens[1].toInt()
            val payload = tokens.drop(2).joinToString("").decodeHex()
            current?.expectedOutputs?.add(StfExpectedOutput(port, payload))
          }
          "add" -> {
            current?.let {
              packets += it
              current = null
            }
            tableEntries += parseAdd(tokens.drop(1))
          }
        }
      }
      current?.let { packets += it }

      return StfFile(tableEntries, packets)
    }

    /**
     * Parses the tokens after "add".
     *
     * Format: `TABLE [PRIORITY] FIELD:VALUE[/PREFIXLEN|&&&MASK]... ACTION([PARAMS])`
     */
    private fun parseAdd(tokens: List<String>): StfTableEntry {
      require(tokens.isNotEmpty()) { "add directive missing table name" }
      val tableName = tokens[0]
      var idx = 1

      // Optional priority: a token that is a plain integer (no ':' or '(').
      var priority: Int? = null
      if (idx < tokens.size && tokens[idx].matches(Regex("\\d+"))) {
        priority = tokens[idx].toInt()
        idx++
      }

      // Collect match fields until we hit a token containing '(' (the action).
      val matches = mutableListOf<StfMatchField>()
      while (idx < tokens.size && !tokens[idx].contains('(')) {
        matches += parseMatchField(tokens[idx])
        idx++
      }

      // The remaining token(s) form the action spec: name(param1, param2, ...).
      // Join remaining tokens in case the action spans multiple tokens (e.g. spaces in params).
      val actionSpec = tokens.drop(idx).joinToString(" ")
      require(actionSpec.isNotEmpty()) { "add directive missing action" }
      val (actionName, actionParams) = parseActionSpec(actionSpec)

      return StfTableEntry(tableName, priority, matches, actionName, actionParams)
    }

    /** Parses `FIELD:VALUE[/PREFIXLEN]` or `FIELD:VALUE[&&&MASK]`. */
    private fun parseMatchField(token: String): StfMatchField {
      val colonIdx = token.indexOf(':')
      require(colonIdx > 0) { "invalid match field token: $token" }
      val fieldName = token.substring(0, colonIdx)
      val rest = token.substring(colonIdx + 1)

      return when {
        rest.contains("/") -> {
          val (value, prefixLen) = rest.split("/", limit = 2)
          StfMatchField(fieldName, MatchKind.LPM, value, prefixLen = prefixLen.toInt())
        }
        rest.contains("&&&") -> {
          val (value, mask) = rest.split("&&&", limit = 2)
          StfMatchField(fieldName, MatchKind.TERNARY, value, mask = mask)
        }
        else -> StfMatchField(fieldName, MatchKind.EXACT, rest)
      }
    }

    /** Parses `actionName(param1, param2, ...)` and returns the name and param list. */
    private fun parseActionSpec(spec: String): Pair<String, List<String>> {
      val parenIdx = spec.indexOf('(')
      require(parenIdx > 0) { "invalid action spec: $spec" }
      val name = spec.substring(0, parenIdx).trim()
      val paramStr = spec.substring(parenIdx + 1).trimEnd(')', ' ')
      val params =
        if (paramStr.isBlank()) emptyList()
        else paramStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      return name to params
    }
  }
}

// ---------------------------------------------------------------------------
// Encoding helpers
// ---------------------------------------------------------------------------

/**
 * Encodes a decimal, 0x-prefixed hex, or dotted-decimal IPv4 string into a big-endian byte array of
 * the right width.
 */
fun encodeValue(raw: String, bitwidth: Int): ByteString {
  val value =
    when {
      raw.startsWith("0x") || raw.startsWith("0X") -> BigInteger(raw.drop(2), 16)
      raw.contains('.') -> {
        // Dotted-decimal IPv4 (e.g. "10.0.0.0"): pack octets into a 32-bit integer.
        val octets = raw.split('.')
        require(octets.size == 4) { "invalid IPv4 address in STF: $raw" }
        octets.fold(BigInteger.ZERO) { acc, octet -> acc.shiftLeft(8).add(BigInteger(octet)) }
      }
      else -> BigInteger(raw)
    }
  val byteLen = (bitwidth + 7) / 8
  val bigEndian = value.toByteArray()
  // BigInteger.toByteArray() may include a leading 0x00 sign byte.
  val result =
    ByteArray(byteLen) { i ->
      val srcIdx = bigEndian.size - byteLen + i
      if (srcIdx < 0) 0 else bigEndian[srcIdx]
    }
  return ByteString.copyFrom(result)
}

// ---------------------------------------------------------------------------
// Data types
// ---------------------------------------------------------------------------

data class StfPacket(
  val ingressPort: Int,
  val payload: ByteArray,
  val expectedOutputs: MutableList<StfExpectedOutput>,
)

data class StfExpectedOutput(val port: Int, val payload: ByteArray)

/** A parsed `add` directive, before p4info resolution. */
data class StfTableEntry(
  val tableName: String,
  val priority: Int?,
  val matches: List<StfMatchField>,
  val actionName: String,
  val actionParams: List<String>,
)

data class StfMatchField(
  val fieldName: String,
  val kind: MatchKind,
  val value: String,
  val prefixLen: Int? = null,
  val mask: String? = null,
)

enum class MatchKind {
  EXACT,
  LPM,
  TERNARY,
}

private fun String.decodeHex(): ByteArray {
  val clean = replace(" ", "").lowercase()
  return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
