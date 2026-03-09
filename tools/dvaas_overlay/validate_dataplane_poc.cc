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
#include "absl/strings/escaping.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_format.h"
#include "dvaas/dataplane_validation.h"
#include "dvaas/switch_api.h"
#include "dvaas/test_vector.pb.h"
#include "gnmi/gnmi.grpc.pb.h"
#include "grpcpp/grpcpp.h"
#include "gutil/gutil/status.h"
#include "gutil/gutil/testing.h"
#include "p4/v1/p4runtime.pb.h"
#include "p4_infra/p4_pdpi/ir.h"
#include "p4_infra/p4_pdpi/ir.pb.h"
#include "p4_infra/p4_pdpi/p4_runtime_session.h"
#include "p4_infra/p4_pdpi/packetlib/packetlib.h"
#include "p4_infra/p4_pdpi/packetlib/packetlib.pb.h"
#include "thinkit/test_environment.h"

namespace {

constexpr int kDeviceId = 1;
constexpr char kCpuPortName[] = "CPU";
constexpr char kIngressPortName[] = "Ethernet1";
constexpr char kEgressPortName[] = "Ethernet2";
constexpr char kMatchedDstMac[] = "02:00:00:00:00:02";
constexpr char kSrcMac[] = "00:00:00:00:00:01";

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

class FourwardDvaasBackend final : public dvaas::DataplaneValidationBackend {
 public:
  absl::StatusOr<dvaas::PacketSynthesisResult> SynthesizePackets(
      const pdpi::IrP4Info&, const pdpi::IrEntities&,
      const p4::v1::ForwardingPipelineConfig&,
      absl::Span<const pins_test::P4rtPortId>,
      const dvaas::OutputWriterFunctionType&,
      const std::optional<p4_symbolic::packet_synthesizer::CoverageGoals>&,
      std::optional<absl::Duration>) const override {
    return absl::UnimplementedError("PoC uses packet_test_vector_override");
  }

  absl::StatusOr<dvaas::PacketTestVectorById> GeneratePacketTestVectors(
      const pdpi::IrP4Info&, const pdpi::IrEntities&,
      const p4::v1::ForwardingPipelineConfig&,
      absl::Span<const pins_test::P4rtPortId>,
      std::vector<p4_symbolic::packet_synthesizer::SynthesizedPacket>&,
      const pins_test::P4rtPortId&, bool) const override {
    return absl::UnimplementedError("PoC uses packet_test_vector_override");
  }

  absl::StatusOr<pdpi::IrEntities> GetEntitiesToPuntAllPackets(
      const pdpi::IrP4Info&) const override {
    return gutil::ParseProtoOrDie<pdpi::IrEntities>(R"pb(
      entities {
        table_entry {
          table_name: "punt_all"
          is_default_action: true
          action { name: "punt_to_controller" }
        }
      }
    )pb");
  }

  absl::StatusOr<dvaas::P4Specification> InferP4Specification(
      dvaas::SwitchApi&) const override {
    return absl::UnimplementedError("PoC passes specification_override");
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

pdpi::IrEntities BuildForwardingEntities() {
  return gutil::ParseProtoOrDie<pdpi::IrEntities>(R"pb(
    entities {
      table_entry {
        table_name: "fwd_table"
        matches {
          name: "dst_addr"
          exact { value { mac: "02:00:00:00:00:02" } }
        }
        action {
          name: "set_output_port"
          params {
            name: "port"
            value { str: "Ethernet2" }
          }
        }
      }
    }
  )pb");
}

absl::StatusOr<packetlib::Packet> BuildPacket() {
  packetlib::Packet packet;
  auto* ethernet = packet.add_headers()->mutable_ethernet_header();
  ethernet->set_ethernet_destination(kMatchedDstMac);
  ethernet->set_ethernet_source(kSrcMac);
  ethernet->set_ethertype("0x88b5");
  packet.set_payload("test packet #1: 4ward DVaaS PoC");
  RETURN_IF_ERROR(packetlib::PadPacketToMinimumSize(packet).status());
  ASSIGN_OR_RETURN(const std::string serialized, packetlib::SerializePacket(packet));
  return packetlib::ParsePacket(serialized);
}

dvaas::PacketTestVector BuildPacketTestVector(const packetlib::Packet& packet) {
  dvaas::PacketTestVector vector;
  vector.mutable_input()->set_type(dvaas::SwitchInput::DATAPLANE);
  vector.mutable_input()->mutable_packet()->set_port(kIngressPortName);
  *vector.mutable_input()->mutable_packet()->mutable_parsed() = packet;

  auto* output = vector.add_acceptable_outputs()->add_packets();
  output->set_port(kEgressPortName);
  *output->mutable_parsed() = packet;
  return vector;
}

std::string BuildInterfacesJson() {
  return R"json(
{
  "openconfig-interfaces:interfaces": {
    "interface": [
      {
        "name": "Ethernet1",
        "config": {
          "enabled": true,
          "openconfig-p4rt:id": 1
        },
        "state": {
          "enabled": true,
          "ifname": "Ethernet1",
          "openconfig-p4rt:id": 1,
          "oper-status": "UP",
          "type": "iana-if-type:ethernetCsmacd"
        }
      },
      {
        "name": "Ethernet2",
        "config": {
          "enabled": true,
          "openconfig-p4rt:id": 2
        },
        "state": {
          "enabled": true,
          "ifname": "Ethernet2",
          "openconfig-p4rt:id": 2,
          "oper-status": "UP",
          "type": "iana-if-type:ethernetCsmacd"
        }
      }
    ]
  }
}
)json";
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
  FakeGnmiEndpoint sut_gnmi(BuildInterfacesJson());
  FakeGnmiEndpoint control_gnmi(BuildInterfacesJson());

  ASSIGN_OR_RETURN(auto sut, ConnectSwitch(sut_address, sut_gnmi));
  ASSIGN_OR_RETURN(auto control, ConnectSwitch(control_address, control_gnmi));

  RETURN_IF_ERROR(pdpi::InstallIrEntities(*sut.p4rt, BuildForwardingEntities()));

  ASSIGN_OR_RETURN(const auto pipeline_config, GetPipelineConfig(*sut.p4rt));
  ASSIGN_OR_RETURN(const auto packet, BuildPacket());

  dvaas::DataplaneValidationParams params;
  params.artifact_prefix = "fourward_poc";
  params.specification_override = dvaas::P4Specification{
      .p4_symbolic_config = pipeline_config,
      .bmv2_config = pipeline_config,
  };
  params.packet_test_vector_override = {BuildPacketTestVector(packet)};

  auto validator =
      dvaas::DataplaneValidator(std::make_unique<FourwardDvaasBackend>());
  MemoryTestEnvironment environment(std::string(artifact_dir));
  ASSIGN_OR_RETURN(auto result,
                   validator.ValidateDataplaneUsingExistingSwitchApis(
                       sut, control, environment, params));

  RETURN_IF_ERROR(result.HasSuccessRateOfAtLeast(1.0));
  LOG(INFO) << "DVaaS ValidateDataplaneUsingExistingSwitchApis passed";
  LOG(INFO) << "Success rate: " << result.GetSuccessRate();
  return absl::OkStatus();
}

}  // namespace

int main(int argc, char** argv) {
  std::vector<char*> positional = absl::ParseCommandLine(argc, argv);
  CHECK_EQ(positional.size(), 4)
      << "usage: validate_dataplane_poc <sut_host:port> <control_host:port> "
         "<artifact_dir>";
  const absl::Status status = Run(positional[1], positional[2], positional[3]);
  if (!status.ok()) {
    LOG(ERROR) << status;
    return 1;
  }
  return 0;
}
