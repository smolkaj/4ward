---
description: "Embedding the 4ward server in a C++ program via the p4runtime_cc wrapper — Bazel setup, example usage, and the stable startup contract downstream wrappers can rely on."
---

# Embedding the server in C++

`//p4runtime_cc:fourward_server` is a C++ RAII wrapper that brings up the
4ward P4Runtime + Dataplane server as a child process, waits until it is
accepting gRPC calls, and tears it down when it goes out of scope. Use it
when you want to drive 4ward from C++ test harnesses, reference
implementations, or differential-testing infrastructure without pulling
Kotlin or the JVM build tools into your own project.

## Bazel dependency

```starlark
cc_test(
    name = "my_test",
    srcs = ["my_test.cc"],
    deps = [
        "@fourward//p4runtime_cc:fourward_server",
        # ... your gRPC / P4Runtime deps ...
    ],
)
```

The library propagates the server binary through its `data` attribute, so
consumers do not need to list `@fourward//p4runtime:p4runtime_server`
themselves.

## Example

```cpp
#include "p4runtime_cc/fourward_server.h"
#include "grpcpp/grpcpp.h"
#include "p4/v1/p4runtime.grpc.pb.h"

absl::Status RunAgainstFourward() {
  ASSIGN_OR_RETURN(fourward::FourwardServer server,
                   fourward::FourwardServer::Start());

  auto channel = grpc::CreateChannel(server.Address(),
                                     grpc::InsecureChannelCredentials());
  auto stub = p4::v1::P4Runtime::NewStub(channel);
  // ... drive the server via gRPC ...

  return absl::OkStatus();
  // `server` is killed here via SIGTERM; the scratch directory holding its
  // port-file is removed on the same path.
}
```

Options cover `device_id`, the listening `port` (unset by default — the
kernel picks an ephemeral port), `drop_port`, `cpu_port`, and
`startup_timeout`. See [`fourward_server.h`](https://github.com/smolkaj/4ward/blob/main/p4runtime_cc/fourward_server.h)
for the full API.

## Startup contract

The wrapper does not rely on the server's log banner to discover its port.
Instead it passes `--port-file=PATH`, and the server atomically writes its
listening port to `PATH` once it is accepting RPCs. File existence is the
readiness signal; file contents are the port.

That contract is stable and suitable for hand-rolled wrappers in other
languages:

| Flag | Semantics |
|------|-----------|
| `--port=N` | Pin the listening port. `--port=0` (the default) lets the kernel pick an ephemeral port — recommended for parallel test shards. |
| `--port-file=PATH` | After binding, the server atomically writes the bound port as ASCII to `PATH` (via tempfile + rename, so a concurrent reader never sees a partial value). |

The banner on stdout (`P4Runtime server listening on port N`) is for
humans only and may change without notice. Always synchronize on the
port-file.

## Related

- [`p4runtime_cc/`](https://github.com/smolkaj/4ward/tree/main/p4runtime_cc)
  — source of the wrapper.
- [gRPC API reference](grpc.md) — server flags, RPC surface, and proto
  definitions.
