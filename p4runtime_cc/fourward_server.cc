// Copyright 2026 The 4ward Authors
// SPDX-License-Identifier: Apache-2.0

#include "p4runtime_cc/fourward_server.h"

#include <signal.h>
#include <spawn.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_format.h"
#include "absl/strings/strip.h"
#include "absl/time/clock.h"
#include "absl/time/time.h"
#include "tools/cpp/runfiles/runfiles.h"

extern char** environ;

namespace fourward {
namespace {

using ::bazel::tools::cpp::runfiles::Runfiles;

// Bazel label of the server binary. Resolved via runfiles at launch so the
// wrapper works identically from `bazel test` and installed-binary scenarios.
constexpr char kServerRunfile[] = "_main/p4runtime/p4runtime_server";

absl::Status PosixError(const std::string& what, int err) {
  return absl::InternalError(
      absl::StrCat(what, ": ", std::strerror(err), " (errno=", err, ")"));
}

// Creates a unique scratch directory under $TEST_TMPDIR (honored by Bazel
// test shards) or /tmp. Not deleted on Shutdown — the test harness cleans
// $TEST_TMPDIR, and for non-test embeds a handful of stale dirs under /tmp
// are harmless relative to the cost of racing cleanup against a still-alive
// subprocess on error paths.
absl::StatusOr<std::string> MakeScratchDir() {
  const char* base = std::getenv("TEST_TMPDIR");
  if (base == nullptr || *base == '\0') base = "/tmp";
  std::string tmpl = absl::StrCat(base, "/fourward-server-XXXXXX");
  std::vector<char> buf(tmpl.begin(), tmpl.end());
  buf.push_back('\0');
  if (mkdtemp(buf.data()) == nullptr) {
    return PosixError("mkdtemp", errno);
  }
  return std::string(buf.data());
}

absl::StatusOr<int> ReadPortFile(const std::string& path) {
  std::ifstream in(path);
  if (!in) return PosixError(absl::StrCat("open ", path), errno);
  std::stringstream ss;
  ss << in.rdbuf();
  std::string contents = ss.str();
  // The writer uses atomic rename, so a successful open never observes a
  // partial value — but it may still observe trailing whitespace from a
  // future writer. Be forgiving.
  absl::string_view trimmed = absl::StripAsciiWhitespace(contents);
  int port = 0;
  if (!absl::SimpleAtoi(trimmed, &port) || port <= 0 || port > 65535) {
    return absl::InternalError(
        absl::StrCat("port file at ", path, " has invalid contents: '",
                     contents, "'"));
  }
  return port;
}

// Waits for `path` to exist (meaning: the server has bound and published its
// port) or `deadline` to elapse. Returns DeadlineExceededError on timeout.
// The child's exit is checked on each poll so we don't hang forever if the
// server crashed before writing the file.
absl::Status WaitForPortFile(const std::string& path, pid_t child_pid,
                             absl::Time deadline) {
  constexpr absl::Duration kPoll = absl::Milliseconds(25);
  while (absl::Now() < deadline) {
    struct stat st;
    if (::stat(path.c_str(), &st) == 0 && st.st_size > 0) {
      return absl::OkStatus();
    }

    int status = 0;
    pid_t waited = ::waitpid(child_pid, &status, WNOHANG);
    if (waited == child_pid) {
      return absl::InternalError(absl::StrFormat(
          "server subprocess exited before reporting its port "
          "(pid=%d, status=0x%x). Check server stderr for a stack trace.",
          child_pid, status));
    }

    absl::SleepFor(kPoll);
  }
  return absl::DeadlineExceededError(absl::StrCat(
      "server did not publish its port to ", path, " before the timeout"));
}

// Reaps `pid` with a bounded wait; returns true if the process is gone.
bool TryReap(pid_t pid, absl::Duration budget) {
  absl::Time deadline = absl::Now() + budget;
  do {
    int status = 0;
    pid_t waited = ::waitpid(pid, &status, WNOHANG);
    if (waited == pid) return true;
    if (waited < 0 && errno == ECHILD) return true;
    absl::SleepFor(absl::Milliseconds(20));
  } while (absl::Now() < deadline);
  return false;
}

}  // namespace

absl::StatusOr<FourwardServer> FourwardServer::Start(Options options) {
  std::string runfiles_error;
  std::unique_ptr<Runfiles> runfiles(
      Runfiles::Create("", BAZEL_CURRENT_REPOSITORY, &runfiles_error));
  if (runfiles == nullptr) {
    return absl::InternalError(
        absl::StrCat("failed to resolve runfiles: ", runfiles_error));
  }
  std::string server_path = runfiles->Rlocation(kServerRunfile);
  if (server_path.empty() || ::access(server_path.c_str(), X_OK) != 0) {
    return absl::NotFoundError(absl::StrCat(
        "4ward P4Runtime server binary not found in runfiles (expected ",
        kServerRunfile,
        "). Does your cc_test list `@fourward//p4runtime:p4runtime_server` "
        "in `data`?"));
  }

  absl::StatusOr<std::string> scratch = MakeScratchDir();
  if (!scratch.ok()) return std::move(scratch).status();
  std::string port_file = absl::StrCat(*scratch, "/port");

  // Build argv. posix_spawn takes a char* const[]; build strings first,
  // then snapshot their c_str()s into the argv array.
  std::vector<std::string> args = {
      server_path,
      absl::StrCat("--port=", options.port.value_or(0)),
      absl::StrCat("--device-id=", options.device_id),
      absl::StrCat("--port-file=", port_file),
  };
  if (options.drop_port.has_value()) {
    args.push_back(absl::StrCat("--drop-port=", *options.drop_port));
  }
  switch (options.cpu_port.kind) {
    case CpuPort::Kind::kAuto:
      break;  // Default; omit the flag.
    case CpuPort::Kind::kDisabled:
      args.emplace_back("--cpu-port=none");
      break;
    case CpuPort::Kind::kOverride:
      args.push_back(absl::StrCat("--cpu-port=", options.cpu_port.port));
      break;
  }
  std::vector<char*> argv;
  argv.reserve(args.size() + 1);
  for (auto& a : args) argv.push_back(a.data());
  argv.push_back(nullptr);

  // Put the server into its own process group so SIGTERM to the group fans
  // out to any JVM grandchildren (e.g. native Netty helpers) without
  // touching the parent test process. posix_spawn with POSIX_SPAWN_SETPGROUP
  // + pgroup=0 is the portable way to request that.
  posix_spawnattr_t attr;
  if (int rc = posix_spawnattr_init(&attr); rc != 0) {
    return PosixError("posix_spawnattr_init", rc);
  }
  if (int rc = posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETPGROUP);
      rc != 0) {
    posix_spawnattr_destroy(&attr);
    return PosixError("posix_spawnattr_setflags", rc);
  }
  if (int rc = posix_spawnattr_setpgroup(&attr, 0); rc != 0) {
    posix_spawnattr_destroy(&attr);
    return PosixError("posix_spawnattr_setpgroup", rc);
  }

  pid_t pid = -1;
  int spawn_rc = posix_spawn(&pid, server_path.c_str(), /*file_actions=*/nullptr,
                             &attr, argv.data(), environ);
  posix_spawnattr_destroy(&attr);
  if (spawn_rc != 0) return PosixError("posix_spawn", spawn_rc);

  absl::Time deadline = absl::Now() + options.startup_timeout;
  if (absl::Status s = WaitForPortFile(port_file, pid, deadline); !s.ok()) {
    ::kill(pid, SIGKILL);
    TryReap(pid, absl::Seconds(5));
    return s;
  }
  absl::StatusOr<int> port = ReadPortFile(port_file);
  if (!port.ok()) {
    ::kill(pid, SIGKILL);
    TryReap(pid, absl::Seconds(5));
    return std::move(port).status();
  }

  return FourwardServer(pid, *port, options.device_id);
}

FourwardServer::FourwardServer(pid_t pid, int port, uint64_t device_id)
    : pid_(pid),
      port_(port),
      device_id_(device_id),
      address_(absl::StrCat("localhost:", port)) {}

FourwardServer::FourwardServer(FourwardServer&& other) noexcept
    : pid_(other.pid_),
      port_(other.port_),
      device_id_(other.device_id_),
      address_(std::move(other.address_)) {
  other.pid_ = -1;
}

FourwardServer& FourwardServer::operator=(FourwardServer&& other) noexcept {
  if (this != &other) {
    Shutdown();
    pid_ = other.pid_;
    port_ = other.port_;
    device_id_ = other.device_id_;
    address_ = std::move(other.address_);
    other.pid_ = -1;
  }
  return *this;
}

FourwardServer::~FourwardServer() { Shutdown(); }

void FourwardServer::Shutdown() {
  if (pid_ <= 0) return;
  // Signal the process group so any JVM-spawned helpers go too.
  ::killpg(pid_, SIGTERM);
  if (!TryReap(pid_, absl::Seconds(5))) {
    ::killpg(pid_, SIGKILL);
    TryReap(pid_, absl::Seconds(2));
  }
  pid_ = -1;
}

}  // namespace fourward
