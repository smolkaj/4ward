package fourward.p4runtime

import fourward.simulator.Simulator
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.sync.Mutex

/** Wraps a P4Runtime + Dataplane gRPC server backed by a 4ward [Simulator]. */
class P4RuntimeServer(private val port: Int = DEFAULT_PORT) {

  private val simulator = Simulator()
  private val lock = Mutex()
  private val service = P4RuntimeService(simulator, lock = lock)
  private val dataplaneService = DataplaneService(simulator, lock)
  private lateinit var server: Server

  fun start(): P4RuntimeServer {
    server =
      NettyServerBuilder.forPort(port)
        .addService(service)
        .addService(dataplaneService)
        .build()
        .start()
    Runtime.getRuntime().addShutdownHook(Thread { stop() })
    return this
  }

  fun stop() {
    if (::server.isInitialized) {
      server.shutdown()
    }
  }

  fun blockUntilShutdown() {
    server.awaitTermination()
  }

  fun port(): Int = server.port

  companion object {
    const val DEFAULT_PORT = 9559
  }
}

fun main(args: Array<String>) {
  val port =
    args.firstOrNull()?.removePrefix("--port=")?.toIntOrNull() ?: P4RuntimeServer.DEFAULT_PORT
  val server = P4RuntimeServer(port).start()
  println("P4Runtime server listening on port ${server.port()}")
  server.blockUntilShutdown()
}
