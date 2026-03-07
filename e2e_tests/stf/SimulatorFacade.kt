package fourward.e2e

import fourward.ir.v1.PipelineConfig
import fourward.sim.v1.SimResponse
import fourward.sim.v1.WriteEntryRequest

/**
 * Abstraction over the simulator transport.
 *
 * [SimulatorClient] talks to a subprocess over length-delimited protobuf pipes;
 * [fourward.cli.InProcessSimulator] wraps a [fourward.simulator.Simulator] instance directly.
 * Both implement this interface so that [installStfEntries] and the CLI can work with either.
 */
interface SimulatorFacade {
  fun loadPipeline(config: PipelineConfig): SimResponse
  fun writeEntry(writeReq: WriteEntryRequest): SimResponse
  fun processPacket(ingressPort: Int, payload: ByteArray): SimResponse
}
