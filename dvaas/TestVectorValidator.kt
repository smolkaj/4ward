// Copyright 2026 4ward Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fourward.dvaas

import com.google.protobuf.ByteString
import fourward.dvaas.DvaasProto.InputType
import fourward.dvaas.DvaasProto.Packet
import fourward.dvaas.DvaasProto.PacketIn
import fourward.dvaas.DvaasProto.PacketTestOutcome
import fourward.dvaas.DvaasProto.PacketTestVector
import fourward.dvaas.DvaasProto.SwitchOutput
import fourward.dvaas.DvaasProto.ValidationResult
import fourward.sim.SimulatorProto.OutputPacket
import fourward.sim.SimulatorProto.TraceTree
import fourward.simulator.ProcessPacketResult

/**
 * Validates packet test vectors against a simulator.
 *
 * Pure validation logic — no gRPC, no simulator ownership. The caller provides a function
 * for packet processing and the CPU port number for PacketIn classification.
 *
 * **Forking traces (action selectors, clone, multicast):** When the trace tree forks, each leaf
 * represents a possible packet outcome. For validation, every leaf's output must match some
 * acceptable output — ensuring correctness regardless of which branch the hardware selects. The
 * `actual_output` in the response contains all possible outputs concatenated; use the trace tree
 * for per-branch inspection.
 *
 * @param processPacketFn Processes a single packet. Must be called under appropriate locking.
 * @param cpuPort The CPU port number for classifying PacketIn, or null if PacketIn is not expected.
 */
class TestVectorValidator(
  private val processPacketFn: (ingressPort: Int, payload: ByteArray) -> ProcessPacketResult,
  private val cpuPort: Int? = null,
) {

  /** Validates a single test vector and returns the outcome. */
  fun validate(vector: PacketTestVector): PacketTestOutcome {
    val input = vector.input
    // TODO(DVaaS): support INPUT_TYPE_PACKET_OUT and INPUT_TYPE_SUBMIT_TO_INGRESS.
    require(
      input.type == InputType.INPUT_TYPE_DATAPLANE ||
        input.type == InputType.INPUT_TYPE_UNSPECIFIED
    ) {
      "unsupported input type: ${input.type}. Only INPUT_TYPE_DATAPLANE is currently supported."
    }
    // TODO(DVaaS): support non-numeric port strings for SAI P4 @p4runtime_translation.
    val ingressPort =
      input.packet.port.toIntOrNull()
        ?: error("non-numeric port '${input.packet.port}' not yet supported")

    val result = processPacketFn(ingressPort, input.packet.payload.toByteArray())

    // Build the "actual output" from all outputs across all branches (for reporting).
    val actualOutput = buildSwitchOutput(result.outputPackets)

    // Validate: for forking traces, check each leaf independently.
    val validationResult =
      if (result.trace.hasForkOutcome()) {
        validateForking(vector, result.trace)
      } else {
        compareOutputs(vector, actualOutput)
      }

    return PacketTestOutcome.newBuilder()
      .setTestVector(vector)
      .setActualOutput(actualOutput)
      .setTrace(result.trace)
      .setResult(validationResult)
      .build()
  }

  /** Validates a batch of test vectors. Returns one outcome per vector, in order. */
  fun validateAll(vectors: List<PacketTestVector>): List<PacketTestOutcome> =
    vectors.map { validate(it) }

  /**
   * Validates a forking trace: every leaf's output must match some acceptable output.
   *
   * This ensures correctness regardless of which branch the hardware selects at
   * non-deterministic choice points (action selectors).
   */
  private fun validateForking(vector: PacketTestVector, trace: TraceTree): ValidationResult {
    if (vector.acceptableOutputsCount == 0) {
      return ValidationResult.newBuilder().setPassed(true).build()
    }

    val leafOutputs = collectPerLeafOutputs(trace)
    val unmatchedLeaves = mutableListOf<Pair<String, SwitchOutput>>()

    for ((label, leafOutput) in leafOutputs) {
      val matched = vector.acceptableOutputsList.any { outputsMatch(it, leafOutput) }
      if (!matched) {
        unmatchedLeaves.add(label to leafOutput)
      }
    }

    if (unmatchedLeaves.isEmpty()) {
      return ValidationResult.newBuilder().setPassed(true).build()
    }

    return ValidationResult.newBuilder()
      .setPassed(false)
      .setFailureDescription(buildForkingFailureDescription(vector, unmatchedLeaves))
      .build()
  }

  /**
   * Collects one [SwitchOutput] per leaf of the trace tree, labeled by branch path.
   *
   * For non-forking trees, returns a single `("", output)`. For forking trees, each leaf is
   * labeled with its branch path (e.g. "member_0" or "clone/member_1").
   */
  private fun collectPerLeafOutputs(
    tree: TraceTree,
    pathPrefix: String = "",
  ): List<Pair<String, SwitchOutput>> =
    when {
      tree.hasForkOutcome() ->
        tree.forkOutcome.branchesList.flatMap { branch ->
          val path = if (pathPrefix.isEmpty()) branch.label else "$pathPrefix/${branch.label}"
          collectPerLeafOutputs(branch.subtree, path)
        }
      tree.hasPacketOutcome() -> {
        val output =
          if (tree.packetOutcome.hasOutput()) {
            buildSwitchOutput(listOf(tree.packetOutcome.output))
          } else {
            SwitchOutput.getDefaultInstance()
          }
        listOf(pathPrefix to output)
      }
      else -> listOf(pathPrefix to SwitchOutput.getDefaultInstance())
    }

  /**
   * Builds a [SwitchOutput] from a list of [OutputPacket]s, classifying outputs as front-panel
   * packets or PacketIn based on the CPU port.
   */
  private fun buildSwitchOutput(outputs: List<OutputPacket>): SwitchOutput {
    val builder = SwitchOutput.newBuilder()
    for (output in outputs) {
      if (cpuPort != null && output.egressPort == cpuPort) {
        builder.addPacketIns(PacketIn.newBuilder().setPayload(output.payload))
      } else {
        builder.addPackets(
          Packet.newBuilder()
            .setPort(output.egressPort.toString())
            .setPayload(output.payload)
        )
      }
    }
    return builder.build()
  }

  /**
   * Compares actual output against the test vector's acceptable outputs.
   *
   * Matching semantics:
   * - If [PacketTestVector.acceptable_outputs] is empty, the test passes unconditionally (recording
   *   mode — actual output is captured but not validated).
   * - Otherwise, the test passes if the actual output matches ANY acceptable output.
   * - Two [SwitchOutput]s match if they have the same packets (port + payload, order-sensitive) and
   *   the same packet_ins (payload, order-sensitive).
   */
  private fun compareOutputs(
    vector: PacketTestVector,
    actualOutput: SwitchOutput,
  ): ValidationResult {
    if (vector.acceptableOutputsCount == 0) {
      return ValidationResult.newBuilder().setPassed(true).build()
    }

    for (expected in vector.acceptableOutputsList) {
      if (outputsMatch(expected, actualOutput)) {
        return ValidationResult.newBuilder().setPassed(true).build()
      }
    }

    return ValidationResult.newBuilder()
      .setPassed(false)
      .setFailureDescription(buildFailureDescription(vector, actualOutput))
      .build()
  }

  companion object {
    /** Two [SwitchOutput]s match if their packets and packet_ins are identical. */
    fun outputsMatch(expected: SwitchOutput, actual: SwitchOutput): Boolean {
      if (expected.packetsCount != actual.packetsCount) return false
      if (expected.packetInsCount != actual.packetInsCount) return false

      for (i in 0 until expected.packetsCount) {
        val exp = expected.getPackets(i)
        val act = actual.getPackets(i)
        if (exp.port != act.port) return false
        if (exp.payload != act.payload) return false
      }

      for (i in 0 until expected.packetInsCount) {
        if (expected.getPacketIns(i).payload != actual.getPacketIns(i).payload) return false
      }

      return true
    }

    /** Builds a failure description for non-forking traces. */
    private fun buildFailureDescription(
      vector: PacketTestVector,
      actualOutput: SwitchOutput,
    ): String {
      val sb = StringBuilder()
      sb.appendLine("Test vector ${vector.id}: output mismatch.")
      sb.appendLine("Actual output:")
      appendSwitchOutput(sb, actualOutput, indent = "  ")
      sb.appendLine("Acceptable outputs (${vector.acceptableOutputsCount}):")
      for ((idx, acc) in vector.acceptableOutputsList.withIndex()) {
        sb.appendLine("  [$idx]:")
        appendSwitchOutput(sb, acc, indent = "    ")
      }
      return sb.toString().trimEnd()
    }

    /** Builds a failure description for forking traces with unmatched leaves. */
    private fun buildForkingFailureDescription(
      vector: PacketTestVector,
      unmatchedLeaves: List<Pair<String, SwitchOutput>>,
    ): String {
      val sb = StringBuilder()
      sb.appendLine("Test vector ${vector.id}: ${unmatchedLeaves.size} fork branch(es) unmatched.")
      for ((label, output) in unmatchedLeaves) {
        sb.appendLine("Branch '$label':")
        appendSwitchOutput(sb, output, indent = "  ")
      }
      sb.appendLine("Acceptable outputs (${vector.acceptableOutputsCount}):")
      for ((idx, acc) in vector.acceptableOutputsList.withIndex()) {
        sb.appendLine("  [$idx]:")
        appendSwitchOutput(sb, acc, indent = "    ")
      }
      return sb.toString().trimEnd()
    }

    private fun appendSwitchOutput(sb: StringBuilder, output: SwitchOutput, indent: String) {
      if (output.packetsCount == 0 && output.packetInsCount == 0) {
        sb.appendLine("$indent(dropped)")
      } else {
        for (pkt in output.packetsList) {
          sb.appendLine("${indent}port=${pkt.port} payload=${pkt.payload.toHex()}")
        }
        for (pi in output.packetInsList) {
          sb.appendLine("${indent}packet_in payload=${pi.payload.toHex()}")
        }
      }
    }

    private fun ByteString.toHex(): String {
      if (isEmpty) return "0x"
      return "0x" + toByteArray().joinToString("") { "%02X".format(it) }
    }
  }
}
