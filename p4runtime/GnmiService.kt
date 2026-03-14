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

package fourward.p4runtime

import com.github.gnmi.proto.CapabilityRequest
import com.github.gnmi.proto.CapabilityResponse
import com.github.gnmi.proto.GetRequest
import com.github.gnmi.proto.GetResponse
import com.github.gnmi.proto.Notification
import com.github.gnmi.proto.Path
import com.github.gnmi.proto.SetRequest
import com.github.gnmi.proto.SetResponse
import com.github.gnmi.proto.SubscribeRequest
import com.github.gnmi.proto.SubscribeResponse
import com.github.gnmi.proto.TypedValue
import com.github.gnmi.proto.Update
import com.github.gnmi.proto.UpdateResult
import com.github.gnmi.proto.gNMIGrpcKt
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow

/**
 * Minimal gNMI stub server for upstream sonic-pins DVaaS mirror testbed support.
 *
 * Models a configurable set of Ethernet interfaces, each with a P4RT port ID, enabled state, and
 * operational status. DVaaS uses gNMI to discover switch ports and map them between the SUT and
 * control switch.
 *
 * **Supported RPCs:**
 * - `Get`: returns interface config or state as JSON_IETF.
 * - `Set`: modifies P4RT port ID assignments.
 * - `Capabilities` / `Subscribe`: return UNIMPLEMENTED.
 *
 * **Supported OpenConfig paths:**
 * - `interfaces` (config/state) — full interface list.
 * - `interfaces/interface[name=X]/config/openconfig-p4rt:id` — per-interface P4RT port ID.
 * - Empty path with CONFIG type — full device config.
 *
 * @param interfaces Initial interface configurations. If empty, a default set of 8 Ethernet
 *   interfaces is created.
 */
class GnmiService(interfaces: List<InterfaceConfig> = defaultInterfaces()) :
  gNMIGrpcKt.gNMICoroutineImplBase() {

  /** Mutable interface state. Thread-safe: gRPC serializes calls. */
  private val interfaces = interfaces.map { it.copy() }.associateBy { it.name }.toMutableMap()

  /** Configuration for a single Ethernet interface. */
  data class InterfaceConfig(
    val name: String,
    val p4rtId: Int? = null,
    val enabled: Boolean = true,
    val operStatus: String = "UP",
  )

  // ---------------------------------------------------------------------------
  // gNMI Get
  // ---------------------------------------------------------------------------

  override suspend fun get(request: GetRequest): GetResponse {
    val dataType = request.type
    val path = request.pathList.firstOrNull() ?: Path.getDefaultInstance()
    val pathStr = pathToString(path)

    val json =
      when {
        // Full device config (empty path)
        pathStr.isEmpty() && dataType == GetRequest.DataType.CONFIG -> buildFullConfig()
        // Interfaces config or state
        pathStr == "interfaces" || pathStr.isEmpty() ->
          when (dataType) {
            GetRequest.DataType.CONFIG -> buildInterfacesConfigJson()
            GetRequest.DataType.STATE -> buildInterfacesStateJson()
            else -> buildInterfacesStateJson()
          }
        else ->
          throw StatusException(
            Status.UNIMPLEMENTED.withDescription("unsupported gNMI path: $pathStr")
          )
      }

    val update =
      Update.newBuilder()
        .setPath(path)
        .setVal(TypedValue.newBuilder().setJsonIetfVal(ByteString.copyFromUtf8(json)))
        .build()

    val notification = Notification.newBuilder().addUpdate(update).build()

    return GetResponse.newBuilder().addNotification(notification).build()
  }

  // ---------------------------------------------------------------------------
  // gNMI Set
  // ---------------------------------------------------------------------------

  override suspend fun set(request: SetRequest): SetResponse {
    val results = mutableListOf<UpdateResult>()

    // Handle deletes (remove P4RT port ID assignments).
    for (delete in request.deleteList) {
      val pathStr = pathToString(delete)
      val ifaceName = extractInterfaceName(delete)
      if (ifaceName != null && pathStr.endsWith("openconfig-p4rt:id")) {
        interfaces[ifaceName]?.let { interfaces[ifaceName] = it.copy(p4rtId = null) }
        results.add(
          UpdateResult.newBuilder().setPath(delete).setOp(UpdateResult.Operation.DELETE).build()
        )
      }
    }

    // Handle updates (set P4RT port ID assignments).
    for (update in request.updateList + request.replaceList) {
      val pathStr = pathToString(update.path)
      val ifaceName = extractInterfaceName(update.path)
      if (ifaceName != null && pathStr.endsWith("openconfig-p4rt:id")) {
        val portId = extractP4rtIdFromJson(update.getVal())
        if (portId != null) {
          interfaces[ifaceName]?.let { interfaces[ifaceName] = it.copy(p4rtId = portId) }
        }
        results.add(
          UpdateResult.newBuilder()
            .setPath(update.path)
            .setOp(UpdateResult.Operation.UPDATE)
            .build()
        )
      } else if (pathStr.isEmpty()) {
        // Full config replace — accept but ignore (DVaaS uses this for config push).
        results.add(
          UpdateResult.newBuilder()
            .setPath(update.path)
            .setOp(UpdateResult.Operation.REPLACE)
            .build()
        )
      }
    }

    return SetResponse.newBuilder().addAllResponse(results).build()
  }

  // ---------------------------------------------------------------------------
  // Unimplemented RPCs
  // ---------------------------------------------------------------------------

  override suspend fun capabilities(request: CapabilityRequest): CapabilityResponse {
    throw StatusException(Status.UNIMPLEMENTED.withDescription("gNMI Capabilities not supported"))
  }

  override fun subscribe(requests: Flow<SubscribeRequest>): Flow<SubscribeResponse> {
    throw StatusException(Status.UNIMPLEMENTED.withDescription("gNMI Subscribe not supported"))
  }

  // ---------------------------------------------------------------------------
  // JSON builders (OpenConfig YANG-aligned format)
  // ---------------------------------------------------------------------------

  /** Builds the full interfaces config JSON in OpenConfig format. */
  private fun buildInterfacesConfigJson(): String {
    val ifaceEntries =
      interfaces.values
        .sortedBy { it.name }
        .joinToString(",\n") { iface ->
          val idField = iface.p4rtId?.let { ""","openconfig-p4rt:id":$it""" } ?: ""
          """{
        "name":"${iface.name}",
        "config":{
          "name":"${iface.name}",
          "type":"iana-if-type:ethernetCsmacd",
          "enabled":${iface.enabled}$idField
        }
      }"""
        }
    return """{"openconfig-interfaces:interfaces":{"interface":[$ifaceEntries]}}"""
  }

  /** Builds the full interfaces state JSON in OpenConfig format. */
  private fun buildInterfacesStateJson(): String {
    val ifaceEntries =
      interfaces.values
        .sortedBy { it.name }
        .joinToString(",\n") { iface ->
          val idField = iface.p4rtId?.let { ""","openconfig-p4rt:id":$it""" } ?: ""
          """{
        "name":"${iface.name}",
        "state":{
          "name":"${iface.name}",
          "type":"iana-if-type:ethernetCsmacd",
          "enabled":${iface.enabled},
          "oper-status":"${iface.operStatus}"$idField
        }
      }"""
        }
    return """{"openconfig-interfaces:interfaces":{"interface":[$ifaceEntries]}}"""
  }

  /** Builds a full device config (wraps interfaces config). */
  private fun buildFullConfig(): String = buildInterfacesConfigJson()

  // ---------------------------------------------------------------------------
  // Path helpers
  // ---------------------------------------------------------------------------

  /** Converts a gNMI Path to a string like "interfaces/interface[name=Ethernet0]/config". */
  private fun pathToString(path: Path): String =
    path.elemList.joinToString("/") { elem ->
      if (elem.keyMap.isEmpty()) {
        elem.name
      } else {
        val keys = elem.keyMap.entries.joinToString(",") { (k, v) -> "$k=$v" }
        "${elem.name}[$keys]"
      }
    }

  /** Extracts the interface name from a path like interfaces/interface[name=X]/... */
  private fun extractInterfaceName(path: Path): String? =
    path.elemList.find { it.name == "interface" }?.keyMap?.get("name")

  /** Extracts the P4RT port ID from a JSON_IETF value like {"openconfig-p4rt:id":42}. */
  @Suppress("MagicNumber")
  private fun extractP4rtIdFromJson(value: TypedValue): Int? {
    if (value.hasJsonIetfVal()) {
      val json = value.jsonIetfVal.toStringUtf8()
      val match = Regex(""""openconfig-p4rt:id"\s*:\s*(\d+)""").find(json)
      return match?.groupValues?.get(1)?.toIntOrNull()
    }
    if (value.hasUintVal()) return value.uintVal.toInt()
    if (value.hasIntVal()) return value.intVal.toInt()
    return null
  }

  companion object {
    /** Creates a default set of 8 Ethernet interfaces with sequential P4RT port IDs. */
    @Suppress("MagicNumber")
    fun defaultInterfaces(): List<InterfaceConfig> =
      (0 until 8).map { i -> InterfaceConfig(name = "Ethernet$i", p4rtId = i + 1) }
  }
}
