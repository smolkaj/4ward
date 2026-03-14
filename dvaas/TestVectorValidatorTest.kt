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
import fourward.dvaas.DvaasProto.PacketTestVector
import fourward.dvaas.DvaasProto.SwitchInput
import fourward.dvaas.DvaasProto.SwitchOutput
import fourward.sim.SimulatorProto.OutputPacket
import fourward.sim.SimulatorProto.TraceTree
import fourward.simulator.ProcessPacketResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestVectorValidatorTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Builds a validator with a stub process function that returns [outputs]. */
  private fun validatorReturning(
    vararg outputs: OutputPacket,
    cpuPort: Int? = null,
  ): TestVectorValidator =
    TestVectorValidator(
      processPacketFn = { _, _ ->
        val trace =
          if (outputs.isEmpty()) {
            // Dropped — no output.
            TraceTree.newBuilder()
              .setPacketOutcome(
                fourward.sim.SimulatorProto.PacketOutcome.newBuilder()
                  .setDrop(fourward.sim.SimulatorProto.Drop.getDefaultInstance())
              )
              .build()
          } else if (outputs.size == 1) {
            TraceTree.newBuilder()
              .setPacketOutcome(
                fourward.sim.SimulatorProto.PacketOutcome.newBuilder().setOutput(outputs[0])
              )
              .build()
          } else {
            // Multiple outputs → use fork.
            TraceTree.newBuilder()
              .setForkOutcome(
                fourward.sim.SimulatorProto.Fork.newBuilder()
                  .addAllBranches(
                    outputs.mapIndexed { i, out ->
                      fourward.sim.SimulatorProto.ForkBranch.newBuilder()
                        .setLabel("branch_$i")
                        .setSubtree(
                          TraceTree.newBuilder()
                            .setPacketOutcome(
                              fourward.sim.SimulatorProto.PacketOutcome.newBuilder().setOutput(out)
                            )
                        )
                        .build()
                    }
                  )
              )
              .build()
          }
        ProcessPacketResult(outputs.toList(), trace)
      },
      cpuPort = cpuPort,
    )

  private fun output(port: Int, payload: ByteArray = byteArrayOf(0x01)): OutputPacket =
    OutputPacket.newBuilder()
      .setEgressPort(port)
      .setPayload(ByteString.copyFrom(payload))
      .build()

  private fun testVector(
    ingressPort: Int,
    payload: ByteArray,
    acceptableOutputs: List<SwitchOutput> = emptyList(),
    id: Long = 0,
  ): PacketTestVector =
    PacketTestVector.newBuilder()
      .setId(id)
      .setInput(
        SwitchInput.newBuilder()
          .setType(InputType.INPUT_TYPE_DATAPLANE)
          .setPacket(
            Packet.newBuilder()
              .setPort(ingressPort.toString())
              .setPayload(ByteString.copyFrom(payload))
          )
      )
      .addAllAcceptableOutputs(acceptableOutputs)
      .build()

  private fun switchOutput(vararg packets: Packet): SwitchOutput =
    SwitchOutput.newBuilder().addAllPackets(packets.toList()).build()

  private fun switchOutputWithPacketIns(vararg packetIns: PacketIn): SwitchOutput =
    SwitchOutput.newBuilder().addAllPacketIns(packetIns.toList()).build()

  private fun packet(port: Int, payload: ByteArray = byteArrayOf(0x01)): Packet =
    Packet.newBuilder()
      .setPort(port.toString())
      .setPayload(ByteString.copyFrom(payload))
      .build()

  private fun packetIn(payload: ByteArray = byteArrayOf(0x01)): PacketIn =
    PacketIn.newBuilder().setPayload(ByteString.copyFrom(payload)).build()

  // ---------------------------------------------------------------------------
  // Output comparison (outputsMatch)
  // ---------------------------------------------------------------------------

  @Test
  fun `outputsMatch returns true for identical empty outputs`() {
    val a = SwitchOutput.getDefaultInstance()
    val b = SwitchOutput.getDefaultInstance()
    assertTrue(TestVectorValidator.outputsMatch(a, b))
  }

  @Test
  fun `outputsMatch returns true for identical single-packet outputs`() {
    val a = switchOutput(packet(1))
    val b = switchOutput(packet(1))
    assertTrue(TestVectorValidator.outputsMatch(a, b))
  }

  @Test
  fun `outputsMatch returns false for different ports`() {
    val a = switchOutput(packet(1))
    val b = switchOutput(packet(2))
    assertFalse(TestVectorValidator.outputsMatch(a, b))
  }

  @Test
  fun `outputsMatch returns false for different payloads`() {
    val a = switchOutput(packet(1, byteArrayOf(0x01)))
    val b = switchOutput(packet(1, byteArrayOf(0x02)))
    assertFalse(TestVectorValidator.outputsMatch(a, b))
  }

  @Test
  fun `outputsMatch returns false for different packet counts`() {
    val a = switchOutput(packet(1), packet(2))
    val b = switchOutput(packet(1))
    assertFalse(TestVectorValidator.outputsMatch(a, b))
  }

  @Test
  fun `outputsMatch compares packet_ins`() {
    val a = switchOutputWithPacketIns(packetIn(byteArrayOf(0xAA.toByte())))
    val b = switchOutputWithPacketIns(packetIn(byteArrayOf(0xAA.toByte())))
    assertTrue(TestVectorValidator.outputsMatch(a, b))
  }

  @Test
  fun `outputsMatch rejects different packet_in payloads`() {
    val a = switchOutputWithPacketIns(packetIn(byteArrayOf(0xAA.toByte())))
    val b = switchOutputWithPacketIns(packetIn(byteArrayOf(0xBB.toByte())))
    assertFalse(TestVectorValidator.outputsMatch(a, b))
  }

  // ---------------------------------------------------------------------------
  // Validation: exact match
  // ---------------------------------------------------------------------------

  @Test
  fun `validate passes when actual output matches expected`() {
    val validator = validatorReturning(output(1))
    val vector = testVector(0, byteArrayOf(0x01), listOf(switchOutput(packet(1))))
    val outcome = validator.validate(vector)
    assertTrue(outcome.result.passed)
  }

  @Test
  fun `validate fails when actual output differs from expected`() {
    val validator = validatorReturning(output(2))
    val vector = testVector(0, byteArrayOf(0x01), listOf(switchOutput(packet(1))))
    val outcome = validator.validate(vector)
    assertFalse(outcome.result.passed)
    assertTrue(outcome.result.failureDescription.contains("mismatch"))
  }

  // ---------------------------------------------------------------------------
  // Drop detection
  // ---------------------------------------------------------------------------

  @Test
  fun `validate passes when expecting drop and packet is dropped`() {
    // Empty acceptable output = expect drop.
    val validator = validatorReturning() // No outputs = drop.
    val vector = testVector(0, byteArrayOf(0x01), listOf(SwitchOutput.getDefaultInstance()))
    val outcome = validator.validate(vector)
    assertTrue(outcome.result.passed)
  }

  @Test
  fun `validate fails when expecting drop but packet is forwarded`() {
    val validator = validatorReturning(output(1))
    val vector = testVector(0, byteArrayOf(0x01), listOf(SwitchOutput.getDefaultInstance()))
    val outcome = validator.validate(vector)
    assertFalse(outcome.result.passed)
  }

  @Test
  fun `validate fails when expecting output but packet is dropped`() {
    val validator = validatorReturning()
    val vector = testVector(0, byteArrayOf(0x01), listOf(switchOutput(packet(1))))
    val outcome = validator.validate(vector)
    assertFalse(outcome.result.passed)
  }

  // ---------------------------------------------------------------------------
  // Multiple acceptable outputs
  // ---------------------------------------------------------------------------

  @Test
  fun `validate passes when actual matches second acceptable output`() {
    val validator = validatorReturning(output(2))
    val vector =
      testVector(
        0,
        byteArrayOf(0x01),
        listOf(switchOutput(packet(1)), switchOutput(packet(2))),
      )
    val outcome = validator.validate(vector)
    assertTrue(outcome.result.passed)
  }

  @Test
  fun `validate fails when actual matches none of the acceptable outputs`() {
    val validator = validatorReturning(output(3))
    val vector =
      testVector(
        0,
        byteArrayOf(0x01),
        listOf(switchOutput(packet(1)), switchOutput(packet(2))),
      )
    val outcome = validator.validate(vector)
    assertFalse(outcome.result.passed)
  }

  // ---------------------------------------------------------------------------
  // Recording mode (no expectations)
  // ---------------------------------------------------------------------------

  @Test
  fun `validate passes unconditionally with empty acceptable_outputs (recording mode)`() {
    val validator = validatorReturning(output(1))
    val vector = testVector(0, byteArrayOf(0x01)) // No acceptable_outputs.
    val outcome = validator.validate(vector)
    assertTrue(outcome.result.passed)
  }

  // ---------------------------------------------------------------------------
  // CPU port classification (PacketIn)
  // ---------------------------------------------------------------------------

  @Test
  fun `outputs on CPU port are classified as packet_ins`() {
    val cpuPort = 510
    val validator = validatorReturning(output(cpuPort, byteArrayOf(0xAA.toByte())), cpuPort = cpuPort)
    val vector =
      testVector(
        0,
        byteArrayOf(0x01),
        listOf(switchOutputWithPacketIns(packetIn(byteArrayOf(0xAA.toByte())))),
      )
    val outcome = validator.validate(vector)
    assertTrue(outcome.result.passed)
    // Verify classification: actual_output should have packet_ins, not packets.
    assertEquals(0, outcome.actualOutput.packetsCount)
    assertEquals(1, outcome.actualOutput.packetInsCount)
  }

  @Test
  fun `front-panel outputs and CPU port outputs are separated`() {
    val cpuPort = 510
    val validator =
      validatorReturning(output(1), output(cpuPort, byteArrayOf(0xBB.toByte())), cpuPort = cpuPort)

    val vector = testVector(0, byteArrayOf(0x01)) // Recording mode.
    val outcome = validator.validate(vector)
    assertEquals(1, outcome.actualOutput.packetsCount)
    assertEquals("1", outcome.actualOutput.getPackets(0).port)
    assertEquals(1, outcome.actualOutput.packetInsCount)
  }

  // ---------------------------------------------------------------------------
  // Batch validation
  // ---------------------------------------------------------------------------

  @Test
  fun `validateAll returns one outcome per vector`() {
    val validator = validatorReturning(output(1))
    val vectors = listOf(testVector(0, byteArrayOf(0x01), id = 1), testVector(0, byteArrayOf(0x02), id = 2))
    val outcomes = validator.validateAll(vectors)
    assertEquals(2, outcomes.size)
    assertEquals(1L, outcomes[0].testVector.id)
    assertEquals(2L, outcomes[1].testVector.id)
  }

  // ---------------------------------------------------------------------------
  // Trace tree is included in outcome
  // ---------------------------------------------------------------------------

  @Test
  fun `outcome includes trace tree`() {
    val validator = validatorReturning(output(1))
    val vector = testVector(0, byteArrayOf(0x01))
    val outcome = validator.validate(vector)
    assertTrue(outcome.hasTrace())
    assertTrue(outcome.trace.hasPacketOutcome())
  }

  // ---------------------------------------------------------------------------
  // Forking traces (action selector / multicast)
  // ---------------------------------------------------------------------------

  @Test
  fun `forking trace passes when each branch matches an acceptable output`() {
    // Two branches: port 1 and port 2. Both must be acceptable.
    val validator = validatorReturning(output(1), output(2))
    val vector =
      testVector(
        0,
        byteArrayOf(0x01),
        listOf(switchOutput(packet(1)), switchOutput(packet(2))),
      )
    val outcome = validator.validate(vector)
    assertTrue(outcome.result.passed)
  }

  @Test
  fun `forking trace fails when a branch has no matching acceptable output`() {
    // Branch outputs: port 2 and port 3. Only port 1 is acceptable.
    val validator = validatorReturning(output(2), output(3))
    val vector =
      testVector(0, byteArrayOf(0x01), listOf(switchOutput(packet(1))))
    val outcome = validator.validate(vector)
    assertFalse(outcome.result.passed)
    assertTrue(outcome.result.failureDescription.contains("fork branch"))
  }

  @Test
  fun `forking trace actual_output contains all branch outputs concatenated`() {
    val validator = validatorReturning(output(1), output(2))
    val vector = testVector(0, byteArrayOf(0x01)) // Recording mode.
    val outcome = validator.validate(vector)
    // actual_output should contain both outputs.
    assertEquals(2, outcome.actualOutput.packetsCount)
  }

  // ---------------------------------------------------------------------------
  // Failure description
  // ---------------------------------------------------------------------------

  @Test
  fun `failure description includes expected and actual`() {
    val validator = validatorReturning(output(42))
    val vector = testVector(0, byteArrayOf(0x01), listOf(switchOutput(packet(1))), id = 7)
    val outcome = validator.validate(vector)
    val desc = outcome.result.failureDescription
    assertTrue(desc.contains("Test vector 7"))
    assertTrue(desc.contains("port=42"))
    assertTrue(desc.contains("port=1"))
  }

  @Test
  fun `failure description shows dropped for empty output`() {
    val validator = validatorReturning()
    val vector = testVector(0, byteArrayOf(0x01), listOf(switchOutput(packet(1))), id = 3)
    val outcome = validator.validate(vector)
    assertTrue(outcome.result.failureDescription.contains("(dropped)"))
  }

  // ---------------------------------------------------------------------------
  // Input type validation
  // ---------------------------------------------------------------------------

  @Test
  fun `validate rejects unsupported input type`() {
    val validator = validatorReturning(output(1))
    val vector =
      PacketTestVector.newBuilder()
        .setInput(
          SwitchInput.newBuilder()
            .setType(InputType.INPUT_TYPE_PACKET_OUT)
            .setPacket(Packet.newBuilder().setPort("0").setPayload(ByteString.copyFrom(byteArrayOf(0x01))))
        )
        .build()
    val e = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { validator.validate(vector) }
    assertTrue(e.message!!.contains("unsupported input type"))
  }
}
