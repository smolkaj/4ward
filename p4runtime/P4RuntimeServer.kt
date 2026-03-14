package fourward.p4runtime

import fourward.dvaas.DvaasService
import fourward.simulator.Simulator
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.sync.Mutex

/** Wraps a P4Runtime + Dataplane + DVaaS gRPC server backed by a 4ward [Simulator]. */
class P4RuntimeServer(
  private val port: Int = DEFAULT_PORT,
  dropPortOverride: Int? = null,
  cpuPortConfig: CpuPortConfig = CpuPortConfig.Auto,
) {

  private val simulator = Simulator(dropPortOverride)
  private val lock = Mutex()
  private val broker = PacketBroker(simulator::processPacket)
  private val service =
    P4RuntimeService(simulator, broker, lock = lock, cpuPortConfig = cpuPortConfig)
  private val dataplaneService = DataplaneService(broker, lock)
  private val dvaasService =
    DvaasService(
      processPacketFn = broker::processPacket,
      lock = lock,
      cpuPortFn = service::currentCpuPort,
      packetOutInjectorFn = service::injectPacketOut,
      packetInMetadataFn = service::buildDvaasPacketInMetadata,
    )
  private lateinit var server: Server

  fun start(): P4RuntimeServer {
    server =
      NettyServerBuilder.forPort(port)
        .addService(service)
        .addService(dataplaneService)
        .addService(dvaasService)
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
  val dropPort = flagValue(args, "--drop-port")?.toIntOrNull()
  val cpuPortConfig = CpuPortConfig.fromFlag(flagValue(args, "--cpu-port"))
  val server = P4RuntimeServer(port, dropPort, cpuPortConfig).start()
  println("P4Runtime server listening on port ${server.port()}")
  server.blockUntilShutdown()
}

private fun flagValue(args: Array<String>, flag: String): String? =
  args.find { it.startsWith("$flag=") }?.substringAfter("=")
