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
import fourward.dvaas.DvaasProto.PacketTestOutcome
import fourward.dvaas.DvaasProto.PacketTestVector
import fourward.dvaas.DvaasProto.SwitchInput
import fourward.dvaas.DvaasProto.SwitchOutput

/**
 * Translates between sonic-pins DVaaS format and 4ward's native format.
 *
 * Sonic-pins represents packets as hex strings (e.g. "0a0b0c0d") with structured `packetlib.Packet`
 * headers, and metadata as typed `IrValue` oneof fields (hex_str, ipv4, ipv6, mac, str). 4ward uses
 * raw bytes for payloads and metadata values.
 *
 * This adapter handles the format conversion so upstream sonic-pins DVaaS can call 4ward's
 * `GenerateTestVectors` / `ValidateTestVectors` RPCs and interpret the results.
 *
 * **Usage in a sonic-pins fork:**
 *
 * ```
 * // C++ DataplaneValidationBackend implementation:
 * // 1. Convert sonic-pins PacketTestVectors to 4ward format via hex→bytes
 * // 2. Call 4ward's GenerateTestVectors gRPC
 * // 3. Convert results back via bytes→hex
 * ```
 */
object SonicPinsAdapter {

  // ---------------------------------------------------------------------------
  // Hex ↔ bytes conversion
  // ---------------------------------------------------------------------------

  /** Decodes a hex string (e.g. "0a0b0c0d" or "0x0a0b0c0d") to raw bytes. */
  fun hexToBytes(hex: String): ByteArray {
    val cleanHex = hex.removePrefix("0x").removePrefix("0X")
    require(cleanHex.length % 2 == 0) { "hex string must have even length: '$hex'" }
    return ByteArray(cleanHex.length / 2) { i ->
      (cleanHex[2 * i].digitToInt(16) shl 4 or cleanHex[2 * i + 1].digitToInt(16)).toByte()
    }
  }

  /** Encodes raw bytes as a lowercase hex string (no 0x prefix). */
  fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) sb.append("%02x".format(b))
    return sb.toString()
  }

  /** Encodes a ByteString as a lowercase hex string (no 0x prefix), without copying. */
  fun bytesToHex(bytes: ByteString): String {
    val sb = StringBuilder(bytes.size() * 2)
    for (i in 0 until bytes.size()) sb.append("%02x".format(bytes.byteAt(i)))
    return sb.toString()
  }

  // ---------------------------------------------------------------------------
  // Sonic-pins → 4ward conversion
  // ---------------------------------------------------------------------------

  /**
   * Converts a sonic-pins test vector (hex-encoded packet) to 4ward format (raw bytes).
   *
   * @param id Test vector ID (from sonic-pins' `packet_test_vector_by_id` map key).
   * @param port Ingress port string (from sonic-pins `Packet.port`).
   * @param hex Raw packet bytes as hex string (from sonic-pins `Packet.hex`).
   * @param type Injection type (default: DATAPLANE).
   */
  fun toTestVector(
    id: Long,
    port: String,
    hex: String,
    type: InputType = InputType.INPUT_TYPE_DATAPLANE,
  ): PacketTestVector =
    PacketTestVector.newBuilder()
      .setId(id)
      .setInput(
        SwitchInput.newBuilder()
          .setType(type)
          .setPacket(
            Packet.newBuilder().setPort(port).setPayload(ByteString.copyFrom(hexToBytes(hex)))
          )
      )
      .build()

  /**
   * Converts a sonic-pins injection type string to 4ward's InputType enum. Useful when
   * deserializing from external configs or JSON.
   */
  fun parseInjectionType(type: String): InputType =
    when (type.uppercase()) {
      "DEFAULT",
      "DATAPLANE" -> InputType.INPUT_TYPE_DATAPLANE
      "PACKET_OUT" -> InputType.INPUT_TYPE_PACKET_OUT
      "SUBMIT_TO_INGRESS" -> InputType.INPUT_TYPE_SUBMIT_TO_INGRESS
      else -> error("unknown injection type: $type")
    }

  // ---------------------------------------------------------------------------
  // 4ward → sonic-pins conversion
  // ---------------------------------------------------------------------------

  /** Converts a 4ward test outcome to sonic-pins-compatible typed format. */
  fun outcomeToSonicPins(outcome: PacketTestOutcome): SonicPinsOutcome =
    SonicPinsOutcome(
      id = outcome.testVector.id,
      output = switchOutputToHex(outcome.actualOutput),
      traceEvents = outcome.trace.eventsCount,
      passed = outcome.result.passed,
    )

  /**
   * Converts a 4ward SwitchOutput to a sonic-pins-compatible hex-based output.
   *
   * This is the format that would be compared against actual SUT output in the DVaaS flow.
   */
  fun switchOutputToHex(output: SwitchOutput): SonicPinsSwitchOutput {
    val packets = output.packetsList.map { HexPacket(it.port, bytesToHex(it.payload)) }
    val packetIns =
      output.packetInsList.map { pi ->
        HexPacketIn(
          bytesToHex(pi.payload),
          pi.metadataList.map { md -> MetadataField(md.name, bytesToHex(md.value)) },
        )
      }
    return SonicPinsSwitchOutput(packets, packetIns)
  }

  /**
   * Converts a sonic-pins metadata value (IrValue-like string) to raw bytes.
   *
   * Supports:
   * - `hex_str`: hex string like "0x0a8b" or "0a8b"
   * - `ipv4`: dotted decimal like "10.0.0.1"
   * - `ipv6`: standard format like "::1" or "fe80::1"
   * - `mac`: colon-separated like "00:11:22:33:44:55"
   * - `str`: UTF-8 string (encoded as raw bytes)
   */
  fun irValueToBytes(format: String, value: String): ByteArray =
    when (format) {
      "hex_str" -> hexToBytes(value)
      "ipv4" -> ipv4ToBytes(value)
      "ipv6" -> ipv6ToBytes(value)
      "mac" -> macToBytes(value)
      "str" -> value.toByteArray(Charsets.UTF_8)
      else -> error("unknown IrValue format: $format")
    }

  /** Converts an IPv4 string "10.0.0.1" to 4 bytes. */
  fun ipv4ToBytes(ip: String): ByteArray {
    val parts = ip.split(".")
    require(parts.size == 4) { "invalid IPv4 address: $ip" }
    return ByteArray(4) { parts[it].toInt().toByte() }
  }

  /** Converts an IPv6 string to 16 bytes. */
  @Suppress("MagicNumber")
  fun ipv6ToBytes(ip: String): ByteArray {
    val addr = java.net.InetAddress.getByName(ip)
    require(addr is java.net.Inet6Address) { "not an IPv6 address: $ip" }
    return addr.address
  }

  /** Converts a MAC address string "00:11:22:33:44:55" to 6 bytes. */
  @Suppress("MagicNumber")
  fun macToBytes(mac: String): ByteArray {
    val parts = mac.split(":")
    require(parts.size == 6) { "invalid MAC address: $mac" }
    return ByteArray(6) { parts[it].toInt(16).toByte() }
  }

  // ---------------------------------------------------------------------------
  // Data classes for sonic-pins-compatible output
  // ---------------------------------------------------------------------------

  /** A packet in sonic-pins hex format. */
  data class HexPacket(val port: String, val hex: String)

  /** A PacketIn in sonic-pins hex format. */
  data class HexPacketIn(val hex: String, val metadata: List<MetadataField>)

  /** A metadata field in sonic-pins format. */
  data class MetadataField(val name: String, val hexValue: String)

  /** A switch output in sonic-pins hex format. */
  data class SonicPinsSwitchOutput(val packets: List<HexPacket>, val packetIns: List<HexPacketIn>)

  /** A full test outcome in sonic-pins format. */
  data class SonicPinsOutcome(
    val id: Long,
    val output: SonicPinsSwitchOutput,
    val traceEvents: Int,
    val passed: Boolean,
  )
}
