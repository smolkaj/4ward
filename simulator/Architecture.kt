package fourward.simulator

import fourward.ir.v1.P4BehavioralConfig

/**
 * Interface for architecture-specific pipeline behaviour.
 *
 * Different P4 architectures (v1model, PSA, PNA) share the same IR interpreter but differ in:
 * 1. Pipeline structure: which parsers and controls run and in what order.
 * 2. Standard metadata: the metadata struct passed between stages.
 * 3. Extern semantics: what clone(), resubmit(), recirculate() etc. actually do.
 *
 * Points 1 and 2 are driven by the [fourward.ir.v1.Architecture] proto in the loaded pipeline
 * config. Point 3 requires code here.
 *
 * To add a new architecture, implement this interface and register the implementation in
 * [Simulator].
 */
interface Architecture {

  /**
   * Processes a single packet through the full pipeline.
   *
   * The implementation is responsible for:
   * - Setting up the initial environment (standard metadata, header validity).
   * - Running each pipeline stage in the correct order.
   * - Handling architecture-specific operations (clone, resubmit, etc.).
   * - Returning the output packets.
   */
  fun processPacket(
    ingressPort: UInt,
    payload: ByteArray,
    config: P4BehavioralConfig,
    tableStore: TableStore,
  ): PipelineResult
}

/**
 * The result of running a packet through the pipeline.
 *
 * [outputPackets] are the packets to emit (port, payload). [trace] is the execution trace tree
 * across all pipeline stages, with forks at non-deterministic choice points.
 */
data class PipelineResult(
  val outputPackets: List<OutputPacket>,
  val trace: fourward.sim.v1.TraceTree,
)

data class OutputPacket(val port: UInt, val payload: ByteArray)
