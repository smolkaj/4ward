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

import fourward.dvaas.DvaasProto.GenerateTestVectorsRequest
import fourward.dvaas.DvaasProto.GenerateTestVectorsResponse
import fourward.dvaas.DvaasProto.PacketMetadata
import fourward.dvaas.DvaasProto.ValidateTestVectorsRequest
import fourward.dvaas.DvaasProto.ValidateTestVectorsResponse
import fourward.simulator.ProcessPacketResult
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DVaaS validation gRPC service: validates packet test vectors against the loaded pipeline, and
 * generates expected outputs as a reference model (replacing BMv2).
 *
 * Serialized via a shared [lock] with P4RuntimeService and DataplaneService to prevent races
 * between control-plane writes and data-plane packet processing.
 *
 * @param processPacketFn Packet processing function (typically [PacketBroker.processPacket]). Using
 *   a function reference rather than a direct PacketBroker dependency avoids a circular build
 *   dependency between the dvaas and p4runtime packages.
 * @param cpuPortFn Returns the current CPU port number, or null if CPU port is not configured.
 *   Resolved dynamically per-request to track pipeline changes.
 * @param packetOutInjectorFn Injects a PacketOut with the given egress port and payload. Null if
 *   the pipeline has no `@controller_header("packet_out")`.
 * @param packetInMetadataFn Builds PacketIn metadata for an output packet on the CPU port. Takes
 *   (ingressPort, egressPort) and returns metadata fields.
 */
class DvaasService(
  processPacketFn: (ingressPort: Int, payload: ByteArray) -> ProcessPacketResult,
  private val lock: Mutex,
  cpuPortFn: () -> Int? = { null },
  packetOutInjectorFn: ((egressPort: Int, payload: ByteArray) -> ProcessPacketResult)? = null,
  packetInMetadataFn: ((ingressPort: Int, egressPort: Int) -> List<PacketMetadata>)? = null,
) : DvaasValidationGrpcKt.DvaasValidationCoroutineImplBase() {

  private val validator =
    TestVectorValidator(processPacketFn, cpuPortFn, packetOutInjectorFn, packetInMetadataFn)

  override suspend fun validateTestVectors(
    request: ValidateTestVectorsRequest
  ): ValidateTestVectorsResponse {
    if (request.testVectorsCount == 0) {
      return ValidateTestVectorsResponse.getDefaultInstance()
    }

    val outcomes =
      lock.withLock {
        try {
          validator.validateAll(request.testVectorsList)
        } catch (e: IllegalStateException) {
          // Simulator and TestVectorValidator throw IllegalStateException for
          // configuration errors (no pipeline loaded, no CPU port, no codec).
          throw StatusException(Status.FAILED_PRECONDITION.withDescription(e.message))
        } catch (e: IllegalArgumentException) {
          throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
        }
      }

    return ValidateTestVectorsResponse.newBuilder().addAllOutcomes(outcomes).build()
  }

  override suspend fun generateTestVectors(
    request: GenerateTestVectorsRequest
  ): GenerateTestVectorsResponse {
    if (request.testVectorsCount == 0) {
      return GenerateTestVectorsResponse.getDefaultInstance()
    }

    val outcomes =
      lock.withLock {
        try {
          validator.generateAll(request.testVectorsList)
        } catch (e: IllegalStateException) {
          throw StatusException(Status.FAILED_PRECONDITION.withDescription(e.message))
        } catch (e: IllegalArgumentException) {
          throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
        }
      }

    return GenerateTestVectorsResponse.newBuilder().addAllOutcomes(outcomes).build()
  }
}
