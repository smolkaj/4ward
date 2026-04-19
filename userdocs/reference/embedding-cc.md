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

absl::Status RunAgainstFourward() {
  ASSIGN_OR_RETURN(fourward::FourwardServer server,
                   fourward::FourwardServer::Start());

  auto p4rt = server.NewP4RuntimeStub();
  auto dataplane = server.NewDataplaneStub();
  // ... drive the server via gRPC ...

  return absl::OkStatus();
  // `server` is killed here via SIGTERM; the scratch directory holding its
  // port-file is removed on the same path.
}
```

Both stubs share a single insecure channel to `localhost:<port>` managed
by the wrapper.

Options cover `device_id`, the listening `port` (unset by default — the
kernel picks an ephemeral port), `drop_port`, `cpu_port`, and
`startup_timeout`. See [`fourward_server.h`](https://github.com/smolkaj/4ward/blob/main/p4runtime_cc/fourward_server.h)
for the full API.

## Startup contract

The wrapper spawns the server with `--port-file=PATH`. The server
atomically writes its listening port to `PATH` once it is accepting RPCs;
file existence is the readiness signal, file contents are the port. The
contract is stable and suitable for hand-rolled wrappers in other
languages.

| Flag | Semantics |
|------|-----------|
| `--port=N` | Pin the listening port. `--port=0` (the default) lets the kernel pick an ephemeral port — recommended for parallel test shards. |
| `--port-file=PATH` | After binding, the server atomically writes the bound port as ASCII to `PATH` (tempfile + rename, so a concurrent reader never sees a partial value). |

## Related

- [`p4runtime_cc/`](https://github.com/smolkaj/4ward/tree/main/p4runtime_cc)
  — source of the wrapper.
- [gRPC API reference](grpc.md) — server flags, RPC surface, and proto
  definitions.
