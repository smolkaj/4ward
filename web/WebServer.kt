package fourward.web

import com.google.protobuf.TextFormat
import com.google.protobuf.util.JsonFormat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fourward.ir.v1.PipelineConfig
import fourward.simulator.Simulator
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.WriteRequest

/**
 * HTTP server for the 4ward web playground.
 *
 * Provides a REST API that wraps P4 compilation and P4Runtime operations, plus serves the
 * single-page frontend. Uses the JDK's built-in [HttpServer] to avoid adding external dependencies.
 *
 * All P4Runtime operations go through [P4RuntimeService][fourward.p4runtime.P4RuntimeService]:
 * pipeline loading via SetForwardingPipelineConfig, table writes via Write, and reads via Read.
 * Packet injection goes directly to the simulator (with the shared lock) to get trace trees.
 */
class WebServer(
  private val simulator: Simulator,
  private val service: fourward.p4runtime.P4RuntimeService,
  private val lock: Mutex,
  private val httpPort: Int = DEFAULT_HTTP_PORT,
  private val staticDir: Path? = null,
) {

  private val server = HttpServer.create(InetSocketAddress(httpPort), 0)
  private val jsonPrinter: JsonFormat.Printer =
    JsonFormat.printer().preservingProtoFieldNames().alwaysPrintFieldsWithNoPresence()
  private val jsonParser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
  private val textPrinter: TextFormat.Printer = TextFormat.printer().escapingNonAscii(false)

  @Volatile private var loadedP4Info: p4.config.v1.P4InfoOuterClass.P4Info? = null
  @Volatile private var loadedBehavioral: fourward.ir.v1.BehavioralConfig? = null

  fun start(): WebServer {
    server.createContext("/api/compile-and-load") { cors(it, ::handleCompileAndLoad) }
    server.createContext("/api/pipeline") { cors(it, ::handleGetPipeline) }
    server.createContext("/api/write") { cors(it, ::handleWrite) }
    server.createContext("/api/read") { cors(it, ::handleRead) }
    server.createContext("/api/packet") { cors(it, ::handlePacket) }
    server.createContext("/api/control-graph") { cors(it, ::handleControlGraph) }
    server.createContext("/") { handleStatic(it) }
    server.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
    server.start()
    return this
  }

  fun stop() {
    server.stop(0)
  }

  // ---------------------------------------------------------------------------
  // CORS wrapper
  // ---------------------------------------------------------------------------

  private fun cors(exchange: HttpExchange, handler: (HttpExchange) -> Unit) {
    exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")

    if (exchange.requestMethod == "OPTIONS") {
      exchange.sendResponseHeaders(HTTP_NO_CONTENT, NO_BODY)
      exchange.close()
      return
    }

    try {
      handler(exchange)
    } catch (e: io.grpc.StatusException) {
      sendJson(exchange, HTTP_BAD_REQUEST, errorJson(e.status.description ?: e.status.code.name))
    } catch (e: Exception) {
      sendJson(exchange, HTTP_INTERNAL_ERROR, errorJson(e.message ?: "Internal server error"))
    }
  }

  // ---------------------------------------------------------------------------
  // POST /api/compile-and-load
  // ---------------------------------------------------------------------------

  private fun handleCompileAndLoad(exchange: HttpExchange) {
    requirePost(exchange)
    val body = exchange.requestBody.bufferedReader().readText()
    val source = extractJsonString(body, "source") ?: throw badRequest("Missing 'source' field")

    val tempP4 = Files.createTempFile("4ward-playground-", ".p4")
    val tempOutput = Files.createTempFile("4ward-playground-", ".txtpb")
    try {
      Files.writeString(tempP4, source)
      val result = compileP4(tempP4, tempOutput)
      if (result.exitCode != 0) {
        sendJson(exchange, HTTP_BAD_REQUEST, """{"error":${jsonEscape(result.output)}}""")
        return
      }

      val configText = Files.readString(tempOutput)
      val configBuilder = PipelineConfig.newBuilder()
      TextFormat.merge(configText, configBuilder)
      val config = configBuilder.build()

      // Load via SetForwardingPipelineConfig — the standard P4Runtime path.
      val request =
        SetForwardingPipelineConfigRequest.newBuilder()
          .setDeviceId(1)
          .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
          .setConfig(
            ForwardingPipelineConfig.newBuilder()
              .setP4Info(config.p4Info)
              .setP4DeviceConfig(config.device.toByteString())
          )
          .build()

      runBlocking { service.setForwardingPipelineConfig(request) }
      loadedP4Info = config.p4Info
      loadedBehavioral = config.device.behavioral

      sendJson(
        exchange,
        HTTP_OK,
        """{"success":true,"p4info":${jsonPrinter.print(config.p4Info)}}""",
      )
    } finally {
      Files.deleteIfExists(tempP4)
      Files.deleteIfExists(tempOutput)
    }
  }

  // ---------------------------------------------------------------------------
  // GET /api/pipeline
  // ---------------------------------------------------------------------------

  private fun handleGetPipeline(exchange: HttpExchange) {
    val p4info = loadedP4Info
    if (p4info == null) {
      sendJson(exchange, HTTP_OK, """{"loaded":false}""")
    } else {
      sendJson(exchange, HTTP_OK, """{"loaded":true,"p4info":${jsonPrinter.print(p4info)}}""")
    }
  }

  // ---------------------------------------------------------------------------
  // POST /api/write
  // ---------------------------------------------------------------------------

  private fun handleWrite(exchange: HttpExchange) {
    requirePost(exchange)
    val body = exchange.requestBody.bufferedReader().readText()
    val builder = WriteRequest.newBuilder()
    jsonParser.merge(body, builder)
    if (builder.deviceId == 0L) builder.deviceId = 1
    runBlocking { service.write(builder.build()) }
    sendJson(exchange, HTTP_OK, """{"success":true}""")
  }

  // ---------------------------------------------------------------------------
  // GET /api/read?table_id=0
  // ---------------------------------------------------------------------------

  private fun handleRead(exchange: HttpExchange) {
    val tableId = queryParam(exchange, "table_id")?.toIntOrNull() ?: 0
    val request =
      ReadRequest.newBuilder()
        .setDeviceId(1)
        .addEntities(Entity.newBuilder().setTableEntry(TableEntry.newBuilder().setTableId(tableId)))
        .build()

    val entities = mutableListOf<Entity>()
    runBlocking { service.read(request).collect { entities.addAll(it.entitiesList) } }

    val entitiesJson = entities.joinToString(",") { jsonPrinter.print(it) }
    sendJson(exchange, HTTP_OK, """{"entities":[$entitiesJson]}""")
  }

  // ---------------------------------------------------------------------------
  // POST /api/packet
  // ---------------------------------------------------------------------------

  private fun handlePacket(exchange: HttpExchange) {
    requirePost(exchange)
    val body = exchange.requestBody.bufferedReader().readText()
    val ingressPort = extractJsonInt(body, "ingress_port") ?: 0
    val payloadHex =
      extractJsonString(body, "payload_hex") ?: throw badRequest("Missing payload_hex")
    val payload = hexToBytes(payloadHex)

    val response = runBlocking { lock.withLock { simulator.processPacket(ingressPort, payload) } }

    val outputsJson = response.outputPacketsList.joinToString(",") { jsonPrinter.print(it) }
    val traceJson = jsonPrinter.print(response.trace)
    val traceProto = textPrinter.printToString(response.trace)
    sendJson(
      exchange,
      HTTP_OK,
      """{"output_packets":[$outputsJson],"trace":$traceJson,"trace_proto":${jsonEscape(traceProto)}}""",
    )
  }

  // ---------------------------------------------------------------------------
  // GET /api/control-graph
  // ---------------------------------------------------------------------------

  private fun handleControlGraph(exchange: HttpExchange) {
    val behavioral = loadedBehavioral
    if (behavioral == null) {
      sendJson(exchange, HTTP_OK, """{"loaded":false}""")
      return
    }
    val graphs = ControlGraphExtractor.extract(behavioral)
    val controlsJson =
      graphs.joinToString(",") { graph ->
        val nodesJson =
          graph.nodes.joinToString(",") { node ->
            """{"id":${jsonEscape(node.id)},"type":"${node.type}","name":${jsonEscape(node.name)}}"""
          }
        val edgesJson =
          graph.edges.joinToString(",") { edge ->
            val labelField =
              if (edge.label.isNotEmpty()) ""","label":${jsonEscape(edge.label)}""" else ""
            """{"from":${jsonEscape(edge.from)},"to":${jsonEscape(edge.to)}$labelField}"""
          }
        """${jsonEscape(graph.name)}:{"nodes":[$nodesJson],"edges":[$edgesJson]}"""
      }
    sendJson(exchange, HTTP_OK, """{"loaded":true,"controls":{$controlsJson}}""")
  }

  // ---------------------------------------------------------------------------
  // Static file serving
  // ---------------------------------------------------------------------------

  private fun handleStatic(exchange: HttpExchange) {
    val reqPath = exchange.requestURI.path.trimStart('/')
    val filePath = if (reqPath.isEmpty()) "index.html" else reqPath

    // Don't serve API paths as static files.
    if (filePath.startsWith("api/")) {
      exchange.sendResponseHeaders(HTTP_NOT_FOUND, NO_BODY)
      exchange.close()
      return
    }

    val content = readStaticFile(filePath)
    if (content == null) {
      exchange.sendResponseHeaders(HTTP_NOT_FOUND, NO_BODY)
      exchange.close()
      return
    }

    exchange.responseHeaders.add("Content-Type", contentType(filePath))
    exchange.sendResponseHeaders(HTTP_OK, content.size.toLong())
    exchange.responseBody.use { it.write(content) }
  }

  private fun readStaticFile(relativePath: String): ByteArray? {
    // 1. Override directory (for development).
    staticDir?.let { dir ->
      val file = dir.resolve(relativePath).normalize()
      if (file.startsWith(dir) && Files.isRegularFile(file)) {
        return Files.readAllBytes(file)
      }
    }

    // 2. Bazel runfiles.
    val runfiles = System.getenv("JAVA_RUNFILES")
    if (runfiles != null) {
      val file = Path.of(runfiles, "_main/web/frontend", relativePath)
      if (Files.isRegularFile(file)) return Files.readAllBytes(file)
    }

    // 3. Classpath.
    return javaClass.getResourceAsStream("/frontend/$relativePath")?.readAllBytes()
  }

  // ---------------------------------------------------------------------------
  // P4 compilation
  // ---------------------------------------------------------------------------

  private data class CompileResult(val exitCode: Int, val output: String)

  private fun compileP4(source: Path, outputPath: Path): CompileResult {
    val p4c = findP4c() ?: return CompileResult(1, "p4c-4ward not found")

    val cmd = mutableListOf(p4c.toString())

    // Add standard includes (core.p4, v1model.p4) from runfiles.
    val runfiles = System.getenv("JAVA_RUNFILES")
    if (runfiles != null) {
      val p4include = Path.of(runfiles, "p4c+/p4include")
      if (Files.isDirectory(p4include)) cmd += listOf("-I", p4include.toString())
    }

    cmd += listOf("-o", outputPath.toString())
    cmd += source.toString()

    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val processOutput = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return CompileResult(exitCode, processOutput)
  }

  private fun findP4c(): Path? {
    val runfiles = System.getenv("JAVA_RUNFILES")
    if (runfiles != null) {
      val candidate = Path.of(runfiles, "_main/p4c_backend/p4c-4ward")
      if (Files.isExecutable(candidate)) return candidate
    }
    val pathDirs = System.getenv("PATH")?.split(":") ?: emptyList()
    for (dir in pathDirs) {
      val candidate = Path.of(dir, "p4c-4ward")
      if (Files.isExecutable(candidate)) return candidate
    }
    return null
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun sendJson(exchange: HttpExchange, statusCode: Int, json: String) {
    val bytes = json.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun requirePost(exchange: HttpExchange) {
    if (exchange.requestMethod != "POST") throw badRequest("POST required")
  }

  private fun badRequest(message: String) =
    io.grpc.Status.INVALID_ARGUMENT.withDescription(message).asException()

  private fun queryParam(exchange: HttpExchange, key: String): String? =
    exchange.requestURI.query?.split("&")?.find { it.startsWith("$key=") }?.substringAfter("=")

  companion object {
    const val DEFAULT_HTTP_PORT = 8080

    private const val THREAD_POOL_SIZE = 4
    private const val HTTP_OK = 200
    private const val HTTP_NO_CONTENT = 204
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_INTERNAL_ERROR = 500
    private const val NO_BODY = -1L

    private fun contentType(path: String): String =
      when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
      }

    /** Minimal JSON string extraction — avoids pulling in a JSON library. */
    fun extractJsonString(json: String, key: String): String? {
      // Manual scan instead of regex: Java's NFA regex engine recurses per
      // character in `(?:[^"\\]|\\.)*`, causing StackOverflowError on long
      // P4 source strings.
      val keyPattern = "\"$key\""
      val keyIdx = json.indexOf(keyPattern)
      if (keyIdx < 0) return null
      var i = keyIdx + keyPattern.length
      while (i < json.length && json[i].isWhitespace()) i++
      if (i >= json.length || json[i] != ':') return null
      i++
      while (i < json.length && json[i].isWhitespace()) i++
      if (i >= json.length || json[i] != '"') return null
      i++ // skip opening quote
      val sb = StringBuilder()
      while (i < json.length) {
        val c = json[i]
        if (c == '"') return sb.toString()
        if (c == '\\' && i + 1 < json.length) {
          when (json[++i]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            '/' -> sb.append('/')
            'u' -> {
              if (i + 4 < json.length) {
                val hex = json.substring(i + 1, i + 5)
                sb.append(hex.toInt(16).toChar())
                i += 4
              }
            }
            else -> {
              sb.append('\\')
              sb.append(json[i])
            }
          }
        } else {
          sb.append(c)
        }
        i++
      }
      return null // unterminated string
    }

    fun extractJsonInt(json: String, key: String): Int? {
      val regex = """"$key"\s*:\s*(-?\d+)""".toRegex()
      return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun jsonEscape(s: String): String {
      val escaped =
        s.replace("\\", "\\\\")
          .replace("\"", "\\\"")
          .replace("\n", "\\n")
          .replace("\r", "\\r")
          .replace("\t", "\\t")
      return "\"$escaped\""
    }

    fun errorJson(message: String): String = """{"error":${jsonEscape(message)}}"""

    fun hexToBytes(hex: String): ByteArray {
      val clean = hex.replace(Regex("[\\s:]+"), "").removePrefix("0x").removePrefix("0X")
      require(clean.length % 2 == 0) { "Hex string must have even length" }
      return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
      }
    }
  }
}
