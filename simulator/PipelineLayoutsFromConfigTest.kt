package fourward.simulator

import fourward.ir.BehavioralConfig
import fourward.ir.BitType
import fourward.ir.DeviceConfig
import fourward.ir.FieldDecl
import fourward.ir.HeaderDecl
import fourward.ir.PipelineConfig
import fourward.ir.StructDecl
import fourward.ir.Type
import fourward.ir.TypeDecl
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests that [computeLayouts] pulls TypeDecls out of a real [PipelineConfig]. */
class PipelineLayoutsFromConfigTest {

  @Test
  fun `computeLayouts extracts every TypeDecl from the pipeline config`() {
    val config =
      PipelineConfig.newBuilder()
        .setDevice(
          DeviceConfig.newBuilder()
            .setBehavioral(
              BehavioralConfig.newBuilder()
                .addTypes(
                  TypeDecl.newBuilder()
                    .setName("ethernet_t")
                    .setHeader(
                      HeaderDecl.newBuilder()
                        .addFields(fieldDecl("dstAddr", bit(48)))
                        .addFields(fieldDecl("etherType", bit(16)))
                    )
                    .build()
                )
                .addTypes(
                  TypeDecl.newBuilder()
                    .setName("metadata_t")
                    .setStruct(
                      StructDecl.newBuilder()
                        .addFields(fieldDecl("ingress_port", bit(9)))
                        .addFields(fieldDecl("priority", bit(3)))
                    )
                    .build()
                )
                .build()
            )
            .build()
        )
        .build()

    val layouts = computeLayouts(config)

    val ethernet = layouts.headers.getValue("ethernet_t")
    assertEquals(FieldSlot(0, 48), ethernet.fields["dstAddr"])
    assertEquals(FieldSlot(48, 16), ethernet.fields["etherType"])

    val meta = layouts.structs.getValue("metadata_t")
    assertEquals(PrimitiveField(0, 9), meta.members["ingress_port"])
    assertEquals(PrimitiveField(9, 3), meta.members["priority"])
  }

  @Test
  fun `empty config yields empty layouts`() {
    val config = PipelineConfig.newBuilder().build()
    val layouts = computeLayouts(config)
    assertEquals(emptyMap<String, HeaderLayout>(), layouts.headers)
    assertEquals(emptyMap<String, StructLayout>(), layouts.structs)
  }

  // Helpers.
  private fun fieldDecl(name: String, type: Type) =
    FieldDecl.newBuilder().setName(name).setType(type).build()

  private fun bit(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()
}
