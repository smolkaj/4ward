package fourward.cli

import com.google.protobuf.ByteString
import fourward.e2e.SimulatorFacade
import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.LoadPipelineRequest
import fourward.sim.v1.ProcessPacketRequest
import fourward.sim.v1.SimRequest
import fourward.sim.v1.SimResponse
import fourward.sim.v1.WriteEntryRequest
import fourward.simulator.Simulator

/**
 * In-process [SimulatorFacade] backed by a [Simulator] instance.
 *
 * This is the same pattern used by [fourward.p4runtime.P4RuntimeService]: the simulator runs
 * in the same JVM, no subprocess or pipe protocol needed.
 */
class InProcessSimulator : SimulatorFacade {
  private val simulator = Simulator()

  override fun loadPipeline(config: PipelineConfig): SimResponse =
    simulator.handle(
      SimRequest.newBuilder()
        .setLoadPipeline(LoadPipelineRequest.newBuilder().setConfig(config))
        .build()
    )

  override fun writeEntry(writeReq: WriteEntryRequest): SimResponse =
    simulator.handle(SimRequest.newBuilder().setWriteEntry(writeReq).build())

  override fun processPacket(ingressPort: Int, payload: ByteArray): SimResponse =
    simulator.handle(
      SimRequest.newBuilder()
        .setProcessPacket(
          ProcessPacketRequest.newBuilder()
            .setIngressPort(ingressPort)
            .setPayload(ByteString.copyFrom(payload))
        )
        .build()
    )
}
