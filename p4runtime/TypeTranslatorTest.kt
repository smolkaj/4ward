package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.ir.v1.TranslationEntry
import fourward.ir.v1.TypeTranslation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [TypeTranslator]'s core mapping logic.
 *
 * These test the bidirectional SDN ↔ data-plane value mapping directly, without going through the
 * P4Runtime gRPC service or simulator. Three modes are covered:
 * 1. **Explicit** — all mappings provided upfront; unknown values rejected.
 * 2. **Auto-allocate** — mappings created on first use; data-plane values assigned sequentially.
 * 3. **Hybrid** — explicit pins for known values, auto-allocate for the rest.
 *
 * Both `sdn_bitwidth` (numeric ↔ numeric) and `sdn_string` (string ↔ numeric) are tested.
 */
class TypeTranslatorTest {

  // ===========================================================================
  // Explicit mapping: sdn_bitwidth (numeric → numeric)
  // ===========================================================================

  @Test
  fun `explicit numeric mapping translates SDN to dataplane`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
          addEntries(entry(sdnBytes(2000), dpBytes(2)))
        }
      )

    assertArrayEquals(dpBytes(1), translator.sdnToDataplane("test.port_id", sdnBytes(1000)))
    assertArrayEquals(dpBytes(2), translator.sdnToDataplane("test.port_id", sdnBytes(2000)))
  }

  @Test
  fun `explicit numeric mapping translates dataplane to SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
          addEntries(entry(sdnBytes(2000), dpBytes(2)))
        }
      )

    val result = translator.dataplaneToSdn("test.port_id", dpBytes(1))
    assertEquals(SdnValue.Bitstring(bs(sdnBytes(1000))), result)
  }

  @Test
  fun `explicit mapping rejects unknown SDN value`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = false
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
        }
      )

    assertThrows(TranslationException::class.java) {
      translator.sdnToDataplane("test.port_id", sdnBytes(9999))
    }
  }

  @Test
  fun `explicit mapping rejects unknown dataplane value`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = false
          addEntries(entry(sdnBytes(1000), dpBytes(1)))
        }
      )

    assertThrows(TranslationException::class.java) {
      translator.dataplaneToSdn("test.port_id", dpBytes(99))
    }
  }

  // ===========================================================================
  // Explicit mapping: sdn_string (string → numeric)
  // ===========================================================================

  @Test
  fun `explicit string mapping translates SDN to dataplane`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          addEntries(stringEntry("Ethernet0", dpBytes(0)))
          addEntries(stringEntry("Ethernet1", dpBytes(1)))
          addEntries(stringEntry("CpuPort", dpBytes(510)))
        }
      )

    assertArrayEquals(dpBytes(0), translator.sdnToDataplane("sai.port", "Ethernet0"))
    assertArrayEquals(dpBytes(510), translator.sdnToDataplane("sai.port", "CpuPort"))
  }

  @Test
  fun `explicit string mapping translates dataplane to SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          addEntries(stringEntry("Ethernet0", dpBytes(0)))
        }
      )

    val result = translator.dataplaneToSdn("sai.port", dpBytes(0))
    assertEquals(SdnValue.Str("Ethernet0"), result)
  }

  // ===========================================================================
  // Auto-allocate: sdn_bitwidth
  // ===========================================================================

  @Test
  fun `auto-allocate assigns sequential dataplane values for numeric SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = true
        }
      )

    // First value seen gets dataplane value 0, second gets 1, etc.
    val dp0 = translator.sdnToDataplane("test.port_id", sdnBytes(5000))
    val dp1 = translator.sdnToDataplane("test.port_id", sdnBytes(6000))

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)

    // Same SDN value returns same dataplane value (idempotent).
    assertArrayEquals(dpBytes(0), translator.sdnToDataplane("test.port_id", sdnBytes(5000)))
  }

  @Test
  fun `auto-allocate reverse lookup works after allocation`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.port_id"
          autoAllocate = true
        }
      )

    translator.sdnToDataplane("test.port_id", sdnBytes(5000))

    val result = translator.dataplaneToSdn("test.port_id", dpBytes(0))
    assertEquals(SdnValue.Bitstring(bs(sdnBytes(5000))), result)
  }

  // ===========================================================================
  // Auto-allocate: sdn_string
  // ===========================================================================

  @Test
  fun `auto-allocate assigns sequential dataplane values for string SDN`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          autoAllocate = true
        }
      )

    val dp0 = translator.sdnToDataplane("sai.port", "Ethernet0")
    val dp1 = translator.sdnToDataplane("sai.port", "Ethernet1")

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)

    // Idempotent.
    assertArrayEquals(dpBytes(0), translator.sdnToDataplane("sai.port", "Ethernet0"))
  }

  @Test
  fun `auto-allocate string reverse lookup works`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          autoAllocate = true
        }
      )

    translator.sdnToDataplane("sai.port", "Ethernet0")

    val result = translator.dataplaneToSdn("sai.port", dpBytes(0))
    assertEquals(SdnValue.Str("Ethernet0"), result)
  }

  // ===========================================================================
  // Default behavior: no TypeTranslation provided for a URI
  // ===========================================================================

  @Test
  fun `missing TypeTranslation defaults to auto-allocate`() {
    // No TypeTranslation provided — the translator should still handle the URI
    // by auto-allocating.
    val translator = buildTranslator()

    val dp0 = translator.sdnToDataplane("unknown.uri", sdnBytes(42))
    assertArrayEquals(dpBytes(0), dp0)
  }

  // ===========================================================================
  // Hybrid: explicit pins + auto-allocate
  // ===========================================================================

  @Test
  fun `hybrid uses explicit mappings and auto-allocates the rest`() {
    val translator =
      buildTranslator(
        translation {
          uri = "sai.port"
          autoAllocate = true
          addEntries(stringEntry("CpuPort", dpBytes(510)))
          addEntries(stringEntry("DropPort", dpBytes(511)))
        }
      )

    // Explicit entries work.
    assertArrayEquals(dpBytes(510), translator.sdnToDataplane("sai.port", "CpuPort"))
    assertArrayEquals(dpBytes(511), translator.sdnToDataplane("sai.port", "DropPort"))

    // Auto-allocated entries skip reserved dataplane values.
    val dp0 = translator.sdnToDataplane("sai.port", "Ethernet0")
    val dp1 = translator.sdnToDataplane("sai.port", "Ethernet1")

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)
  }

  @Test
  fun `hybrid auto-allocate skips explicitly reserved dataplane values`() {
    val translator =
      buildTranslator(
        translation {
          uri = "test.type"
          autoAllocate = true
          // Pin dataplane value 0 to SDN value 100.
          addEntries(entry(sdnBytes(100), dpBytes(0)))
        }
      )

    // Auto-allocate should skip 0 (reserved) and start at 1.
    val dp = translator.sdnToDataplane("test.type", sdnBytes(200))
    assertArrayEquals(dpBytes(1), dp)
  }

  // ===========================================================================
  // Multiple URIs are independent
  // ===========================================================================

  @Test
  fun `different URIs have independent mapping tables`() {
    val translator =
      buildTranslator(
        translation {
          uri = "type.a"
          autoAllocate = true
        },
        translation {
          uri = "type.b"
          autoAllocate = true
        },
      )

    val dpA = translator.sdnToDataplane("type.a", sdnBytes(42))
    val dpB = translator.sdnToDataplane("type.b", sdnBytes(42))

    // Both get dataplane value 0 — they're independent.
    assertArrayEquals(dpBytes(0), dpA)
    assertArrayEquals(dpBytes(0), dpB)
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** Builds a TypeTranslator from the given TypeTranslation configs. */
  private fun buildTranslator(vararg translations: TypeTranslation): TypeTranslator =
    TypeTranslator.create(translations.toList())

  /** Shorthand for building a TypeTranslation proto. */
  private fun translation(block: TypeTranslation.Builder.() -> Unit): TypeTranslation =
    TypeTranslation.newBuilder().apply(block).build()

  /** Builds a numeric-to-numeric TranslationEntry. */
  private fun entry(sdnValue: ByteArray, dataplaneValue: ByteArray): TranslationEntry =
    TranslationEntry.newBuilder()
      .setSdnBitstring(ByteString.copyFrom(sdnValue))
      .setDataplaneValue(ByteString.copyFrom(dataplaneValue))
      .build()

  /** Builds a string-to-numeric TranslationEntry. */
  private fun stringEntry(sdnStr: String, dataplaneValue: ByteArray): TranslationEntry =
    TranslationEntry.newBuilder()
      .setSdnStr(sdnStr)
      .setDataplaneValue(ByteString.copyFrom(dataplaneValue))
      .build()

  /** Minimum-width big-endian encoding of a non-negative integer. */
  private fun sdnBytes(value: Int): ByteArray = encodeMinWidth(value)

  private fun dpBytes(value: Int): ByteArray = encodeMinWidth(value)

  private fun encodeMinWidth(value: Int): ByteArray {
    if (value == 0) return byteArrayOf(0)
    val bytes = mutableListOf<Byte>()
    var v = value
    while (v > 0) {
      bytes.add(0, (v and 0xFF).toByte())
      v = v shr 8
    }
    return bytes.toByteArray()
  }

  /** Wraps a ByteArray as ByteString. */
  private fun bs(bytes: ByteArray): ByteString = ByteString.copyFrom(bytes)
}
