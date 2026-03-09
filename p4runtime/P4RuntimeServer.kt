package fourward.p4runtime

import com.google.protobuf.TextFormat
import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.DataplaneGrpcKt.DataplaneCoroutineStub
import fourward.sim.v1.SimulatorProto.ProcessPacketRequest
import fourward.simulator.Simulator
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

/** Wraps a P4Runtime + Dataplane gRPC server backed by a 4ward [Simulator]. */
class P4RuntimeServer(
  private val port: Int = DEFAULT_PORT,
  private val pipelinePath: Path? = null,
  private val cpuPort: Int? = null,
  private val peerDataplaneTarget: String? = null,
) {

  private val simulator = Simulator()
  private val lock = Mutex()
  private val peerChannel: ManagedChannel? =
    peerDataplaneTarget?.let { ManagedChannelBuilder.forTarget(it).usePlaintext().build() }
  private val peerDataplaneStub: DataplaneCoroutineStub? =
    peerChannel?.let { DataplaneCoroutineStub(it) }
  private val service =
    P4RuntimeService(
      simulator = simulator,
      cpuPort = cpuPort,
      frontPanelTransmitter =
        peerDataplaneStub?.let { stub ->
          P4RuntimeService.FrontPanelTransmitter { egressPort, payload ->
            runBlocking {
              stub
                .processPacket(
                  ProcessPacketRequest.newBuilder()
                    .setIngressPort(egressPort)
                    .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                    .build()
                )
                .outputPacketsList
            }
          }
        },
      lock = lock,
    )
  private val dataplaneService = DataplaneService(simulator, lock)
  private lateinit var server: Server

  fun start(): P4RuntimeServer {
    pipelinePath?.let { runBlocking { service.loadPipelineConfig(loadPipelineConfig(it)) } }
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
    peerChannel?.shutdown()
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
  val options =
    args.associate {
      val parts = it.split("=", limit = 2)
      parts[0] to parts.getOrElse(1) { "" }
    }
  val port = options["--port"]?.toIntOrNull() ?: P4RuntimeServer.DEFAULT_PORT
  val pipelinePath = options["--pipeline"]?.takeIf { it.isNotEmpty() }?.let(Path::of)
  val cpuPort = options["--cpu_port"]?.toIntOrNull()
  val peerDataplaneTarget = options["--peer_dataplane"]?.takeIf { it.isNotEmpty() }
  val server = P4RuntimeServer(port, pipelinePath, cpuPort, peerDataplaneTarget).start()
  println("P4Runtime server listening on port ${server.port()}")
  server.blockUntilShutdown()
}

private fun loadPipelineConfig(path: Path): PipelineConfig {
  val builder = PipelineConfig.newBuilder()
  TextFormat.merge(path.toFile().readText(), builder)
  return builder.build()
}
