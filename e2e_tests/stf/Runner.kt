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
      for (directive in stf.tableEntries) {
        val writeReq = resolveTableEntry(directive, config.p4Info)
        sendRequest(output, SimRequest.newBuilder().setWriteEntry(writeReq).build())
        val writeResp = readResponse(input)
        if (writeResp.hasError()) {
          return TestResult.Failure("WriteEntry failed: ${writeResp.error.message}")
        }
      }

      // Send all packets and collect outputs into a FIFO queue, matching
      // BMv2's STF semantics where expects are checked against the global
      // output order (not tied to individual input packets).
      val failures = mutableListOf<String>()
      data class Output(val port: Int, val payload: ByteArray)
      val outputQueue = mutableListOf<Output>()

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
        for (pkt in resp.processPacket.outputPacketsList) {
          outputQueue += Output(pkt.egressPort, pkt.payload.toByteArray())
        }
      }

      // Match expects against the output queue in order.
      for (expected in stf.expects) {
        val idx = outputQueue.indexOfFirst { it.port == expected.port }
        if (idx < 0) {
          failures += "expected packet on port ${expected.port} but got none"
        } else {
          val actual = outputQueue.removeAt(idx)
          if (!actual.payload.matchesMasked(expected.payload, expected.mask)) {
            failures +=
              "port ${expected.port}: payload mismatch\n" +
                "  expected: ${expected.payload.hex(expected.mask)}\n" +
                "  actual:   ${actual.payload.hex()}"
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
    directive: StfTableDirective,
    p4info: P4InfoOuterClass.P4Info,
  ): WriteEntryRequest {
    val table =
      p4info.tablesList.find {
        it.preamble.alias == directive.tableName || it.preamble.name == directive.tableName
      } ?: error("unknown table: ${directive.tableName}")

    val action =
      p4info.actionsList.find {
        it.preamble.alias == directive.actionName || it.preamble.name == directive.actionName
      } ?: error("unknown action: ${directive.actionName}")

    val paramsList =
      action.paramsList.mapIndexed { i, paramInfo ->
        val rawValue =
          directive.actionParams.getOrNull(i)
            ?: error("missing param ${paramInfo.name} for action ${directive.actionName}")
        P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramInfo.id)
          .setValue(encodeValue(rawValue, paramInfo.bitwidth))
          .build()
      }

    val tableEntry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(table.preamble.id)
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(action.preamble.id)
                .addAllParams(paramsList)
            )
        )

    val updateType =
      when (directive) {
        is StfAddEntry -> {
          tableEntry.addAllMatch(
            directive.matches.map { m -> resolveMatchField(m, table, directive.tableName) }
          )
          if (directive.priority != null) tableEntry.setPriority(directive.priority)
          P4RuntimeOuterClass.Update.Type.INSERT
        }
        is StfSetDefault -> {
          tableEntry.setIsDefaultAction(true)
          P4RuntimeOuterClass.Update.Type.MODIFY
        }
      }

    return WriteEntryRequest.newBuilder()
      .setUpdate(
        P4RuntimeOuterClass.Update.newBuilder()
          .setType(updateType)
          .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(tableEntry))
      )
      .build()
  }

  private fun resolveMatchField(
    m: StfMatchField,
    table: P4InfoOuterClass.Table,
    tableName: String,
  ): P4RuntimeOuterClass.FieldMatch {
    val mf =
      table.matchFieldsList.find { it.name == m.fieldName }
        ?: error("unknown match field '${m.fieldName}' in table '$tableName'")
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
    return fmBuilder.build()
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
 * Looks for `_main/simulator/simulator`, `_main/<pkg>/<testName>.txtpb`, and
 * `_main/<pkg>/<testName>.stf` under `JAVA_RUNFILES`. The [pkg] defaults to `e2e_tests/<testName>`
 * (matching the per-test package layout of the regular e2e tests).
 */
fun runStfTest(testName: String, pkg: String = "e2e_tests/$testName"): TestResult {
  val r = System.getenv("JAVA_RUNFILES") ?: "."
  return runStf(
    r,
    Paths.get(r, "_main/$pkg/$testName.txtpb"),
    Paths.get(r, "_main/$pkg/$testName.stf"),
  )
}

fun runStf(runfiles: String, configPath: Path, stfPath: Path): TestResult =
  StfRunner(Paths.get(runfiles, "_main/simulator/simulator"), configPath).run(stfPath)

/** A parsed .stf file. */
data class StfFile(
  val tableEntries: List<StfTableDirective>,
  val packets: List<StfPacket>,
  val expects: List<StfExpectedOutput>,
) {
  companion object {
    /**
     * Parses an STF file. Supported directives:
     * - `packet <port> <hex bytes>` — send a packet on ingress port
     * - `expect <port> <hex bytes>` — expect a packet on egress port
     * - `add <table> [priority] <field:value>... <action(params)>` — install table entry
     * - `setdefault <table> <action(params)>` — override a table's default action
     * - `# comment`
     */
    fun parse(path: Path): StfFile {
      val lines =
        path
          .toFile()
          .readLines()
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") }

      val tableEntries = mutableListOf<StfTableDirective>()
      val packets = mutableListOf<StfPacket>()
      val expects = mutableListOf<StfExpectedOutput>()

      for (line in lines) {
        val tokens = line.split(Regex("\\s+"))
        when (tokens[0].lowercase()) {
          "packet" -> {
            val port = tokens[1].toInt()
            val payload = tokens.drop(2).joinToString("").decodeHex()
            packets += StfPacket(port, payload)
          }
          "expect" -> {
            val port = tokens[1].toInt()
            // "$" marks end-of-packet (length assertion); our runner already does exact-length
            // comparison, so strip it. It can appear standalone or appended to the last byte.
            val hexStr = tokens.drop(2).joinToString("").replace("$", "")
            val (payload, mask) = decodeExpect(hexStr)
            expects += StfExpectedOutput(port, payload, mask)
          }
          "add" -> tableEntries += parseAdd(tokens.drop(1))
          "setdefault" -> tableEntries += parseSetDefault(tokens.drop(1))
        }
      }

      return StfFile(tableEntries, packets, expects)
    }

    /**
     * Parses the tokens after "add".
     *
     * Format: `TABLE [PRIORITY] FIELD:VALUE[/PREFIXLEN|&&&MASK]... ACTION([PARAMS])`
     *
     * p4testgen quotes identifiers (`"table"`, `"action"`, `"field"`), so we strip quotes
     * throughout.
     */
    private fun parseAdd(tokens: List<String>): StfAddEntry {
      require(tokens.isNotEmpty()) { "add directive missing table name" }
      val tableName = tokens[0].unquote()
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

      return StfAddEntry(tableName, priority, matches, actionName, actionParams)
    }

    /**
     * Parses the tokens after "setdefault".
     *
     * Format: `TABLE ACTION([PARAMS])`
     */
    private fun parseSetDefault(tokens: List<String>): StfSetDefault {
      require(tokens.isNotEmpty()) { "setdefault directive missing table name" }
      val tableName = tokens[0].unquote()
      val actionSpec = tokens.drop(1).joinToString(" ")
      require(actionSpec.isNotEmpty()) { "setdefault directive missing action" }
      val (actionName, actionParams) = parseActionSpec(actionSpec)
      return StfSetDefault(tableName, actionName, actionParams)
    }

    /** Parses `FIELD:VALUE[/PREFIXLEN]` or `FIELD:VALUE[&&&MASK]`. */
    private fun parseMatchField(token: String): StfMatchField {
      val colonIdx = token.indexOf(':')
      require(colonIdx > 0) { "invalid match field token: $token" }
      val fieldName = token.substring(0, colonIdx).unquote()
      val rest = token.substring(colonIdx + 1)

      // p4testgen uses binary wildcards: 0b1010**** where * bits are don't-care.
      // Convert to value/mask ternary representation.
      if (rest.startsWith("0b") && rest.contains('*')) {
        val (value, mask) = parseBinaryWildcard(rest)
        return StfMatchField(fieldName, MatchKind.TERNARY, value, mask = mask)
      }

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

    /**
     * Parses `actionName(param1, param2, ...)` and returns the name and param list.
     *
     * p4testgen uses named params: `"action"("p1":val1,"p2":val2)`. We strip quotes from the action
     * name and extract just the value part of each named param.
     */
    private fun parseActionSpec(spec: String): Pair<String, List<String>> {
      val parenIdx = spec.indexOf('(')
      require(parenIdx > 0) { "invalid action spec: $spec" }
      val name = spec.substring(0, parenIdx).trim().unquote()
      val paramStr = spec.substring(parenIdx + 1).trimEnd(')', ' ')
      val params =
        if (paramStr.isBlank()) emptyList()
        else
          paramStr
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.stripNamedParamPrefix() }
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
      raw.startsWith("0b") || raw.startsWith("0B") -> BigInteger(raw.drop(2), 2)
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
)

class StfExpectedOutput(val port: Int, val payload: ByteArray, val mask: ByteArray)

/** A parsed table directive (`add` or `setdefault`), before p4info resolution. */
sealed interface StfTableDirective {
  val tableName: String
  val actionName: String
  val actionParams: List<String>
}

data class StfAddEntry(
  override val tableName: String,
  val priority: Int?,
  val matches: List<StfMatchField>,
  override val actionName: String,
  override val actionParams: List<String>,
) : StfTableDirective

data class StfSetDefault(
  override val tableName: String,
  override val actionName: String,
  override val actionParams: List<String>,
) : StfTableDirective

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

/** Strips surrounding double-quotes, if present. */
private fun String.unquote(): String = removeSurrounding("\"")

/** Strips a `"name":` prefix from a p4testgen named action parameter, returning just the value. */
private fun String.stripNamedParamPrefix(): String {
  // p4testgen: quoted named params like "param":value
  if (startsWith('"')) {
    val sep = indexOf("\":", 1)
    return if (sep >= 0) substring(sep + 2) else this
  }
  // Unquoted named params like param:value (used in standard STF files).
  val colon = indexOf(':')
  if (colon > 0 && substring(0, colon).all { it.isLetterOrDigit() || it == '_' }) {
    return substring(colon + 1)
  }
  return this
}

/**
 * Parses a binary wildcard string like `0b1010****` into hex value and mask strings.
 *
 * Each `*` bit becomes a 0 in the value and a 0 in the mask; each `0`/`1` bit becomes its value in
 * the value and a 1 in the mask. Returns hex strings for consistency with the other match value
 * representations (all match values flow through [encodeValue] during p4info resolution).
 */
private fun parseBinaryWildcard(binStr: String): Pair<String, String> {
  val bits = binStr.removePrefix("0b").removePrefix("0B")
  val valueBits = StringBuilder(bits.length)
  val maskBits = StringBuilder(bits.length)
  for (ch in bits) {
    when (ch) {
      '1' -> {
        valueBits.append('1')
        maskBits.append('1')
      }
      '0' -> {
        valueBits.append('0')
        maskBits.append('1')
      }
      '*' -> {
        valueBits.append('0')
        maskBits.append('0')
      }
      else -> error("unexpected character in binary wildcard: $ch")
    }
  }
  val v = if (valueBits.isEmpty()) BigInteger.ZERO else BigInteger(valueBits.toString(), 2)
  val m = if (maskBits.isEmpty()) BigInteger.ZERO else BigInteger(maskBits.toString(), 2)
  return "0x${v.toString(16)}" to "0x${m.toString(16)}"
}

private fun String.decodeHex(): ByteArray {
  val clean = replace(" ", "").lowercase()
  return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * Parses a hex string that may contain `*` wildcard characters. Returns a (payload, mask) pair
 * where each nibble is independently masked: a concrete nibble gets mask 0xF, a `*` gets 0x0.
 *
 * This handles both hand-written STFs (which use `**` for a full wildcard byte) and p4testgen
 * output (which uses `*` per nibble). The `**` case is just two consecutive wildcard nibbles, so
 * the nibble-level logic handles both uniformly.
 */
private fun decodeExpect(hexStr: String): Pair<ByteArray, ByteArray> {
  val clean = hexStr.replace(" ", "").lowercase()
  require(clean.length % 2 == 0) { "odd-length hex in expect: $hexStr" }
  val n = clean.length / 2
  val payload = ByteArray(n)
  val mask = ByteArray(n)
  for (i in 0 until n) {
    val hi = clean[i * 2]
    val lo = clean[i * 2 + 1]
    val hiVal = if (hi == '*') 0 else hi.digitToInt(16)
    val loVal = if (lo == '*') 0 else lo.digitToInt(16)
    val hiMask = if (hi == '*') 0 else 0xF
    val loMask = if (lo == '*') 0 else 0xF
    payload[i] = ((hiVal shl 4) or loVal).toByte()
    mask[i] = ((hiMask shl 4) or loMask).toByte()
  }
  return payload to mask
}

/** Returns true iff every non-wildcard byte (mask != 0) matches expected. */
private fun ByteArray.matchesMasked(expected: ByteArray, mask: ByteArray): Boolean {
  if (size != expected.size) return false
  return indices.all { i ->
    (this[i].toInt() and mask[i].toInt()) == (expected[i].toInt() and mask[i].toInt())
  }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** Like [hex] but shows `**` for wildcard bytes (mask byte == 0). */
private fun ByteArray.hex(mask: ByteArray): String =
  indices.joinToString("") { i -> if (mask[i] == 0.toByte()) "**" else "%02x".format(this[i]) }
