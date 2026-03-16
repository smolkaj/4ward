package fourward.p4runtime

import com.google.protobuf.TextFormat
import fourward.dvaas.DvaasService
import fourward.ir.PipelineConfig
import fourward.simulator.Simulator
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.MasterArbitrationUpdate
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest
import p4.v1.P4RuntimeOuterClass.StreamMessageRequest
import p4.v1.P4RuntimeOuterClass.Uint128

/** Wraps a P4Runtime + Dataplane + DVaaS gRPC server backed by a 4ward [Simulator]. */
class P4RuntimeServer(
  private val port: Int = DEFAULT_PORT,
  private val deviceId: Long = DEFAULT_DEVICE_ID,
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
  private val dataplaneService = DataplaneService(broker, lock)
  private val dvaasService =
    DvaasService(
      processPacketFn = broker::processPacket,
      lock = lock,
      cpuPortFn = service::currentCpuPort,
      packetOutInjectorFn = service::injectPacketOut,
      packetInMetadataFn = service::buildDvaasPacketInMetadata,
    )
  private val gnmiService = GnmiService()
  private lateinit var server: Server

  fun start(): P4RuntimeServer {
    server =
      NettyServerBuilder.forPort(port)
        .addService(service)
        .addService(dataplaneService)
        .addService(dvaasService)
        .addService(gnmiService)
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

  /**
   * Loads a pre-compiled pipeline via P4Runtime. Connects as a loopback client, performs master
   * arbitration, and sends [SetForwardingPipelineConfigRequest] with VERIFY_AND_COMMIT — the same
   * path any external client would use. The server must be [start]ed first.
   */
  fun loadPipelineViaP4Runtime(path: Path) {
    val configText = Files.readString(path)
    val pipelineConfig =
      PipelineConfig.newBuilder().also { TextFormat.merge(configText, it) }.build()
    val fwdConfig =
      ForwardingPipelineConfig.newBuilder()
        .setP4Info(pipelineConfig.p4Info)
        .setP4DeviceConfig(pipelineConfig.device.toByteString())
        .build()

    val channel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
    try {
      val stub = P4RuntimeGrpcKt.P4RuntimeCoroutineStub(channel)
      val electionId = Uint128.newBuilder().setHigh(0).setLow(1).build()

      runBlocking {
        coroutineScope {
          val requests = Channel<StreamMessageRequest>(Channel.BUFFERED)
          val arbitrated = CompletableDeferred<Unit>()

          // Keep the stream channel open so the session stays primary.
          val streamJob = launch {
            var first = true
            stub.streamChannel(requests.consumeAsFlow()).collect {
              if (first) {
                arbitrated.complete(Unit)
                first = false
              }
            }
          }

          // Perform master arbitration.
          requests.send(
            StreamMessageRequest.newBuilder()
              .setArbitration(
                MasterArbitrationUpdate.newBuilder().setDeviceId(deviceId).setElectionId(electionId)
              )
              .build()
          )
          arbitrated.await()

          // Push the pipeline via the standard P4RT RPC.
          stub.setForwardingPipelineConfig(
            SetForwardingPipelineConfigRequest.newBuilder()
              .setDeviceId(deviceId)
              .setElectionId(electionId)
              .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
              .setConfig(fwdConfig)
              .build()
          )

          // Close the stream and end the session.
          requests.close()
          streamJob.cancel()
        }
      }
    } finally {
      channel.shutdownNow()
    }
  }

  fun blockUntilShutdown() {
    server.awaitTermination()
  }

  fun port(): Int = server.port

  companion object {
    const val DEFAULT_PORT = 9559
    const val DEFAULT_DEVICE_ID = 1L
  }
}

fun main(args: Array<String>) {
  val port = flagValue(args, "--port")?.toIntOrNull() ?: P4RuntimeServer.DEFAULT_PORT
  val deviceId = flagValue(args, "--device-id")?.toLongOrNull() ?: P4RuntimeServer.DEFAULT_DEVICE_ID
  val dropPort = flagValue(args, "--drop-port")?.toIntOrNull()
  val cpuPortConfig = CpuPortConfig.fromFlag(flagValue(args, "--cpu-port"))
  val pipelinePath = flagValue(args, "--pipeline")

  val server = P4RuntimeServer(port, deviceId, dropPort, cpuPortConfig).start()

  if (pipelinePath != null) {
    val resolved = resolveUserPath(pipelinePath)
    server.loadPipelineViaP4Runtime(resolved)
    println("Loaded pipeline from $resolved")
  }

  println("P4Runtime server listening on port ${server.port()}")
  server.blockUntilShutdown()
}

/**
 * Resolves a user-supplied path against the original working directory.
 *
 * `bazel run` changes cwd to the runfiles tree, so relative paths won't resolve without this. Bazel
 * sets `BUILD_WORKING_DIRECTORY` to the user's actual cwd.
 */
private fun resolveUserPath(arg: String): Path {
  val p = Path.of(arg)
  if (p.isAbsolute) return p
  val bwd = System.getenv("BUILD_WORKING_DIRECTORY") ?: return p
  return Path.of(bwd).resolve(p)
}

private fun flagValue(args: Array<String>, flag: String): String? =
  args.find { it.startsWith("$flag=") }?.substringAfter("=")
