package fourward.cli

import java.nio.file.Path

/**
 * A parsed `.nstf` (network STF) file describing a multi-switch test scenario.
 *
 * Format:
 * ```
 * switch <id> <pipeline.txtpb> <entries.stf>
 * link <switch:port> <switch:port>
 * packet <switch:port> <hex bytes>
 * expect <switch:port> <hex bytes>
 * ```
 *
 * Pipeline and STF paths are resolved relative to the `.nstf` file's directory.
 */
data class NetworkStf(
  val switches: List<SwitchDecl>,
  val links: List<LinkDecl>,
  val packets: List<NetworkPacket>,
  val expects: List<NetworkExpect>,
) {
  data class SwitchDecl(val id: String, val pipelinePath: Path, val stfPath: Path)
  data class LinkDecl(val switchA: String, val portA: Int, val switchB: String, val portB: Int)
  data class NetworkPacket(val switchId: String, val port: Int, val payload: ByteArray)
  data class NetworkExpect(val switchId: String, val port: Int, val payload: ByteArray)

  companion object {
    fun parse(path: Path): NetworkStf {
      val dir = path.parent ?: Path.of(".")
      val lines =
        path.toFile().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

      val switches = mutableListOf<SwitchDecl>()
      val links = mutableListOf<LinkDecl>()
      val packets = mutableListOf<NetworkPacket>()
      val expects = mutableListOf<NetworkExpect>()

      for ((lineNum, line) in lines.withIndex()) {
        val tokens = line.split("\\s+".toRegex())
        when (tokens[0]) {
          "switch" -> {
            require(tokens.size == 4) { "line ${lineNum + 1}: switch needs <id> <pipeline> <stf>" }
            switches.add(SwitchDecl(tokens[1], dir.resolve(tokens[2]), dir.resolve(tokens[3])))
          }
          "link" -> {
            require(tokens.size == 3) { "line ${lineNum + 1}: link needs <switch:port> <switch:port>" }
            val (sA, pA) = parseEndpoint(tokens[1], lineNum)
            val (sB, pB) = parseEndpoint(tokens[2], lineNum)
            links.add(LinkDecl(sA, pA, sB, pB))
          }
          "packet" -> {
            require(tokens.size >= 3) { "line ${lineNum + 1}: packet needs <switch:port> <hex>" }
            val (sw, port) = parseEndpoint(tokens[1], lineNum)
            packets.add(NetworkPacket(sw, port, parseHex(tokens.drop(2))))
          }
          "expect" -> {
            require(tokens.size >= 3) { "line ${lineNum + 1}: expect needs <switch:port> <hex>" }
            val (sw, port) = parseEndpoint(tokens[1], lineNum)
            expects.add(NetworkExpect(sw, port, parseHex(tokens.drop(2))))
          }
          else -> error("line ${lineNum + 1}: unknown directive '${tokens[0]}'")
        }
      }

      require(switches.isNotEmpty()) { "no switches declared" }
      return NetworkStf(switches, links, packets, expects)
    }

    private fun parseEndpoint(token: String, lineNum: Int): Pair<String, Int> {
      val parts = token.split(":")
      require(parts.size == 2) { "line ${lineNum + 1}: expected <switch:port>, got '$token'" }
      val port = parts[1].toIntOrNull()
      require(port != null) { "line ${lineNum + 1}: invalid port number '${parts[1]}'" }
      return Pair(parts[0], port)
    }

    private fun parseHex(tokens: List<String>): ByteArray {
      val hex = tokens.joinToString("")
      return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
  }
}
