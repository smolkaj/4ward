package fourward.web

import fourward.p4runtime.DataplaneService
import fourward.p4runtime.P4RuntimeService
import fourward.simulator.Simulator
import io.grpc.netty.NettyServerBuilder
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

  val simulator = Simulator()
  val lock = Mutex()
  val service = P4RuntimeService(simulator, lock = lock)
  val dataplaneService = DataplaneService(simulator, lock)

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

  println("4ward Playground")
  println("  Web UI:   http://localhost:$httpPort")
  println("  gRPC:     localhost:${grpcServer.port}")
  if (staticDir != null) println("  Static:   $staticDir")

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
