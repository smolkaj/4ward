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

import com.github.gnmi.proto.GetRequest
import com.github.gnmi.proto.Path
import com.github.gnmi.proto.PathElem
import com.github.gnmi.proto.SetRequest
import com.github.gnmi.proto.TypedValue
import com.github.gnmi.proto.Update
import com.github.gnmi.proto.gNMIGrpcKt
import com.google.protobuf.ByteString
import fourward.dvaas.DvaasProto.GenerateTestVectorsRequest
import fourward.dvaas.DvaasProto.InputType
import fourward.dvaas.DvaasProto.Packet
import fourward.dvaas.DvaasProto.PacketTestVector
import fourward.dvaas.DvaasProto.SwitchInput
import fourward.dvaas.DvaasProto.SwitchOutput
import fourward.dvaas.DvaasProto.ValidateTestVectorsRequest
import fourward.ir.PipelineConfig
import fourward.p4runtime.GnmiService
import fourward.p4runtime.P4RuntimeService
import fourward.p4runtime.PacketBroker
import fourward.simulator.Simulator
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeGrpcKt
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.ForwardingPipelineConfig
import p4.v1.P4RuntimeOuterClass.SetForwardingPipelineConfigRequest

/**
 * DVaaS mirror testbed integration test.
 *
 * Exercises the complete upstream DVaaS mirror testbed workflow against 4ward:
 * 1. **gNMI interface discovery** — read interfaces, configure P4RT port IDs.
 * 2. **Pipeline loading** — push SAI P4 middleblock via P4Runtime.
 * 3. **Table entry installation** — install L3 forwarding chain (VRF → IPv4 → nexthop → RIF →
 *    neighbor).
 * 4. **DVaaS test vector validation** — run packets through the pipeline and verify outputs.
 * 5. **PacketIO** — SUBMIT_TO_INGRESS and PACKET_OUT injection modes.
 *
 * This test proves that 4ward can serve as a switch (SUT or control switch) in the upstream
 * sonic-pins DVaaS mirror testbed topology, using all three gRPC services (P4Runtime, gNMI, DVaaS).
 */
class MirrorTestbedTest {

  // ---------------------------------------------------------------------------
  // Test harness: combined P4Runtime + gNMI + DVaaS server
  // ---------------------------------------------------------------------------

  /**
   * A minimal "switch" exposing P4Runtime, gNMI, and DVaaS services — exactly what upstream DVaaS
   * expects from each switch in the mirror testbed.
   */
  private class SwitchHarness : Closeable {
    private val serverName = InProcessServerBuilder.generateName()
    private val simulator = Simulator()
    private val lock = Mutex()
    private val broker = PacketBroker(simulator::processPacket)
    private val p4rtService = P4RuntimeService(simulator, broker, lock = lock)
    private val gnmiService = GnmiService()
    private val dvaasService =
      DvaasService(
        processPacketFn = broker::processPacket,
        lock = lock,
        cpuPortFn = p4rtService::currentCpuPort,
        packetOutInjectorFn = p4rtService::injectPacketOut,
        packetInMetadataFn = p4rtService::buildDvaasPacketInMetadata,
      )

    private val server =
      InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(p4rtService)
        .addService(gnmiService)
        .addService(dvaasService)
        .build()
        .start()

    private val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()

    val p4rt = P4RuntimeGrpcKt.P4RuntimeCoroutineStub(channel)
    val gnmi = gNMIGrpcKt.gNMICoroutineStub(channel)
    val dvaas = DvaasValidationGrpcKt.DvaasValidationCoroutineStub(channel)

    override fun close() {
      p4rtService.close()
      channel.shutdownNow()
      server.shutdownNow()
    }
  }

  // ---------------------------------------------------------------------------
  // Step 1: gNMI interface discovery
  // ---------------------------------------------------------------------------

  @Test
  fun `gNMI discovers interfaces with P4RT port IDs`() = runBlocking {
    SwitchHarness().use { sw ->
      val response =
        sw.gnmi.get(
          GetRequest.newBuilder()
            .setType(GetRequest.DataType.STATE)
            .addPath(interfacesPath())
            .build()
        )

      val json = response.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
      assertTrue("should have OpenConfig format", json.contains("openconfig-interfaces"))
      // Default: 8 Ethernet interfaces with P4RT port IDs 1–8.
      for (i in 0 until 8) {
        assertTrue("should have Ethernet$i", json.contains("Ethernet$i"))
      }
      assertTrue("should have P4RT IDs", json.contains("openconfig-p4rt:id"))
    }
  }

  @Test
  fun `gNMI Set configures P4RT port IDs`() = runBlocking {
    SwitchHarness().use { sw ->
      // Reconfigure Ethernet0 with P4RT ID 100 (like DVaaS mirror testbed setup).
      sw.gnmi.set(
        SetRequest.newBuilder()
          .addUpdate(
            Update.newBuilder()
              .setPath(p4rtIdPath("Ethernet0"))
              .setVal(jsonIetfValue("""{"openconfig-p4rt:id":100}"""))
              .build()
          )
          .build()
      )

      // Verify the change persists in the config view.
      val response =
        sw.gnmi.get(
          GetRequest.newBuilder()
            .setType(GetRequest.DataType.CONFIG)
            .addPath(interfacesPath())
            .build()
        )

      val json = response.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
      assertTrue("should reflect updated P4RT ID", json.contains(""""openconfig-p4rt:id":100"""))
    }
  }

  // ---------------------------------------------------------------------------
  // Step 2: Pipeline loading + table entry installation
  // ---------------------------------------------------------------------------

  @Test
  fun `SAI P4 pipeline loads and accepts L3 forwarding chain`() = runBlocking {
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)

      // Install a complete L3 forwarding chain:
      // VRF("vrf-1") → IPv4(10.0.0.0/8) → nexthop("nhop-1") → RIF("rif-1", Ethernet0) → neighbor.
      installL3ForwardingChain(sw, config)

      // Read back entries to verify they were accepted.
      val vrfTable = findTable(config, "vrf_table")
      val entities = readTableEntries(sw, vrfTable.preamble.id)
      assertTrue("should have at least one vrf_table entry", entities.isNotEmpty())
    }
  }

  // ---------------------------------------------------------------------------
  // Step 3: DVaaS validation with installed forwarding state
  // ---------------------------------------------------------------------------

  @Test
  fun `DVaaS validates packet forwarded through L3 chain`() = runBlocking {
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)
      installL3ForwardingChain(sw, config)

      // Build a test packet: IPv4 dst in 10.0.0.0/8, matching our installed route.
      // The forwarding chain should rewrite the packet and send it to the egress port
      // specified in the router_interface_table entry.
      val payload = buildIpv4Packet(dstIp = byteArrayOf(10, 0, 0, 1))

      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneInput(port = 1, payload = payload))
            // No acceptable_outputs → recording mode. We verify the trace and actual output.
          )
          .build()

      val response = sw.dvaas.validateTestVectors(request)
      assertEquals(1, response.outcomesCount)
      val outcome = response.getOutcomes(0)
      assertTrue("should pass in recording mode", outcome.result.passed)
      assertTrue("should have trace events", outcome.trace.eventsCount > 0)
      assertTrue("should have a packet outcome", outcome.trace.hasPacketOutcome())
    }
  }

  @Test
  fun `DVaaS validates packet dropped with no matching entry`() = runBlocking {
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)
      // Install only a VRF (required for the match), no route.
      installEntry(sw, buildVrfEntry(config, ""))

      val payload = buildIpv4Packet(dstIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 1))

      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneInput(port = 1, payload = payload))
              .addAcceptableOutputs(SwitchOutput.getDefaultInstance()) // Expect drop.
          )
          .build()

      val response = sw.dvaas.validateTestVectors(request)
      assertEquals(1, response.outcomesCount)
      assertTrue("packet should be dropped", response.getOutcomes(0).result.passed)
    }
  }

  // ---------------------------------------------------------------------------
  // Step 4: PacketIO injection modes
  // ---------------------------------------------------------------------------

  @Test
  fun `SUBMIT_TO_INGRESS injects on CPU port`() = runBlocking {
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)

      // SAI P4 has @controller_header("packet_out"), so currentCpuPort() is non-null.
      // SUBMIT_TO_INGRESS enters the ingress parser on the CPU port.
      val payload = buildIpv4Packet(dstIp = byteArrayOf(10, 0, 0, 1))

      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder().setId(1).setInput(submitToIngressInput(payload))
          )
          .build()

      val response = sw.dvaas.validateTestVectors(request)
      assertEquals(1, response.outcomesCount)
      assertTrue("should pass in recording mode", response.getOutcomes(0).result.passed)
    }
  }

  @Test
  fun `PACKET_OUT injects via controller header`() = runBlocking {
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)

      // PACKET_OUT: injects with a serialized packet_out header, directing to the specified
      // egress port. The pipeline's parser extracts egress_port from the header.
      val payload = buildIpv4Packet(dstIp = byteArrayOf(10, 0, 0, 1))

      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(packetOutInput(egressPort = 1, payload = payload))
          )
          .build()

      val response = sw.dvaas.validateTestVectors(request)
      assertEquals(1, response.outcomesCount)
      assertTrue("should pass in recording mode", response.getOutcomes(0).result.passed)
    }
  }

  // ---------------------------------------------------------------------------
  // Step 5: GenerateTestVectors (reference model — Path A)
  // ---------------------------------------------------------------------------

  @Test
  fun `GenerateTestVectors computes expected outputs for L3 forwarding`() = runBlocking {
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)
      installL3ForwardingChain(sw, config)

      // Use GenerateTestVectors as a reference model: given inputs, compute expected outputs.
      // This is the core of Path A — 4ward replacing BMv2 in DVaaS.
      val payload = buildIpv4Packet(dstIp = byteArrayOf(10, 0, 0, 1))
      val request =
        GenerateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneInput(port = 1, payload = payload))
          )
          .build()

      val response = sw.dvaas.generateTestVectors(request)
      assertEquals(1, response.outcomesCount)
      val outcome = response.getOutcomes(0)

      // Reference model should always succeed (no validation, just computation).
      assertTrue("generate should pass", outcome.result.passed)
      // The trace tree should contain the full execution trace.
      assertTrue("should have trace events", outcome.trace.eventsCount > 0)
      assertTrue("should have a packet outcome", outcome.trace.hasPacketOutcome())
      // Input vector should have acceptable_outputs cleared.
      assertEquals(0, outcome.testVector.acceptableOutputsCount)
      assertEquals(1L, outcome.testVector.id)
    }
  }

  @Test
  fun `GenerateTestVectors computes drop for unroutable packet`() = runBlocking {
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)
      // Only install VRF, no route → packets should be dropped.
      installEntry(sw, buildVrfEntry(config, ""))

      val payload = buildIpv4Packet(dstIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
      val request =
        GenerateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneInput(port = 1, payload = payload))
          )
          .build()

      val response = sw.dvaas.generateTestVectors(request)
      val outcome = response.getOutcomes(0)

      assertTrue("generate should pass", outcome.result.passed)
      // No route → drop.
      assertEquals(0, outcome.actualOutput.packetsCount)
      assertEquals(0, outcome.actualOutput.packetInsCount)
    }
  }

  @Test
  fun `GenerateTestVectors used as reference model validates against SUT`() = runBlocking {
    // Full Path A flow: use 4ward as reference model, then validate against itself as SUT.
    // (In production, the SUT would be real hardware — here we use the same 4ward instance
    // to prove the flow works end-to-end.)
    SwitchHarness().use { sw ->
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)
      installL3ForwardingChain(sw, config)

      // 1. Generate expected outputs (reference model).
      val payload = buildIpv4Packet(dstIp = byteArrayOf(10, 0, 0, 1))
      val generateRequest =
        GenerateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneInput(port = 1, payload = payload))
          )
          .build()

      val generateResponse = sw.dvaas.generateTestVectors(generateRequest)
      val referenceOutcome = generateResponse.getOutcomes(0)

      // 2. Use the reference output as acceptable_output for validation against the SUT.
      val validateRequest =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneInput(port = 1, payload = payload))
              .addAcceptableOutputs(referenceOutcome.actualOutput)
          )
          .build()

      val validateResponse = sw.dvaas.validateTestVectors(validateRequest)
      assertEquals(1, validateResponse.outcomesCount)
      assertTrue("SUT should match reference model", validateResponse.getOutcomes(0).result.passed)
    }
  }

  // ---------------------------------------------------------------------------
  // Step 6: Full mirror testbed flow (discovery → config → validate)
  // ---------------------------------------------------------------------------

  @Test
  fun `full mirror testbed flow from gNMI discovery to DVaaS validation`() = runBlocking {
    SwitchHarness().use { sw ->
      // 1. Discover interfaces via gNMI.
      val discoverResponse =
        sw.gnmi.get(
          GetRequest.newBuilder()
            .setType(GetRequest.DataType.STATE)
            .addPath(interfacesPath())
            .build()
        )
      assertTrue("should discover interfaces", discoverResponse.notificationCount > 0)

      // 2. Configure P4RT port IDs via gNMI (mirror testbed assigns sequential IDs).
      sw.gnmi.set(
        SetRequest.newBuilder()
          .addUpdate(
            Update.newBuilder()
              .setPath(p4rtIdPath("Ethernet0"))
              .setVal(jsonIetfValue("""{"openconfig-p4rt:id":1}"""))
              .build()
          )
          .addUpdate(
            Update.newBuilder()
              .setPath(p4rtIdPath("Ethernet1"))
              .setVal(jsonIetfValue("""{"openconfig-p4rt:id":2}"""))
              .build()
          )
          .build()
      )

      // 3. Load SAI P4 pipeline.
      val config = loadConfig("e2e_tests/sai_p4/sai_middleblock.txtpb")
      loadPipeline(sw, config)

      // 4. Install forwarding state.
      installL3ForwardingChain(sw, config)

      // 5. Run DVaaS validation.
      val payload = buildIpv4Packet(dstIp = byteArrayOf(10, 0, 0, 1))
      val request =
        ValidateTestVectorsRequest.newBuilder()
          .addTestVectors(
            PacketTestVector.newBuilder()
              .setId(1)
              .setInput(dataplaneInput(port = 1, payload = payload))
          )
          .addTestVectors(
            PacketTestVector.newBuilder().setId(2).setInput(submitToIngressInput(payload))
          )
          .build()

      val response = sw.dvaas.validateTestVectors(request)
      assertEquals(2, response.outcomesCount)
      assertTrue("DATAPLANE should pass", response.getOutcomes(0).result.passed)
      assertTrue("SUBMIT_TO_INGRESS should pass", response.getOutcomes(1).result.passed)
    }
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private fun loadConfig(relativePath: String) = DvaasTestUtil.loadConfig(relativePath)

  private suspend fun loadPipeline(sw: SwitchHarness, config: PipelineConfig) {
    val fwdConfig =
      ForwardingPipelineConfig.newBuilder()
        .setP4Info(config.p4Info)
        .setP4DeviceConfig(config.device.toByteString())
        .build()
    sw.p4rt.setForwardingPipelineConfig(
      SetForwardingPipelineConfigRequest.newBuilder()
        .setDeviceId(1)
        .setAction(SetForwardingPipelineConfigRequest.Action.VERIFY_AND_COMMIT)
        .setConfig(fwdConfig)
        .build()
    )
  }

  // ---------------------------------------------------------------------------
  // SAI P4 entry builders
  // ---------------------------------------------------------------------------

  /** Installs the full L3 forwarding chain: VRF → IPv4 → nexthop → RIF → neighbor. */
  private suspend fun installL3ForwardingChain(sw: SwitchHarness, config: PipelineConfig) {
    installEntry(sw, buildVrfEntry(config, "vrf-1"))
    installEntry(sw, buildRouterInterfaceEntry(config, "rif-1", "Ethernet0"))
    installEntry(sw, buildNeighborEntry(config, "rif-1", NEIGHBOR_IPV6, NEIGHBOR_MAC))
    installEntry(sw, buildNexthopEntry(config, "nhop-1", "rif-1"))
    installEntry(sw, buildIpv4RouteEntry(config, "vrf-1", "nhop-1"))
  }

  private suspend fun installEntry(sw: SwitchHarness, entity: Entity) {
    sw.p4rt.write(
      P4RuntimeOuterClass.WriteRequest.newBuilder()
        .setDeviceId(1)
        .addUpdates(
          P4RuntimeOuterClass.Update.newBuilder()
            .setType(P4RuntimeOuterClass.Update.Type.INSERT)
            .setEntity(entity)
        )
        .build()
    )
  }

  private suspend fun readTableEntries(sw: SwitchHarness, tableId: Int): List<Entity> {
    val entities = mutableListOf<Entity>()
    sw.p4rt
      .read(
        P4RuntimeOuterClass.ReadRequest.newBuilder()
          .setDeviceId(1)
          .addEntities(
            Entity.newBuilder()
              .setTableEntry(P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(tableId))
          )
          .build()
      )
      .collect { entities.addAll(it.entitiesList) }
    return entities.filter { !it.tableEntry.isDefaultAction }
  }

  private fun findTable(config: PipelineConfig, alias: String) =
    DvaasTestUtil.findTable(config, alias)

  private fun findAction(config: PipelineConfig, alias: String) =
    DvaasTestUtil.findAction(config, alias)

  private fun matchFieldId(table: P4InfoOuterClass.Table, name: String) =
    DvaasTestUtil.matchFieldId(table, name)

  private fun paramId(action: P4InfoOuterClass.Action, name: String) =
    DvaasTestUtil.paramId(action, name)

  @Suppress("MagicNumber")
  private fun buildVrfEntry(config: PipelineConfig, vrfId: String): Entity {
    val table = findTable(config, "vrf_table")
    val action = findAction(config, "no_action")
    return Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(table.preamble.id)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(matchFieldId(table, "vrf_id"))
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFromUtf8(vrfId))
              )
          )
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(P4RuntimeOuterClass.Action.newBuilder().setActionId(action.preamble.id))
          )
      )
      .build()
  }

  @Suppress("MagicNumber")
  private fun buildRouterInterfaceEntry(
    config: PipelineConfig,
    rifId: String,
    port: String,
  ): Entity {
    val table = findTable(config, "router_interface_table")
    val action = findAction(config, "set_port_and_src_mac")
    return Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(table.preamble.id)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(matchFieldId(table, "router_interface_id"))
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFromUtf8(rifId))
              )
          )
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(
                P4RuntimeOuterClass.Action.newBuilder()
                  .setActionId(action.preamble.id)
                  .addParams(
                    P4RuntimeOuterClass.Action.Param.newBuilder()
                      .setParamId(paramId(action, "port"))
                      .setValue(ByteString.copyFromUtf8(port))
                  )
                  .addParams(
                    P4RuntimeOuterClass.Action.Param.newBuilder()
                      .setParamId(paramId(action, "src_mac"))
                      .setValue(ByteString.copyFrom(SRC_MAC))
                  )
              )
          )
      )
      .build()
  }

  @Suppress("MagicNumber")
  private fun buildNeighborEntry(
    config: PipelineConfig,
    rifId: String,
    neighborId: ByteArray,
    dstMac: ByteArray,
  ): Entity {
    val table = findTable(config, "neighbor_table")
    val action = findAction(config, "set_dst_mac")
    return Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(table.preamble.id)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(matchFieldId(table, "router_interface_id"))
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFromUtf8(rifId))
              )
          )
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(matchFieldId(table, "neighbor_id"))
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFrom(neighborId))
              )
          )
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(
                P4RuntimeOuterClass.Action.newBuilder()
                  .setActionId(action.preamble.id)
                  .addParams(
                    P4RuntimeOuterClass.Action.Param.newBuilder()
                      .setParamId(paramId(action, "dst_mac"))
                      .setValue(ByteString.copyFrom(dstMac))
                  )
              )
          )
      )
      .build()
  }

  @Suppress("MagicNumber")
  private fun buildNexthopEntry(config: PipelineConfig, nexthopId: String, rifId: String): Entity {
    val table = findTable(config, "nexthop_table")
    val action = findAction(config, "set_ip_nexthop")
    return Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(table.preamble.id)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(matchFieldId(table, "nexthop_id"))
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFromUtf8(nexthopId))
              )
          )
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(
                P4RuntimeOuterClass.Action.newBuilder()
                  .setActionId(action.preamble.id)
                  .addParams(
                    P4RuntimeOuterClass.Action.Param.newBuilder()
                      .setParamId(paramId(action, "router_interface_id"))
                      .setValue(ByteString.copyFromUtf8(rifId))
                  )
                  .addParams(
                    P4RuntimeOuterClass.Action.Param.newBuilder()
                      .setParamId(paramId(action, "neighbor_id"))
                      .setValue(ByteString.copyFrom(NEIGHBOR_IPV6))
                  )
              )
          )
      )
      .build()
  }

  @Suppress("MagicNumber")
  private fun buildIpv4RouteEntry(
    config: PipelineConfig,
    vrfId: String,
    nexthopId: String,
  ): Entity {
    val table = findTable(config, "ipv4_table")
    val action = findAction(config, "set_nexthop_id")
    val vrfFieldId = matchFieldId(table, "vrf_id")
    val dstFieldId = matchFieldId(table, "ipv4_dst")
    return Entity.newBuilder()
      .setTableEntry(
        P4RuntimeOuterClass.TableEntry.newBuilder()
          .setTableId(table.preamble.id)
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(vrfFieldId)
              .setExact(
                P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                  .setValue(ByteString.copyFromUtf8(vrfId))
              )
          )
          .addMatch(
            P4RuntimeOuterClass.FieldMatch.newBuilder()
              .setFieldId(dstFieldId)
              .setLpm(
                P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
                  .setValue(ByteString.copyFrom(byteArrayOf(10, 0, 0, 0)))
                  .setPrefixLen(8)
              )
          )
          .setAction(
            P4RuntimeOuterClass.TableAction.newBuilder()
              .setAction(
                P4RuntimeOuterClass.Action.newBuilder()
                  .setActionId(action.preamble.id)
                  .addParams(
                    P4RuntimeOuterClass.Action.Param.newBuilder()
                      .setParamId(paramId(action, "nexthop_id"))
                      .setValue(ByteString.copyFromUtf8(nexthopId))
                  )
              )
          )
      )
      .build()
  }

  // ---------------------------------------------------------------------------
  // Packet builders
  // ---------------------------------------------------------------------------

  /** Builds a minimal IPv4 packet with the given destination IP. */
  @Suppress("MagicNumber")
  private fun buildIpv4Packet(dstIp: ByteArray): ByteArray {
    // Ethernet header (14 bytes): dst=FF:FF:FF:FF:FF:FF src=00:00:00:00:00:01 type=0x0800
    val frame = ByteArray(34) // 14 Ethernet + 20 IPv4 minimum
    for (i in 0 until 6) frame[i] = 0xFF.toByte()
    frame[11] = 0x01
    frame[12] = 0x08 // etherType 0x0800 (IPv4)
    frame[13] = 0x00

    // IPv4 header (20 bytes minimum)
    frame[14] = 0x45.toByte() // version=4, IHL=5
    // Total length = 20 (header only)
    frame[16] = 0x00
    frame[17] = 0x14
    // TTL
    frame[22] = 0x40 // TTL=64
    // Protocol (TCP = 6)
    frame[23] = 0x06
    // Source IP: 192.168.0.1
    frame[26] = 192.toByte()
    frame[27] = 168.toByte()
    frame[28] = 0
    frame[29] = 1
    // Destination IP
    System.arraycopy(dstIp, 0, frame, 30, dstIp.size.coerceAtMost(4))
    return frame
  }

  // ---------------------------------------------------------------------------
  // Switch input builders
  // ---------------------------------------------------------------------------

  private fun dataplaneInput(port: Int, payload: ByteArray): SwitchInput =
    SwitchInput.newBuilder()
      .setType(InputType.INPUT_TYPE_DATAPLANE)
      .setPacket(
        Packet.newBuilder().setPort(port.toString()).setPayload(ByteString.copyFrom(payload))
      )
      .build()

  private fun submitToIngressInput(payload: ByteArray): SwitchInput =
    SwitchInput.newBuilder()
      .setType(InputType.INPUT_TYPE_SUBMIT_TO_INGRESS)
      .setPacket(Packet.newBuilder().setPayload(ByteString.copyFrom(payload)))
      .build()

  private fun packetOutInput(egressPort: Int, payload: ByteArray): SwitchInput =
    SwitchInput.newBuilder()
      .setType(InputType.INPUT_TYPE_PACKET_OUT)
      .setPacket(
        Packet.newBuilder().setPort(egressPort.toString()).setPayload(ByteString.copyFrom(payload))
      )
      .build()

  // ---------------------------------------------------------------------------
  // gNMI path helpers
  // ---------------------------------------------------------------------------

  private fun interfacesPath(): Path =
    Path.newBuilder().addElem(PathElem.newBuilder().setName("interfaces")).build()

  private fun p4rtIdPath(ifaceName: String): Path =
    Path.newBuilder()
      .addElem(PathElem.newBuilder().setName("interfaces"))
      .addElem(PathElem.newBuilder().setName("interface").putKey("name", ifaceName))
      .addElem(PathElem.newBuilder().setName("config"))
      .addElem(PathElem.newBuilder().setName("openconfig-p4rt:id"))
      .build()

  private fun jsonIetfValue(json: String): TypedValue =
    TypedValue.newBuilder().setJsonIetfVal(ByteString.copyFromUtf8(json)).build()

  companion object {
    /** Source MAC used in router_interface_table entries. */
    @Suppress("MagicNumber") private val SRC_MAC = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)

    /** Destination MAC used in neighbor_table entries. */
    @Suppress("MagicNumber")
    private val NEIGHBOR_MAC =
      byteArrayOf(
        0xAA.toByte(),
        0xBB.toByte(),
        0xCC.toByte(),
        0xDD.toByte(),
        0xEE.toByte(),
        0xFF.toByte(),
      )

    /** Neighbor ID: IPv6 address ::1 (16 bytes, matching ipv6_addr_t). */
    @Suppress("MagicNumber") private val NEIGHBOR_IPV6 = ByteArray(16) { if (it == 15) 1 else 0 }
  }
}
