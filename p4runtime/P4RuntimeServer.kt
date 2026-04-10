package fourward.p4runtime

import fourward.ir.PipelineConfig
import fourward.simulator.ProcessPacketResult
import fourward.simulator.Simulator
import fourward.simulator.WriteResult
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import java.util.concurrent.Executors
import p4.v1.P4RuntimeOuterClass

/** Wraps a P4Runtime + Dataplane gRPC server backed by a 4ward [Simulator]. */
class P4RuntimeServer(
  private val port: Int = DEFAULT_PORT,
  private val deviceId: Long = P4RuntimeService.DEFAULT_DEVICE_ID,
  dropPortOverride: Int? = null,
  cpuPortConfig: CpuPortConfig = CpuPortConfig.Auto,
) {

  private val simulator = Simulator(dropPortOverride)
  private val lock = ReadWriteMutex()
  private val broker = PacketBroker(simulator::processPacket, lock)
  private val service =
    P4RuntimeService(
      simulator,
      broker,
      lock = lock,
      deviceId = deviceId,
      cpuPortConfig = cpuPortConfig,
    )
  private val dataplaneService =
    DataplaneService(broker, typeTranslator = { service.typeTranslator })

  init {
    // Wire P4RuntimeService lambdas into the broker for hook support.
    broker.readAllEntities = { service.readAllEntities() }
    broker.readP4Info = { service.p4Info() }
    broker.applyUpdates = { updates -> service.applyHookUpdates(updates) }
  }

  private lateinit var server: Server

  fun start(): P4RuntimeServer {
    server =
      NettyServerBuilder.forPort(port)
        // Without a dedicated executor, gRPC-Java runs RPC handlers on Netty's
        // I/O event loop threads. Suspended Kotlin coroutines (e.g. a StreamChannel
        // awaiting the next client message) can prevent other RPCs on the same
        // HTTP/2 connection from being dispatched.
        .executor(Executors.newCachedThreadPool())
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

  // --- Pre-configuration API (call before [start]) -------------------------------------------

  /** Loads a compiled pipeline into the underlying simulator. */
  fun loadPipeline(config: PipelineConfig) = simulator.loadPipeline(config)

  /** Writes a table/PRE/action-profile entry via the underlying simulator. */
  fun writeEntry(update: P4RuntimeOuterClass.Update): WriteResult = simulator.writeEntry(update)

  // --- Packet I/O (usable after [start]) -----------------------------------------------------

  /**
   * Registers a subscriber that receives results for every processed packet (via DataplaneService,
   * PacketOut, or any other source). Returns a handle for unsubscribing.
   */
  fun onPacketProcessed(callback: (PacketBroker.SubscriptionResult) -> Unit) =
    broker.subscribe(callback)

  /**
   * Processes a packet through this switch's broker. Equivalent to injecting a packet via gRPC.
   * Fires all subscribers, including cross-switch forwarding.
   */
  fun processPacket(ingressPort: Int, payload: ByteArray): ProcessPacketResult =
    broker.processPacket(ingressPort, payload)

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
