package fourward.web

import fourward.p4runtime.DataplaneService
import fourward.p4runtime.P4RuntimeService
import fourward.p4runtime.PacketBroker
import fourward.simulator.Simulator
import io.grpc.netty.NettyServerBuilder
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex

/**
 * 4ward Playground — combined gRPC + HTTP server.
 *
 * Runs two servers in the same process sharing a single [Simulator]:
 * - **gRPC** (port 9559): standard P4Runtime + Dataplane services for CLI/controller use.
 * - **HTTP** (port 8080): REST API + web UI for the interactive playground.
 */
fun main(args: Array<String>) {
  val httpPort = flagValue(args, "--http-port")?.toIntOrNull() ?: WebServer.DEFAULT_HTTP_PORT
  val grpcPort =
    flagValue(args, "--grpc-port")?.toIntOrNull() ?: fourward.p4runtime.P4RuntimeServer.DEFAULT_PORT
  val staticDir = flagValue(args, "--static-dir")?.let { Path.of(it) }

  val dropPort = flagValue(args, "--drop-port")?.toIntOrNull()
  val cpuPortConfig = fourward.p4runtime.CpuPortConfig.fromFlag(flagValue(args, "--cpu-port"))

  val simulator = Simulator(dropPort)
  val lock = Mutex()
  val broker = PacketBroker(simulator::processPacket)
  val service = P4RuntimeService(simulator, broker, lock = lock, cpuPortConfig = cpuPortConfig)
  val dataplaneService = DataplaneService(broker, lock)

  // Start gRPC server.
  val grpcServer =
    NettyServerBuilder.forPort(grpcPort)
      .addService(service)
      .addService(dataplaneService)
      .build()
      .start()

  // Start HTTP server.
  val webServer =
    WebServer(
        simulator = simulator,
        service = service,
        lock = lock,
        httpPort = httpPort,
        staticDir = staticDir,
      )
      .start()

  val url = "http://localhost:$httpPort"
  println("4ward Playground")
  println("  Web UI:   $url")
  println("  gRPC:     localhost:${grpcServer.port}")
  if (staticDir != null) println("  Static:   $staticDir")

  // Open browser automatically.
  try {
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
  } catch (_: Exception) {
    // Best-effort — headless environments won't have a desktop.
  }

  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        webServer.stop()
        grpcServer.shutdown()
        service.close()
      }
    )

  grpcServer.awaitTermination()
}

private fun flagValue(args: Array<String>, flag: String): String? =
  args.find { it.startsWith("$flag=") }?.substringAfter("=")
