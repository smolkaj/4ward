package fourward.e2e.bmv2

import fourward.e2e.MatchKind
import fourward.e2e.StfAddEntry
import fourward.e2e.StfFile
import fourward.e2e.StfGroupDirective
import fourward.e2e.StfMatchField
import fourward.e2e.StfMemberDirective
import fourward.e2e.StfSetDefault
import fourward.e2e.StfTableDirective
import fourward.e2e.allOnesMask
import fourward.e2e.decodeHex
import fourward.e2e.encodeValue
import fourward.e2e.extractParamName
import fourward.e2e.findAction
import fourward.e2e.findActionProfile
import fourward.e2e.findTable
import fourward.e2e.hex
import fourward.e2e.stripNamedParamPrefix
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import p4.config.v1.P4InfoOuterClass

/**
 * Drives a BMv2 simple_switch subprocess via the bmv2_driver binary.
 *
 * Translates STF directives into the driver's line-based command protocol. Uses P4Info (from the
 * 4ward PipelineConfig) to resolve field names and encode match/action values with correct
 * bitwidths.
 */
class Bmv2Runner(driverBinary: Path, jsonPath: Path, private val p4Info: P4InfoOuterClass.P4Info) :
  Closeable {
  private val process: Process
  private val writer: java.io.BufferedWriter
  private val reader: java.io.BufferedReader

  init {
    process =
      ProcessBuilder(driverBinary.toString(), jsonPath.toString())
        .redirectErrorStream(false)
        .start()
    writer = process.outputStream.bufferedWriter()
    reader = process.inputStream.bufferedReader()
    val ready = reader.readLine()
    check(ready == "READY") { "Expected READY from bmv2_driver, got: $ready" }
  }

  private fun sendCommand(cmd: String): List<String> {
    writer.write(cmd)
    writer.newLine()
    writer.flush()
    val lines = mutableListOf<String>()
    var done = false
    while (!done) {
      val line = reader.readLine()
      if (line == null) {
        done = true
      } else {
        lines.add(line)
        done = line.startsWith("OK") || line.startsWith("ERROR") || line == "DONE"
      }
    }
    return lines
  }

  private fun sendChecked(cmd: String) {
    val resp = sendCommand(cmd)
    check(resp.lastOrNull()?.startsWith("OK") == true) { "$cmd failed: ${resp.joinToString("\n")}" }
  }

  /** Install all STF-declared entries (PRE + table entries). Throws on failure. */
  fun installEntries(stf: StfFile) {
    for (mirror in stf.pre.mirroringAdds) {
      sendChecked("MIRRORING_ADD ${mirror.sessionId} ${mirror.egressPort}")
    }

    // Create nodes before groups (BMv2 assigns sequential handles starting at 0).
    for (node in stf.pre.mcNodeCreates) {
      sendChecked("MC_NODE_CREATE ${node.rid} ${node.ports.joinToString(" ")}")
    }
    for (group in stf.pre.mcGroupCreates) {
      sendChecked("MC_MGRP_CREATE ${group.groupId}")
    }
    for (assoc in stf.pre.mcNodeAssociates) {
      sendChecked("MC_NODE_ASSOCIATE ${assoc.groupId} ${assoc.nodeHandle}")
    }

    // Action profile members and groups must be installed before table entries
    // that reference them.
    for (member in stf.memberDirectives) {
      sendChecked(translateMember(member))
    }
    for (group in stf.groupDirectives) {
      val resp = sendCommand(translateCreateGroup(group))
      check(resp.lastOrNull()?.startsWith("OK") == true) { "create group failed: $resp" }
      val grpHandle = resp.last().removePrefix("OK ").trim().toInt()
      val profileName = findActionProfileName(group.profileName)
      for (memberId in group.memberIds) {
        sendChecked("ACT_PROF_ADD_MEMBER_TO_GROUP $profileName $memberId $grpHandle")
      }
    }

    for (directive in stf.tableEntries) {
      sendChecked(translateTableEntry(directive))
    }
  }

  /** Inject a packet and collect output packets from BMv2. */
  fun sendPacket(port: Int, payload: ByteArray): List<Pair<Int, ByteArray>> {
    val resp = sendCommand("PACKET $port ${payload.hex()}")
    return resp
      .filter { it.startsWith("OUTPUT ") }
      .map { line ->
        val parts = line.split(" ", limit = 3)
        parts[1].toInt() to parts[2].decodeHex()
      }
  }

  // -- Table entry translation --

  private fun translateTableEntry(directive: StfTableDirective): String =
    when (directive) {
      is StfAddEntry -> translateAdd(directive)
      is StfSetDefault -> translateSetDefault(directive)
    }

  @Suppress("CyclomaticComplexMethod")
  private fun translateAdd(entry: StfAddEntry): String {
    val table = findTable(entry.tableName, p4Info)

    // Encode match fields in P4Info table key order.
    val matchParts =
      table.matchFieldsList.map { mf -> encodeMatchForDriver(findStfMatch(entry, mf), mf) }

    // Indirect table entry pointing to a group (action selector with group=N).
    if (entry.groupId != null) {
      return buildString {
        append("TABLE_ADD_GROUP ${table.preamble.name} ${entry.groupId}")
        for (m in matchParts) append(" $m")
        if (entry.priority != null) append(" priority ${entry.priority}")
      }
    }

    val action = findAction(entry.actionName, p4Info)
    val paramParts = encodeActionParams(entry.actionParams, action)

    return buildString {
      append("TABLE_ADD ${table.preamble.name} ${action.preamble.name}")
      for (m in matchParts) append(" $m")
      append(" =>")
      for (p in paramParts) append(" $p")
      if (entry.priority != null) append(" priority ${entry.priority}")
    }
  }

  private fun translateSetDefault(entry: StfSetDefault): String {
    val table = findTable(entry.tableName, p4Info)
    val action = findAction(entry.actionName, p4Info)
    val paramParts = encodeActionParams(entry.actionParams, action)

    return buildString {
      append("TABLE_SET_DEFAULT ${table.preamble.name} ${action.preamble.name}")
      for (p in paramParts) append(" $p")
    }
  }

  // -- Action profile translation --

  private fun translateMember(member: StfMemberDirective): String {
    val profileName = findActionProfileName(member.profileName)
    val action = findAction(member.actionName, p4Info)
    val paramParts = encodeActionParams(memberParamsToList(member.params, action), action)
    return buildString {
      append("ACT_PROF_ADD_MEMBER $profileName ${action.preamble.name}")
      for (p in paramParts) append(" $p")
    }
  }

  private fun translateCreateGroup(group: StfGroupDirective): String =
    "ACT_PROF_CREATE_GROUP ${findActionProfileName(group.profileName)}"

  /** Resolves an STF profile name to the BMv2-visible (p4info) name. */
  private fun findActionProfileName(stfName: String): String =
    findActionProfile(stfName, p4Info).preamble.name

  /** Converts named member params (map) to positional list for encodeActionParams. */
  private fun memberParamsToList(
    params: Map<String, String>,
    action: P4InfoOuterClass.Action,
  ): List<String> =
    action.paramsList.map { "${it.name}=${params[it.name] ?: error("missing ${it.name}")}" }

  private fun encodeActionParams(
    stfParams: List<String>,
    action: P4InfoOuterClass.Action,
  ): List<String> =
    action.paramsList.mapIndexed { i, paramInfo ->
      val raw =
        stfParams.find { it.extractParamName() == paramInfo.name }
          ?: stfParams.getOrNull(i)
          ?: error("missing param ${paramInfo.name} for action ${action.preamble.name}")
      encodeValue(raw.stripNamedParamPrefix(), paramInfo.bitwidth).toByteArray().hex()
    }

  /** Find the STF match field corresponding to a P4Info match field definition. */
  private fun findStfMatch(entry: StfAddEntry, mf: P4InfoOuterClass.MatchField): StfMatchField =
    entry.matches.find { stf ->
      val norm = stf.fieldName.replace(ARRAY_INDEX_REGEX, "[$1]")
      mf.name == stf.fieldName ||
        mf.name == norm ||
        mf.name.substringAfter(".") == stf.fieldName ||
        mf.name.substringAfter(".") == norm
    } ?: error("no STF match for P4Info field '${mf.name}' in table '${entry.tableName}'")

  /** Encode a parsed STF match field for the bmv2_driver's command format. */
  private fun encodeMatchForDriver(stf: StfMatchField, mf: P4InfoOuterClass.MatchField): String {
    val valueHex = encodeValue(stf.value, mf.bitwidth).toByteArray().hex()
    return when (stf.kind) {
      MatchKind.LPM -> "$valueHex/${stf.prefixLen}"
      MatchKind.TERNARY -> {
        val mask = stf.mask
        val maskHex =
          if (mask != null) encodeValue(mask, mf.bitwidth).toByteArray().hex()
          else allOnesMask(mf.bitwidth)
        "$valueHex&&&$maskHex"
      }
      MatchKind.EXACT -> {
        when (mf.matchType) {
          // If the table declares ternary/lpm but the STF value is exact, promote.
          P4InfoOuterClass.MatchField.MatchType.TERNARY -> "$valueHex&&&${allOnesMask(mf.bitwidth)}"
          P4InfoOuterClass.MatchField.MatchType.LPM -> "$valueHex/${mf.bitwidth}"
          else -> valueHex
        }
      }
    }
  }

  @Suppress("MagicNumber")
  override fun close() {
    writer.close()
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
  }

  companion object {
    private val ARRAY_INDEX_REGEX = Regex("\\$(\\d+)")
  }
}
