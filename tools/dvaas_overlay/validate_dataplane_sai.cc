#include <array>
#include <cstdint>
#include <fstream>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "absl/flags/parse.h"
#include "absl/log/check.h"
#include "absl/log/log.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_format.h"
#include "dvaas/dataplane_validation.h"
#include "dvaas/switch_api.h"
#include "dvaas/test_vector.pb.h"
#include "proto/gnmi/gnmi.grpc.pb.h"
#include "grpcpp/grpcpp.h"
#include "gutil/gutil/status.h"
#include "gutil/gutil/testing.h"
#include "lib/gnmi/gnmi_helper.h"
#include "lib/gnmi/openconfig.pb.h"
#include "p4/config/v1/p4info.pb.h"
#include "p4/v1/p4runtime.pb.h"
#include "p4_infra/p4_pdpi/ir.h"
#include "p4_infra/p4_pdpi/ir.pb.h"
#include "p4_infra/p4_pdpi/p4_runtime_session.h"
#include "p4_infra/p4_pdpi/p4_runtime_session_extras.h"
#include "p4_infra/p4_pdpi/packetlib/packetlib.h"
#include "p4_infra/p4_pdpi/packetlib/packetlib.pb.h"
#include "thinkit/test_environment.h"

namespace {

constexpr int kDeviceId = 1;
constexpr char kIngressInterfaceName[] = "Ethernet1";
constexpr char kEgressInterfaceName[] = "Ethernet2";
constexpr char kIngressPortId[] = "1";
constexpr char kEgressPortId[] = "1";
constexpr uint8_t kIpv4Ttl = 64;

constexpr std::array<uint8_t, 6> kIngressDstMac = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
constexpr std::array<uint8_t, 6> kIngressSrcMac = {0x00, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E};
constexpr std::array<uint8_t, 6> kRifSrcMac = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55};
constexpr std::array<uint8_t, 6> kNeighborDstMac = {0x00, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE};
constexpr std::array<uint8_t, 4> kSrcIp = {192, 168, 1, 1};
constexpr std::array<uint8_t, 4> kDstIp = {10, 0, 0, 1};
constexpr std::array<uint8_t, 4> kDstPrefix = {10, 0, 0, 0};
constexpr std::array<uint8_t, 16> kNeighborId = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

class MemoryTestEnvironment final : public thinkit::TestEnvironment {
 public:
  explicit MemoryTestEnvironment(std::string artifact_dir)
      : artifact_dir_(std::move(artifact_dir)) {}

  absl::Status StoreTestArtifact(absl::string_view filename,
                                 absl::string_view contents) override {
    return WriteFile(filename, contents, /*append=*/false);
  }

  absl::Status AppendToTestArtifact(absl::string_view filename,
                                    absl::string_view contents) override {
    return WriteFile(filename, contents, /*append=*/true);
  }

  bool MaskKnownFailures() override { return false; }

 private:
  absl::Status WriteFile(absl::string_view filename, absl::string_view contents,
                         bool append) {
    const std::string path = absl::StrCat(artifact_dir_, "/", filename);
    std::ofstream stream(path, append ? std::ios::app : std::ios::trunc);
    if (!stream.is_open()) {
      return absl::InternalError(absl::StrCat("failed to open artifact ", path));
    }
    stream << contents;
    return absl::OkStatus();
  }

  std::string artifact_dir_;
};

class FakeGnmiService final : public gnmi::gNMI::Service {
 public:
  explicit FakeGnmiService(std::string interfaces_json)
      : interfaces_json_(std::move(interfaces_json)) {}

  grpc::Status Get(grpc::ServerContext*,
                   const gnmi::GetRequest*,
                   gnmi::GetResponse* response) override {
    auto* notification = response->add_notification();
    auto* update = notification->add_update();
    update->mutable_val()->set_json_ietf_val(interfaces_json_);
    return grpc::Status::OK;
  }

 private:
  std::string interfaces_json_;
};

class FakeGnmiEndpoint {
 public:
  explicit FakeGnmiEndpoint(std::string interfaces_json)
      : service_(std::move(interfaces_json)) {
    grpc::ServerBuilder builder;
    builder.AddListeningPort("localhost:0", grpc::InsecureServerCredentials(),
                             &port_);
    builder.RegisterService(&service_);
    server_ = builder.BuildAndStart();
    CHECK(server_ != nullptr);
  }

  std::unique_ptr<gnmi::gNMI::StubInterface> CreateStub() const {
    return gnmi::gNMI::NewStub(grpc::CreateChannel(
        absl::StrFormat("localhost:%d", port_),
        grpc::InsecureChannelCredentials()));
  }

 private:
  FakeGnmiService service_;
  int port_ = 0;
  std::unique_ptr<grpc::Server> server_;
};

class FourwardSaiDvaasBackend final : public dvaas::DataplaneValidationBackend {
 public:
  absl::StatusOr<dvaas::PacketSynthesisResult> SynthesizePackets(
      const pdpi::IrP4Info&, const pdpi::IrEntities&,
      const p4::v1::ForwardingPipelineConfig&,
      absl::Span<const pins_test::P4rtPortId>,
      const dvaas::OutputWriterFunctionType&,
      const std::optional<p4_symbolic::packet_synthesizer::CoverageGoals>&,
      std::optional<absl::Duration>) const override {
    return absl::UnimplementedError("SAI PoC uses packet_test_vector_override");
  }

  absl::StatusOr<dvaas::PacketTestVectorById> GeneratePacketTestVectors(
      const pdpi::IrP4Info&, const pdpi::IrEntities&,
      const p4::v1::ForwardingPipelineConfig&,
      absl::Span<const pins_test::P4rtPortId>,
      std::vector<p4_symbolic::packet_synthesizer::SynthesizedPacket>&,
      const pins_test::P4rtPortId&, bool) const override {
    return absl::UnimplementedError("SAI PoC uses packet_test_vector_override");
  }

  absl::StatusOr<pdpi::IrEntities> GetEntitiesToPuntAllPackets(
      const pdpi::IrP4Info&) const override {
    return gutil::ParseProtoOrDie<pdpi::IrEntities>(R"pb(
      entities {
        packet_replication_engine_entry {
          clone_session_entry {
            session_id: 255
            replicas { port: "CPU" instance: 1 }
          }
        }
      }
      entities {
        table_entry {
          table_name: "ingress_clone_table"
          matches { name: "marked_to_copy" exact { hex_str: "0x1" } }
          matches { name: "marked_to_mirror" exact { hex_str: "0x0" } }
          priority: 1
          action {
            name: "ingress_clone"
            params { name: "clone_session" value { hex_str: "0x000000ff" } }
          }
        }
      }
      entities {
        table_entry {
          table_name: "acl_ingress_table"
          priority: 1
          action {
            name: "acl_trap"
            params { name: "qos_queue" value { str: "queue-1" } }
          }
        }
      }
    )pb");
  }

  absl::StatusOr<dvaas::P4Specification> InferP4Specification(
      dvaas::SwitchApi&) const override {
    return absl::UnimplementedError("SAI PoC passes specification_override");
  }

  absl::Status AugmentPacketTestVectorsWithPacketTraces(
      std::vector<dvaas::PacketTestVector>&, const pdpi::IrP4Info&,
      const pdpi::IrEntities&, const p4::v1::ForwardingPipelineConfig&,
      bool) const override {
    return absl::OkStatus();
  }

  absl::StatusOr<pdpi::IrEntities> CreateV1ModelAuxiliaryEntities(
      const pdpi::IrEntities&, const pdpi::IrP4Info&,
      gnmi::gNMI::StubInterface&) const override {
    return pdpi::IrEntities();
  }
};

absl::StatusOr<p4::v1::ForwardingPipelineConfig> GetPipelineConfig(
    pdpi::P4RuntimeSession& session) {
  p4::v1::GetForwardingPipelineConfigRequest request;
  request.set_device_id(kDeviceId);
  request.set_response_type(p4::v1::GetForwardingPipelineConfigRequest::ALL);
  ASSIGN_OR_RETURN(auto response, session.GetForwardingPipelineConfig(request));
  return response.config();
}

const p4::config::v1::Table& FindTable(const p4::config::v1::P4Info& p4info,
                                       absl::string_view alias) {
  for (const auto& table : p4info.tables()) {
    if (table.preamble().alias() == alias) return table;
  }
  LOG(FATAL) << "table not found: " << alias;
}

const p4::config::v1::Action& FindAction(const p4::config::v1::P4Info& p4info,
                                         absl::string_view alias) {
  for (const auto& action : p4info.actions()) {
    if (action.preamble().alias() == alias) return action;
  }
  LOG(FATAL) << "action not found: " << alias;
}

int MatchFieldId(const p4::config::v1::Table& table, absl::string_view name) {
  for (const auto& field : table.match_fields()) {
    if (field.name() == name) return field.id();
  }
  LOG(FATAL) << "match field not found: " << name;
}

int ParamId(const p4::config::v1::Action& action, absl::string_view name) {
  for (const auto& param : action.params()) {
    if (param.name() == name) return param.id();
  }
  LOG(FATAL) << "action param not found: " << name;
}

std::string CanonicalP4RuntimeBytes(absl::string_view value) {
  size_t first_non_zero = 0;
  while (first_non_zero < value.size() && value[first_non_zero] == '\0') {
    ++first_non_zero;
  }
  if (first_non_zero == value.size()) {
    return std::string(1, '\0');
  }
  return std::string(value.substr(first_non_zero));
}

p4::v1::FieldMatch ExactMatch(const p4::config::v1::Table& table,
                              absl::string_view field_name,
                              const std::string& value) {
  p4::v1::FieldMatch match;
  match.set_field_id(MatchFieldId(table, field_name));
  match.mutable_exact()->set_value(value);
  return match;
}

p4::v1::FieldMatch ExactMatch(const p4::config::v1::Table& table,
                              absl::string_view field_name,
                              const std::array<uint8_t, 16>& value) {
  p4::v1::FieldMatch match;
  match.set_field_id(MatchFieldId(table, field_name));
  match.mutable_exact()->set_value(CanonicalP4RuntimeBytes(std::string_view(
      reinterpret_cast<const char*>(value.data()), value.size())));
  return match;
}

p4::v1::FieldMatch LpmMatch(const p4::config::v1::Table& table,
                            absl::string_view field_name,
                            const std::array<uint8_t, 4>& value,
                            int prefix_length) {
  p4::v1::FieldMatch match;
  match.set_field_id(MatchFieldId(table, field_name));
  match.mutable_lpm()->set_value(
      std::string(reinterpret_cast<const char*>(value.data()), value.size()));
  match.mutable_lpm()->set_prefix_len(prefix_length);
  return match;
}

p4::v1::Action::Param StringParam(const p4::config::v1::Action& action,
                                  absl::string_view param_name,
                                  absl::string_view value) {
  p4::v1::Action::Param param;
  param.set_param_id(ParamId(action, param_name));
  param.set_value(std::string(value));
  return param;
}

p4::v1::Action::Param BytesParam(const p4::config::v1::Action& action,
                                 absl::string_view param_name,
                                 const std::string& value) {
  p4::v1::Action::Param param;
  param.set_param_id(ParamId(action, param_name));
  param.set_value(CanonicalP4RuntimeBytes(value));
  return param;
}

p4::v1::Entity BuildTableEntry(
    const p4::config::v1::Table& table, const p4::config::v1::Action& action,
    std::vector<p4::v1::FieldMatch> matches,
    std::vector<p4::v1::Action::Param> params) {
  p4::v1::Entity entity;
  auto* table_entry = entity.mutable_table_entry();
  table_entry->set_table_id(table.preamble().id());
  for (auto& match : matches) *table_entry->add_match() = std::move(match);
  auto* pi_action = table_entry->mutable_action()->mutable_action();
  pi_action->set_action_id(action.preamble().id());
  for (auto& param : params) *pi_action->add_params() = std::move(param);
  return entity;
}

std::vector<p4::v1::Entity> BuildRoutingEntities(
    const p4::config::v1::P4Info& p4info) {
  const auto& ipv4_table = FindTable(p4info, "ipv4_table");
  const auto& nexthop_table = FindTable(p4info, "nexthop_table");
  const auto& router_interface_table = FindTable(p4info, "router_interface_table");
  const auto& neighbor_table = FindTable(p4info, "neighbor_table");
  const auto& set_nexthop_id = FindAction(p4info, "set_nexthop_id");
  const auto& set_ip_nexthop = FindAction(p4info, "set_ip_nexthop");
  const auto& set_port_and_src_mac = FindAction(p4info, "set_port_and_src_mac");
  const auto& set_dst_mac = FindAction(p4info, "set_dst_mac");

  return {
      BuildTableEntry(
          ipv4_table, set_nexthop_id,
          {ExactMatch(ipv4_table, "vrf_id", std::string()),
           LpmMatch(ipv4_table, "ipv4_dst", kDstPrefix, /*prefix_length=*/8)},
          {StringParam(set_nexthop_id, "nexthop_id", "nhop-1")}),
      BuildTableEntry(
          nexthop_table, set_ip_nexthop,
          {ExactMatch(nexthop_table, "nexthop_id", std::string("nhop-1"))},
          {StringParam(set_ip_nexthop, "router_interface_id", "rif-1"),
           BytesParam(set_ip_nexthop, "neighbor_id",
                      std::string(reinterpret_cast<const char*>(kNeighborId.data()),
                                  kNeighborId.size()))}),
      BuildTableEntry(
          router_interface_table, set_port_and_src_mac,
          {ExactMatch(router_interface_table, "router_interface_id",
                      std::string("rif-1"))},
          {StringParam(set_port_and_src_mac, "port", "Ethernet1"),
           BytesParam(set_port_and_src_mac, "src_mac",
                      std::string(reinterpret_cast<const char*>(kRifSrcMac.data()),
                                  kRifSrcMac.size()))}),
      BuildTableEntry(
          neighbor_table, set_dst_mac,
          {ExactMatch(neighbor_table, "router_interface_id", std::string("rif-1")),
           ExactMatch(neighbor_table, "neighbor_id", kNeighborId)},
          {BytesParam(set_dst_mac, "dst_mac",
                      std::string(reinterpret_cast<const char*>(kNeighborDstMac.data()),
                                  kNeighborDstMac.size()))}),
  };
}

uint16_t ComputeIpv4Checksum(const std::array<uint8_t, 20>& header) {
  uint32_t sum = 0;
  for (int i = 0; i < 20; i += 2) {
    if (i == 10) continue;
    sum += (static_cast<uint16_t>(header[i]) << 8) | header[i + 1];
  }
  while ((sum >> 16) != 0) {
    sum = (sum & 0xFFFF) + (sum >> 16);
  }
  return static_cast<uint16_t>(~sum);
}

std::string BuildIpv4TcpFrame(const std::array<uint8_t, 6>& dst_mac,
                              const std::array<uint8_t, 6>& src_mac, uint8_t ttl) {
  std::array<uint8_t, 20> ipv4 = {
      0x45, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00, 0x00, ttl, 0x06,
      0x00, 0x00, kSrcIp[0], kSrcIp[1], kSrcIp[2], kSrcIp[3],
      kDstIp[0], kDstIp[1], kDstIp[2], kDstIp[3],
  };
  const std::array<uint8_t, 20> tcp = {
      0x04, 0xd2, 0x00, 0x50, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
      0x00, 0x00, 0x50, 0x02, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00,
  };
  const uint16_t checksum = ComputeIpv4Checksum(ipv4);
  ipv4[10] = static_cast<uint8_t>(checksum >> 8);
  ipv4[11] = static_cast<uint8_t>(checksum & 0xFF);

  std::string packet;
  packet.reserve(14 + ipv4.size());
  packet.append(reinterpret_cast<const char*>(dst_mac.data()), dst_mac.size());
  packet.append(reinterpret_cast<const char*>(src_mac.data()), src_mac.size());
  packet.push_back(static_cast<char>(0x08));
  packet.push_back(static_cast<char>(0x00));
  packet.append(reinterpret_cast<const char*>(ipv4.data()), ipv4.size());
  packet.append(reinterpret_cast<const char*>(tcp.data()), tcp.size());
  return packet;
}

absl::StatusOr<packetlib::Packet> BuildInputPacket() {
  packetlib::Packet packet =
      packetlib::ParsePacket(BuildIpv4TcpFrame(kIngressDstMac, kIngressSrcMac, kIpv4Ttl));
  packet.set_payload("test packet #1: 4ward DVaaS SAI");
  RETURN_IF_ERROR(packetlib::UpdateAllComputedFields(packet).status());
  return packet;
}

absl::StatusOr<packetlib::Packet> BuildExpectedOutputPacket() {
  packetlib::Packet packet =
      packetlib::ParsePacket(BuildIpv4TcpFrame(kNeighborDstMac, kRifSrcMac, kIpv4Ttl - 1));
  packet.set_payload("test packet #1: 4ward DVaaS SAI");
  RETURN_IF_ERROR(packetlib::UpdateAllComputedFields(packet).status());
  return packet;
}

dvaas::PacketTestVector BuildPacketTestVector(const packetlib::Packet& input_packet,
                                              const packetlib::Packet& expected_packet) {
  dvaas::PacketTestVector vector;
  vector.mutable_input()->set_type(dvaas::SwitchInput::DATAPLANE);
  vector.mutable_input()->mutable_packet()->set_port(kIngressPortId);
  *vector.mutable_input()->mutable_packet()->mutable_parsed() = input_packet;

  auto* output = vector.add_acceptable_outputs()->add_packets();
  output->set_port(kEgressPortId);
  *output->mutable_parsed() = expected_packet;
  return vector;
}

std::string BuildInterfacesJson() {
  return absl::StrFormat(
      R"json(
{
  "openconfig-interfaces:interfaces": {
    "interface": [
      {
        "name": "%s",
        "config": {
          "enabled": true,
          "openconfig-p4rt:id": 1
        },
        "state": {
          "enabled": true,
          "ifname": "%s",
          "openconfig-p4rt:id": 1,
          "oper-status": "UP",
          "type": "iana-if-type:ethernetCsmacd"
        }
      },
      {
        "name": "%s",
        "config": {
          "enabled": true,
          "openconfig-p4rt:id": 2
        },
        "state": {
          "enabled": true,
          "ifname": "%s",
          "openconfig-p4rt:id": 2,
          "oper-status": "UP",
          "type": "iana-if-type:ethernetCsmacd"
        }
      }
    ]
  }
}
)json",
      kIngressInterfaceName, kIngressInterfaceName, kEgressInterfaceName,
      kEgressInterfaceName);
}

absl::StatusOr<dvaas::SwitchApi> ConnectSwitch(absl::string_view address,
                                               FakeGnmiEndpoint& gnmi) {
  ASSIGN_OR_RETURN(
      auto session,
      pdpi::P4RuntimeSession::Create(std::string(address),
                                     grpc::InsecureChannelCredentials(),
                                     kDeviceId));
  return dvaas::SwitchApi{
      .p4rt = std::move(session),
      .gnmi = gnmi.CreateStub(),
  };
}

absl::Status Run(absl::string_view sut_address, absl::string_view control_address,
                 absl::string_view artifact_dir) {
  LOG(INFO) << "Starting DVaaS SAI helper";
  FakeGnmiEndpoint sut_gnmi(BuildInterfacesJson());
  FakeGnmiEndpoint control_gnmi(BuildInterfacesJson());

  LOG(INFO) << "Connecting to SUT P4Runtime at " << sut_address;
  ASSIGN_OR_RETURN(auto sut, ConnectSwitch(sut_address, sut_gnmi));
  LOG(INFO) << "Connecting to control-switch P4Runtime at " << control_address;
  ASSIGN_OR_RETURN(auto control, ConnectSwitch(control_address, control_gnmi));

  LOG(INFO) << "Fetching SUT forwarding pipeline config";
  ASSIGN_OR_RETURN(const auto pipeline_config, GetPipelineConfig(*sut.p4rt));
  LOG(INFO) << "Installing SUT routing entities";
  RETURN_IF_ERROR(pdpi::InstallPiEntities(*sut.p4rt,
                                          BuildRoutingEntities(pipeline_config.p4info())));
  LOG(INFO) << "Building SAI packet and expected output";
  ASSIGN_OR_RETURN(const auto input_packet, BuildInputPacket());
  ASSIGN_OR_RETURN(const auto expected_output, BuildExpectedOutputPacket());

  dvaas::DataplaneValidationParams params;
  params.artifact_prefix = "fourward_sai";
  params.reset_and_collect_counters = false;
  params.specification_override = dvaas::P4Specification{
      .p4_symbolic_config = pipeline_config,
      .bmv2_config = pipeline_config,
  };
  params.packet_test_vector_override = {
      BuildPacketTestVector(input_packet, expected_output)};

  auto match_all_interfaces =
      [](const pins_test::openconfig::Interfaces::Interface&) { return true; };
  LOG(INFO) << "Preflight: reading SUT gNMI P4RT ports";
  ASSIGN_OR_RETURN(const auto sut_ports,
                   pins_test::GetMatchingP4rtPortIds(*sut.gnmi,
                                                     match_all_interfaces));
  LOG(INFO) << "Preflight: reading control-switch gNMI P4RT ports";
  ASSIGN_OR_RETURN(const auto control_ports,
                   pins_test::GetMatchingP4rtPortIds(*control.gnmi,
                                                     match_all_interfaces));
  LOG(INFO) << "Preflight: SUT ports=" << sut_ports.size()
            << ", control ports=" << control_ports.size();

  auto validator =
      dvaas::DataplaneValidator(std::make_unique<FourwardSaiDvaasBackend>());
  MemoryTestEnvironment environment{std::string(artifact_dir)};
  LOG(INFO) << "Calling ValidateDataplaneUsingExistingSwitchApis";
  ASSIGN_OR_RETURN(auto result,
                   validator.ValidateDataplaneUsingExistingSwitchApis(
                       sut, control, environment, params));

  LOG(INFO) << "Checking validation success rate";
  RETURN_IF_ERROR(result.HasSuccessRateOfAtLeast(1.0));
  LOG(INFO) << "DVaaS ValidateDataplaneUsingExistingSwitchApis passed";
  LOG(INFO) << "Success rate: " << result.GetSuccessRate();
  return absl::OkStatus();
}

}  // namespace

int main(int argc, char** argv) {
  std::vector<char*> positional = absl::ParseCommandLine(argc, argv);
  CHECK_EQ(positional.size(), 4)
      << "usage: validate_dataplane_sai <sut_host:port> <control_host:port> "
         "<artifact_dir>";
  const absl::Status status = Run(positional[1], positional[2], positional[3]);
  if (!status.ok()) {
    LOG(ERROR) << status;
    return 1;
  }
  return 0;
}
