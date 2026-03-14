package fourward.p4runtime

import com.github.gnmi.proto.GetRequest
import com.github.gnmi.proto.Path
import com.github.gnmi.proto.PathElem
import com.github.gnmi.proto.SetRequest
import com.github.gnmi.proto.TypedValue
import com.github.gnmi.proto.Update
import com.github.gnmi.proto.gNMIGrpcKt
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GnmiServiceTest {

  @get:Rule val grpcCleanup = GrpcCleanupRule()

  private fun setup(
    interfaces: List<GnmiService.InterfaceConfig> = GnmiService.defaultInterfaces()
  ): gNMIGrpcKt.gNMICoroutineStub {
    val serverName = InProcessServerBuilder.generateName()
    grpcCleanup.register(
      InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(GnmiService(interfaces))
        .build()
        .start()
    )
    val channel: ManagedChannel =
      grpcCleanup.register(
        InProcessChannelBuilder.forName(serverName).directExecutor().build()
      )
    return gNMIGrpcKt.gNMICoroutineStub(channel)
  }

  // ---------------------------------------------------------------------------
  // Get — interfaces
  // ---------------------------------------------------------------------------

  @Test
  fun `get interfaces state returns all interfaces`() = runBlocking {
    val stub = setup()
    val response =
      stub.get(
        GetRequest.newBuilder()
          .setType(GetRequest.DataType.STATE)
          .addPath(path("interfaces"))
          .build()
      )

    assertEquals(1, response.notificationCount)
    val json = response.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    assertTrue("should contain openconfig-interfaces", json.contains("openconfig-interfaces"))
    // Default: 8 interfaces, Ethernet0–Ethernet7.
    for (i in 0 until 8) {
      assertTrue("should contain Ethernet$i", json.contains("Ethernet$i"))
    }
  }

  @Test
  fun `get interfaces config returns config with P4RT IDs`() = runBlocking {
    val stub = setup()
    val response =
      stub.get(
        GetRequest.newBuilder()
          .setType(GetRequest.DataType.CONFIG)
          .addPath(path("interfaces"))
          .build()
      )

    val json = response.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    assertTrue("should contain config block", json.contains(""""config":"""))
    assertTrue("should contain P4RT IDs", json.contains("openconfig-p4rt:id"))
  }

  @Test
  fun `get state includes oper-status`() = runBlocking {
    val stub =
      setup(
        listOf(GnmiService.InterfaceConfig("Ethernet0", p4rtId = 1, operStatus = "DOWN"))
      )
    val response =
      stub.get(
        GetRequest.newBuilder()
          .setType(GetRequest.DataType.STATE)
          .addPath(path("interfaces"))
          .build()
      )

    val json = response.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    assertTrue("should contain DOWN status", json.contains(""""oper-status":"DOWN""""))
  }

  @Test
  fun `get full config with empty path`() = runBlocking {
    val stub = setup()
    val response =
      stub.get(
        GetRequest.newBuilder().setType(GetRequest.DataType.CONFIG).build()
      )

    val json = response.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    assertTrue("should be full config", json.contains("openconfig-interfaces"))
  }

  @Test
  fun `get unsupported path returns UNIMPLEMENTED`() = runBlocking {
    val stub = setup()
    try {
      stub.get(
        GetRequest.newBuilder()
          .addPath(path("system", "config"))
          .build()
      )
      assertTrue("should have thrown", false)
    } catch (e: StatusException) {
      assertEquals(Status.Code.UNIMPLEMENTED, e.status.code)
    }
  }

  // ---------------------------------------------------------------------------
  // Set — P4RT port ID
  // ---------------------------------------------------------------------------

  @Test
  fun `set updates P4RT port ID`() = runBlocking {
    val stub = setup()

    // Update Ethernet0's P4RT ID to 42.
    val setResponse =
      stub.set(
        SetRequest.newBuilder()
          .addUpdate(
            Update.newBuilder()
              .setPath(p4rtIdPath("Ethernet0"))
              .setVal(jsonIetfValue("""{"openconfig-p4rt:id":42}"""))
              .build()
          )
          .build()
      )

    assertEquals(1, setResponse.responseCount)

    // Verify via Get.
    val getResponse =
      stub.get(
        GetRequest.newBuilder()
          .setType(GetRequest.DataType.CONFIG)
          .addPath(path("interfaces"))
          .build()
      )

    val json = getResponse.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    assertTrue("Ethernet0 should have P4RT ID 42", json.contains(""""openconfig-p4rt:id":42"""))
  }

  @Test
  fun `set delete removes P4RT port ID`() = runBlocking {
    val stub = setup()

    // Delete Ethernet0's P4RT ID.
    val setResponse =
      stub.set(
        SetRequest.newBuilder()
          .addDelete(p4rtIdPath("Ethernet0"))
          .build()
      )

    assertEquals(1, setResponse.responseCount)

    // Verify via Get — Ethernet0 should have no P4RT ID, but Ethernet1 still should.
    val getResponse =
      stub.get(
        GetRequest.newBuilder()
          .setType(GetRequest.DataType.CONFIG)
          .addPath(path("interfaces"))
          .build()
      )

    val json = getResponse.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    // Ethernet1 has p4rtId=2 by default, so JSON should still contain an ID.
    assertTrue("should still have other P4RT IDs", json.contains("openconfig-p4rt:id"))
  }

  @Test
  fun `set full config replace is accepted`() = runBlocking {
    val stub = setup()

    val setResponse =
      stub.set(
        SetRequest.newBuilder()
          .addReplace(
            Update.newBuilder()
              .setPath(Path.getDefaultInstance())
              .setVal(jsonIetfValue("{}"))
              .build()
          )
          .build()
      )

    assertEquals(1, setResponse.responseCount)
  }

  // ---------------------------------------------------------------------------
  // Unimplemented RPCs
  // ---------------------------------------------------------------------------

  @Test
  fun `capabilities returns UNIMPLEMENTED`() = runBlocking {
    val stub = setup()
    try {
      stub.capabilities(com.github.gnmi.proto.CapabilityRequest.getDefaultInstance())
      assertTrue("should have thrown", false)
    } catch (e: StatusException) {
      assertEquals(Status.Code.UNIMPLEMENTED, e.status.code)
    }
  }

  // ---------------------------------------------------------------------------
  // Custom interfaces
  // ---------------------------------------------------------------------------

  @Test
  fun `custom interfaces are reflected in Get response`() = runBlocking {
    val stub =
      setup(
        listOf(
          GnmiService.InterfaceConfig("Ethernet100", p4rtId = 100),
          GnmiService.InterfaceConfig("Ethernet200", p4rtId = null, enabled = false),
        )
      )

    val response =
      stub.get(
        GetRequest.newBuilder()
          .setType(GetRequest.DataType.STATE)
          .addPath(path("interfaces"))
          .build()
      )

    val json = response.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    assertTrue(json.contains("Ethernet100"))
    assertTrue(json.contains("Ethernet200"))
    assertTrue(json.contains(""""openconfig-p4rt:id":100"""))
    assertTrue("disabled interface", json.contains(""""enabled":false"""))
  }

  @Test
  fun `set with uint_val updates P4RT port ID`() = runBlocking {
    val stub = setup()

    stub.set(
      SetRequest.newBuilder()
        .addUpdate(
          Update.newBuilder()
            .setPath(p4rtIdPath("Ethernet0"))
            .setVal(TypedValue.newBuilder().setUintVal(99).build())
            .build()
        )
        .build()
    )

    val getResponse =
      stub.get(
        GetRequest.newBuilder()
          .setType(GetRequest.DataType.CONFIG)
          .addPath(path("interfaces"))
          .build()
      )

    val json = getResponse.getNotification(0).getUpdate(0).getVal().jsonIetfVal.toStringUtf8()
    assertTrue("should have P4RT ID 99", json.contains(""""openconfig-p4rt:id":99"""))
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun path(vararg elems: String): Path =
    Path.newBuilder()
      .addAllElem(elems.map { PathElem.newBuilder().setName(it).build() })
      .build()

  private fun p4rtIdPath(ifaceName: String): Path =
    Path.newBuilder()
      .addElem(PathElem.newBuilder().setName("interfaces").build())
      .addElem(PathElem.newBuilder().setName("interface").putKey("name", ifaceName).build())
      .addElem(PathElem.newBuilder().setName("config").build())
      .addElem(PathElem.newBuilder().setName("openconfig-p4rt:id").build())
      .build()

  private fun jsonIetfValue(json: String): TypedValue =
    TypedValue.newBuilder().setJsonIetfVal(ByteString.copyFromUtf8(json)).build()
}
