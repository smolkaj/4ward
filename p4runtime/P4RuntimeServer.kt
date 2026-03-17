package fourward.p4runtime

import fourward.simulator.Simulator
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.sync.Mutex

/** Wraps a P4Runtime + Dataplane gRPC server backed by a 4ward [Simulator]. */
class P4RuntimeServer(
  private val port: Int = DEFAULT_PORT,
  private val deviceId: Long = P4RuntimeService.DEFAULT_DEVICE_ID,
  dropPortOverride: Int? = null,
  cpuPortConfig: CpuPortConfig = CpuPortConfig.Auto,
) {

  private val simulator = Simulator(dropPortOverride)
  private val lock = Mutex()
  private val broker = PacketBroker(simulator::processPacket)
  private val service =
    P4RuntimeService(
      simulator,
      broker,
      lock = lock,
      deviceId = deviceId,
      cpuPortConfig = cpuPortConfig,
    )
  private val dataplaneService = DataplaneService(broker, lock) { service.portTranslator }
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
  val port = flagValue(args, "--port")?.toIntOrNull() ?: P4RuntimeServer.DEFAULT_PORT
  val deviceId =
    flagValue(args, "--device-id")?.toLongOrNull() ?: P4RuntimeService.DEFAULT_DEVICE_ID
  val dropPort = flagValue(args, "--drop-port")?.toIntOrNull()
  val cpuPortConfig = CpuPortConfig.fromFlag(flagValue(args, "--cpu-port"))

  val server = P4RuntimeServer(port, deviceId, dropPort, cpuPortConfig).start()
  println("P4Runtime server listening on port ${server.port()}")
  server.blockUntilShutdown()
}

private fun flagValue(args: Array<String>, flag: String): String? =
  args.find { it.startsWith("$flag=") }?.substringAfter("=")
