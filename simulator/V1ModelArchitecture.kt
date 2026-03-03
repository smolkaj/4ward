package fourward.simulator

import fourward.ir.v1.P4BehavioralConfig
import fourward.ir.v1.StageKind

/**
 * v1model pipeline implementation.
 *
 * v1model is the original BMv2 architecture, defined in v1model.p4. The pipeline
 * runs six stages in fixed order:
 *
 *   MyParser → MyVerifyChecksum → MyIngress → MyEgress → MyComputeChecksum → MyDeparser
 *
 * Architecture-specific behaviour implemented here:
 *   - standard_metadata_t initialisation and egress_spec routing.
 *   - mark_to_drop() (sets egress_spec to DROP_PORT = 511).
 *   - clone/resubmit/recirculate stubs (not yet fully implemented).
 *
 * Reference: https://github.com/p4lang/p4c/blob/main/p4include/v1model.p4
 */
class V1ModelArchitecture : Architecture {

    override fun processPacket(
        ingressPort: UInt,
        payload: ByteArray,
        config: P4BehavioralConfig,
        tableStore: TableStore,
    ): PipelineResult {
        val interpreter = Interpreter(config, tableStore)
        val env = Environment(payload)

        // Initialise standard_metadata_t. The struct is defined in v1model.p4;
        // we hard-code the fields we care about for simulation purposes.
        val standardMetadata = StructVal(
            typeName = "standard_metadata_t",
            fields = mutableMapOf(
                "ingress_port"  to BitVal(ingressPort.toLong(), 9),
                "egress_spec"   to BitVal(0L, 9),
                "egress_port"   to BitVal(0L, 9),
                "packet_length" to BitVal(payload.size.toLong(), 32),
                "instance_type" to BitVal(0L, 32),
                "drop"          to BitVal(0L, 1),
            )
        )
        env.define("standard_metadata", standardMetadata)

        val stages = config.architecture.stagesList
        val parserStage   = stages.find { it.kind == StageKind.PARSER }
        val controlStages = stages.filter { it.kind == StageKind.CONTROL }
        val deparserStage = stages.find { it.kind == StageKind.DEPARSER }

        // --- Parser ---
        if (parserStage != null) {
            try {
                interpreter.runParser(parserStage.blockName, env)
            } catch (e: ExitException) {
                return PipelineResult(emptyList(), env.buildTrace())
            }
        }

        // --- Controls (verify checksum, ingress, egress, compute checksum) ---
        for (stage in controlStages) {
            try {
                interpreter.runControl(stage.blockName, env)
            } catch (e: ExitException) {
                return PipelineResult(emptyList(), env.buildTrace())
            }
        }

        val egressSpec = (standardMetadata.fields["egress_spec"] as? BitVal)
            ?.bits?.value?.toInt() ?: 0

        // Port 511 is the v1model drop port (mark_to_drop sets egress_spec = 511).
        if (egressSpec == DROP_PORT) {
            return PipelineResult(emptyList(), env.buildTrace())
        }

        // --- Deparser ---
        if (deparserStage != null) {
            interpreter.runControl(deparserStage.blockName, env)
        }

        val output = OutputPacket(egressSpec.toUInt(), env.outputPayload())
        return PipelineResult(listOf(output), env.buildTrace())
    }

    companion object {
        /** Port value used by mark_to_drop() to signal packet drop in v1model. */
        const val DROP_PORT = 511
    }
}
