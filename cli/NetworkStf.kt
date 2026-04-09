package fourward.cli

import fourward.e2e.decodeHex
import fourward.simulator.Endpoint
import fourward.simulator.Link
import java.nio.file.Path

/**
 * A parsed `.nstf` (network STF) file describing a multi-switch test scenario.
 *
 * Format:
 * ```
 * switch <id> <pipeline.txtpb> [entries.stf]
 * link <switch:port> <switch:port>
 * <switchId>: <stf directive>
 * packet <switch:port> <hex bytes>
 * expect <switch:port> <hex bytes>
 * ```
 *
 * The STF file in `switch` declarations is optional. Per-switch table entries can be provided
 * inline using `<switchId>: <stf directive>` syntax (e.g., `s1: add port_table ...`). Both external
 * STF files and inline entries can be used together — inline entries are applied after external
 * ones.
 *
 * Pipeline and STF paths are resolved relative to the `.nstf` file's directory.
 */
data class NetworkStf(
  val switches: List<SwitchDecl>,
  val links: List<Link>,
  val inlineEntries: Map<String, List<String>>,
  val packets: List<NetworkPacket>,
  val expects: List<NetworkExpect>,
) {
  data class SwitchDecl(val id: String, val pipelinePath: Path, val stfPath: Path?)

  data class NetworkPacket(val endpoint: Endpoint, val payload: ByteArray)

  data class NetworkExpect(val endpoint: Endpoint, val payload: ByteArray)

  companion object {
    private val WHITESPACE = "\\s+".toRegex()

    fun parse(path: Path): NetworkStf {
      val dir = path.parent ?: Path.of(".")
      val rawLines = path.toFile().readLines()

      val switches = mutableListOf<SwitchDecl>()
      val links = mutableListOf<Link>()
      val inlineEntries = mutableMapOf<String, MutableList<String>>()
      val packets = mutableListOf<NetworkPacket>()
      val expects = mutableListOf<NetworkExpect>()

      for ((idx, rawLine) in rawLines.withIndex()) {
        val lineNum = idx + 1
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) continue

        val tokens = line.split(WHITESPACE)
        when {
          tokens[0] == "switch" -> {
            require(tokens.size in 3..4) { "line $lineNum: switch needs <id> <pipeline> [stf]" }
            val stfPath = if (tokens.size == 4) dir.resolve(tokens[3]) else null
            switches.add(SwitchDecl(tokens[1], dir.resolve(tokens[2]), stfPath))
          }
          tokens[0] == "link" -> {
            require(tokens.size == 3) { "line $lineNum: link needs <switch:port> <switch:port>" }
            links.add(Link(parseEndpoint(tokens[1], lineNum), parseEndpoint(tokens[2], lineNum)))
          }
          tokens[0] == "packet" -> {
            require(tokens.size >= 3) { "line $lineNum: packet needs <switch:port> <hex>" }
            packets.add(
              NetworkPacket(
                parseEndpoint(tokens[1], lineNum),
                tokens.drop(2).joinToString("").decodeHex(),
              )
            )
          }
          tokens[0] == "expect" -> {
            require(tokens.size >= 3) { "line $lineNum: expect needs <switch:port> <hex>" }
            expects.add(
              NetworkExpect(
                parseEndpoint(tokens[1], lineNum),
                tokens.drop(2).joinToString("").decodeHex(),
              )
            )
          }
          // Inline STF directive: "<switchId>: <stf line>"
          tokens[0].endsWith(":") -> {
            val switchId = tokens[0].removeSuffix(":")
            val stfLine = line.substringAfter(":").trim()
            require(stfLine.isNotEmpty()) {
              "line $lineNum: empty inline directive for switch '$switchId'"
            }
            inlineEntries.getOrPut(switchId) { mutableListOf() }.add(stfLine)
          }
          else -> error("line $lineNum: unknown directive '${tokens[0]}'")
        }
      }

      require(switches.isNotEmpty()) { "no switches declared" }

      // Validate that all referenced switches are declared.
      val declaredIds = switches.map { it.id }.toSet()
      for (link in links) {
        require(link.a.switchId in declaredIds) {
          "link references undeclared switch '${link.a.switchId}'"
        }
        require(link.b.switchId in declaredIds) {
          "link references undeclared switch '${link.b.switchId}'"
        }
      }
      for (pkt in packets) {
        require(pkt.endpoint.switchId in declaredIds) {
          "packet references undeclared switch '${pkt.endpoint.switchId}'"
        }
      }
      for (exp in expects) {
        require(exp.endpoint.switchId in declaredIds) {
          "expect references undeclared switch '${exp.endpoint.switchId}'"
        }
      }
      for (switchId in inlineEntries.keys) {
        require(switchId in declaredIds) {
          "inline entries reference undeclared switch '$switchId'"
        }
      }

      return NetworkStf(switches, links, inlineEntries, packets, expects)
    }

    private fun parseEndpoint(token: String, lineNum: Int): Endpoint {
      val parts = token.split(":")
      require(parts.size == 2) { "line $lineNum: expected <switch:port>, got '$token'" }
      val port = parts[1].toIntOrNull()
      require(port != null) { "line $lineNum: invalid port number '${parts[1]}'" }
      return Endpoint(parts[0], port)
    }
  }
}
