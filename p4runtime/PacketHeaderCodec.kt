/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.BehavioralConfig
import fourward.ir.v1.Type
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.PacketMetadata

/**
 * Converts P4Runtime PacketOut/PacketIn metadata to/from the bit-packed binary headers expected by
 * the P4 parser and deparser.
 *
 * The P4Runtime spec defines PacketOut metadata as structured fields corresponding to the
 * `@controller_header("packet_out")` header. This codec packs metadata values into a binary header
 * that gets prepended to the payload before the simulator processes it.
 */
class PacketHeaderCodec
private constructor(
  private val packetOutFields: List<FieldDef>,
  private val packetInFields: List<FieldDef>,
  /** The CPU port for PacketOut packets (v1model convention: 2^portBits - 2). */
  val cpuPort: Int,
) {
  data class FieldDef(val id: Int, val name: String, val bitWidth: Int)

  /** Total bytes of the serialized packet_out header. */
  val packetOutHeaderBytes: Int = (packetOutFields.sumOf { it.bitWidth } + 7) / 8

  /** Packs PacketOut metadata into a bit-packed binary header. */
  fun serializePacketOut(metadata: List<PacketMetadata>): ByteArray {
    val metadataById = metadata.associateBy { it.metadataId }
    return packFields(packetOutFields, metadataById)
  }

  /**
   * Builds PacketIn metadata from ingress and egress port values.
   *
   * On non-BMv2 platforms, the P4 program does not prepend a packet_in header to CPU-bound packets.
   * Instead, the service constructs metadata from the simulation context.
   */
  fun buildPacketInMetadata(ingressPort: Int, egressPort: Int): List<PacketMetadata> =
    packetInFields.map { field ->
      val value =
        when (field.name) {
          "ingress_port" -> ingressPort
          "target_egress_port" -> egressPort
          else -> 0
        }
      PacketMetadata.newBuilder().setMetadataId(field.id).setValue(encodeMinWidth(value)).build()
    }

  @Suppress("MagicNumber")
  private fun packFields(fields: List<FieldDef>, metadata: Map<Int, PacketMetadata>): ByteArray {
    var bits = 0L
    var totalBits = 0
    for (field in fields) {
      val value = metadata[field.id]?.value?.toByteArray() ?: ByteArray(0)
      var v = 0L
      for (b in value) v = (v shl 8) or (b.toLong() and 0xFF)
      if (field.bitWidth < Long.SIZE_BITS) v = v and ((1L shl field.bitWidth) - 1)
      bits = (bits shl field.bitWidth) or v
      totalBits += field.bitWidth
    }
    val totalBytes = (totalBits + 7) / 8
    // Left-align: p4info excludes @padding fields, but the parser reads from
    // MSB. Shift data bits to the top of the byte-aligned output.
    val paddingBits = totalBytes * 8 - totalBits
    bits = bits shl paddingBits
    val result = ByteArray(totalBytes)
    for (i in totalBytes - 1 downTo 0) {
      result[i] = (bits and 0xFF).toByte()
      bits = bits shr 8
    }
    return result
  }

  companion object {
    /**
     * Creates a codec from the pipeline config, or null if no `controller_packet_metadata` is
     * defined (programs without `@controller_header`).
     */
    fun create(
      p4info: P4Info,
      behavioral: BehavioralConfig,
      cpuPort: Int? = null,
    ): PacketHeaderCodec? {
      val packetOutMeta =
        p4info.controllerPacketMetadataList.find { it.preamble.name == "packet_out" } ?: return null

      // Build header-type → field-name → bitwidth map from behavioral config.
      val headerWidths = buildMap {
        for (type in behavioral.typesList) {
          if (type.hasHeader()) {
            put(type.name, type.header.fieldsList.associate { it.name to bitWidth(it.type) })
          }
        }
      }

      val packetOutType = headerWidths["packet_out_header_t"]
      val packetOutFields =
        packetOutMeta.metadataList.map { meta ->
          FieldDef(
            id = meta.id,
            name = meta.name,
            bitWidth =
              if (meta.bitwidth > 0) meta.bitwidth
              else
                packetOutType?.get(meta.name)
                  ?: error("cannot determine bitwidth for packet_out.${meta.name}"),
          )
        }

      // CPU port = 2^portBits - 2 (v1model convention; drop port is 2^W - 1).
      val portBits =
        packetOutFields.find { it.name == "egress_port" }?.bitWidth ?: DEFAULT_PORT_BITS
      val resolvedCpuPort = cpuPort ?: (1 shl portBits) - 2

      val packetInMeta =
        p4info.controllerPacketMetadataList.find { it.preamble.name == "packet_in" }
      val packetInType = headerWidths["packet_in_header_t"]
      val packetInFields =
        packetInMeta?.metadataList?.map { meta ->
          FieldDef(
            id = meta.id,
            name = meta.name,
            bitWidth =
              if (meta.bitwidth > 0) meta.bitwidth else packetInType?.get(meta.name) ?: portBits,
          )
        } ?: emptyList()

      return PacketHeaderCodec(packetOutFields, packetInFields, resolvedCpuPort)
    }

    private const val DEFAULT_PORT_BITS = 9

    private fun encodeFieldWidth(value: Int, bitWidth: Int): ByteString {
      val byteWidth = (bitWidth + 7) / 8
      if (byteWidth == 0) return ByteString.EMPTY
      val bytes = ByteArray(byteWidth)
      var v = value
      for (i in byteWidth - 1 downTo 0) {
        bytes[i] = (v and 0xFF).toByte()
        v = v ushr 8
      }
      return ByteString.copyFrom(bytes)
    }

    private fun bitWidth(type: Type): Int =
      when {
        type.hasBit() -> type.bit.width
        type.hasSignedInt() -> type.signedInt.width
        type.hasBoolean() -> 1
        else -> error("unsupported type in packet header: $type")
      }
  }
}
