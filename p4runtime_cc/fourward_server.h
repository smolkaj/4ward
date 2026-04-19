// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#ifndef FOURWARD_P4RUNTIME_CC_FOURWARD_SERVER_H_
#define FOURWARD_P4RUNTIME_CC_FOURWARD_SERVER_H_

// RAII wrapper that brings up a 4ward P4Runtime + Dataplane gRPC server as a
// child process, waits until it is ready to serve RPCs, and tears it down
// when the wrapper goes out of scope.
//
// Intended for C++ clients (tests, reference harnesses, differential-testing
// infrastructure) that want to embed 4ward without touching the JVM toolchain
// directly.
//
// Example:
//
//     #include "p4runtime_cc/fourward_server.h"
//
//     absl::StatusOr<fourward::FourwardServer> server =
//         fourward::FourwardServer::Start();
//     ASSERT_TRUE(server.ok()) << server.status();
//     auto channel = grpc::CreateChannel(server->Address(),
//                                        grpc::InsecureChannelCredentials());
//     auto stub = p4::v1::P4Runtime::NewStub(channel);
//     // ... drive the server via gRPC ...
//     // Server is killed when `server` goes out of scope.
//
// A Bazel consumer only needs to add this target to `deps`; the server binary
// is propagated through `cc_library.data` into the test's runfiles.
//
// Startup contract (stable): the server is launched with `--port-file=PATH`,
// to which it atomically writes its listening port once it is accepting RPCs.
// This wrapper polls for the file and parses the port, which is why the
// log-format of the banner on stdout is NOT part of the contract and may
// change without notice.

#include <sys/types.h>  // pid_t

#include <cstdint>
#include <optional>
#include <string>

#include "absl/status/statusor.h"
#include "absl/time/time.h"

namespace fourward {

// Packet-in/out CPU port configuration. Mirrors the Kotlin CpuPortConfig:
// Auto (infer from P4Info's controller_header, the default), Disabled (no
// CPU port — packet I/O rejected), or Override (use this specific port).
struct CpuPort {
  enum class Kind { kAuto, kDisabled, kOverride };

  static CpuPort Auto() { return {Kind::kAuto, 0}; }
  static CpuPort Disabled() { return {Kind::kDisabled, 0}; }
  static CpuPort Override(int port) { return {Kind::kOverride, port}; }

  Kind kind = Kind::kAuto;
  int port = 0;  // meaningful only when kind == kOverride
};

struct FourwardServerOptions {
  // P4Runtime device ID exposed by the server (the `--device-id` flag).
  uint64_t device_id = 1;

  // TCP port the server binds on. If unset, the kernel assigns an ephemeral
  // port — the recommended default, since it avoids collisions when multiple
  // servers run in parallel (e.g. in a test shard).
  std::optional<int> port = std::nullopt;

  // v1model drop-port override (the `--drop-port` flag). If unset, the
  // simulator's built-in default is used.
  std::optional<int> drop_port = std::nullopt;

  // CPU port configuration (the `--cpu-port` flag).
  CpuPort cpu_port = CpuPort::Auto();

  // Maximum time to wait for the server to become ready. JVM warm-up on cold
  // caches dominates — 30s is generous but not paranoid.
  absl::Duration startup_timeout = absl::Seconds(30);
};

class FourwardServer {
 public:
  using Options = FourwardServerOptions;

  // Forks a server subprocess and blocks until it is accepting gRPC calls.
  // Returns NotFoundError if the server binary is missing from runfiles,
  // DeadlineExceededError on startup timeout, InternalError on other
  // lifecycle failures.
  static absl::StatusOr<FourwardServer> Start(Options options = {});

  ~FourwardServer();

  // Move-only. The server is killed by whichever instance owns the PID.
  FourwardServer(FourwardServer&& other) noexcept;
  FourwardServer& operator=(FourwardServer&& other) noexcept;
  FourwardServer(const FourwardServer&) = delete;
  FourwardServer& operator=(const FourwardServer&) = delete;

  // Address suitable for grpc::CreateChannel, e.g. "localhost:42517".
  const std::string& Address() const { return address_; }

  // TCP port the server is listening on.
  int Port() const { return port_; }

  // P4Runtime device ID exposed by the server.
  uint64_t DeviceId() const { return device_id_; }

  // PID of the server subprocess. Primarily useful for diagnostics.
  pid_t Pid() const { return pid_; }

 private:
  FourwardServer(pid_t pid, int port, uint64_t device_id,
                 std::string scratch_dir);

  // Kills the subprocess (SIGTERM → SIGKILL) and removes the scratch dir.
  void Shutdown();

  pid_t pid_ = -1;
  int port_ = 0;
  uint64_t device_id_ = 0;
  std::string address_;
  // Scratch directory holding the `--port-file`. Removed on Shutdown.
  std::string scratch_dir_;
};

}  // namespace fourward

#endif  // FOURWARD_P4RUNTIME_CC_FOURWARD_SERVER_H_
