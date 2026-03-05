package fourward.e2e

import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.LoadPipelineRequest
import fourward.sim.v1.ProcessPacketRequest
import fourward.sim.v1.SimRequest
import fourward.sim.v1.SimResponse
import fourward.sim.v1.WriteEntryRequest
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path

/**
 * Client for the 4ward simulator subprocess protocol.
 *
 * Manages the simulator process lifecycle and provides typed methods for each request kind.
 * Implements [Closeable] so it can be used in `use {}` blocks.
 *
 * The wire protocol is length-delimited protobuf over stdin/stdout: a 4-byte big-endian length
 * prefix followed by a serialized [SimRequest] or [SimResponse].
 */
class SimulatorClient(simulatorBinary: Path) : Closeable {

  private val process: Process =
    ProcessBuilder(simulatorBinary.toString()).redirectErrorStream(false).start()

  private val input = DataInputStream(process.inputStream.buffered())
  private val output = DataOutputStream(process.outputStream.buffered())

  fun loadPipeline(config: PipelineConfig): SimResponse =
    call(
      SimRequest.newBuilder()
        .setLoadPipeline(LoadPipelineRequest.newBuilder().setConfig(config))
        .build()
    )

  fun writeEntry(writeReq: WriteEntryRequest): SimResponse =
    call(SimRequest.newBuilder().setWriteEntry(writeReq).build())

  fun processPacket(ingressPort: Int, payload: ByteArray): SimResponse =
    call(
      SimRequest.newBuilder()
        .setProcessPacket(
          ProcessPacketRequest.newBuilder()
            .setIngressPort(ingressPort)
            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
        )
        .build()
    )

  private fun call(request: SimRequest): SimResponse {
    val bytes = request.toByteArray()
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
    val length = input.readInt()
    val respBytes = ByteArray(length)
    input.readFully(respBytes)
    return SimResponse.parseFrom(respBytes)
  }

  override fun close() {
    process.destroy()
  }
}
