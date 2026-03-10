#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ROOT}/.tmp/dvaas_poc_artifacts"
SONIC_PINS_REF="${SONIC_PINS_REF:-6052c041f299fdf8fad50236caf15483e95b56d4}"
FOURWARD_BAZEL_CONFIG="${FOURWARD_BAZEL_CONFIG:-}"
DVAAS_POC_WRAP_GDB="${DVAAS_POC_WRAP_GDB:-0}"
SUT_PORT=9560
CONTROL_PORT=9561
CPU_PORT=510

mkdir -p "${ARTIFACT_DIR}"

if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
  SONIC_PINS_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sonic-pins.XXXXXX")"
  SONIC_PINS_EPHEMERAL=1
else
  SONIC_PINS_DIR="${ROOT}/.tmp/sonic-pins"
  mkdir -p "${SONIC_PINS_DIR}"
  SONIC_PINS_EPHEMERAL=0
fi
SONIC_PINS_BAZELRC="${SONIC_PINS_DIR}/dvaas-poc.bazelrc"

dump_logs() {
  for log in "${ARTIFACT_DIR}/sut.log" "${ARTIFACT_DIR}/control.log"; do
    if [[ -f "${log}" ]]; then
      echo "===== ${log} ====="
      tail -n 200 "${log}" || true
    fi
  done
}

cleanup() {
  status=$?
  if [[ ${status} -ne 0 ]]; then
    dump_logs
  fi
  if [[ -n "${CONTROL_PID:-}" ]]; then kill "${CONTROL_PID}" >/dev/null 2>&1 || true; fi
  if [[ -n "${SUT_PID:-}" ]]; then kill "${SUT_PID}" >/dev/null 2>&1 || true; fi
  if [[ "${SONIC_PINS_EPHEMERAL}" == "1" ]]; then
    rm -rf "${SONIC_PINS_DIR}"
  fi
  exit "${status}"
}
trap cleanup EXIT

wait_for_port() {
  python3 - "$1" "$2" <<'PY'
import socket
import sys
import time

host = sys.argv[1]
port = int(sys.argv[2])
deadline = time.time() + 30
while time.time() < deadline:
    try:
        with socket.create_connection((host, port), timeout=0.5):
            sys.exit(0)
    except OSError:
        time.sleep(0.2)
sys.exit(1)
PY
}

BAZEL_ARGS=()
if [[ -n "${FOURWARD_BAZEL_CONFIG}" ]]; then
  BAZEL_ARGS+=("--config=${FOURWARD_BAZEL_CONFIG}")
fi

SONIC_PINS_BAZEL_STARTUP_ARGS=(
  "--bazelrc=${SONIC_PINS_BAZELRC}"
  "--nosystem_rc"
  "--noworkspace_rc"
  "--nohome_rc"
)

if [[ "${SONIC_PINS_EPHEMERAL}" == "0" ]]; then
  SONIC_PINS_BAZEL_STARTUP_ARGS+=("--output_user_root=${ROOT}/.tmp/bazel-sonic-pins")
fi

SONIC_PINS_BAZEL_ARGS=()

if [[ "$(uname -s)" == "Darwin" ]]; then
  if command -v ld64.lld >/dev/null 2>&1; then
    DARWIN_LINKER="$(command -v ld64.lld)"
  else
    DARWIN_LINKER="ld"
  fi
fi

write_sonic_pins_bazelrc() {
  cat >"${SONIC_PINS_BAZELRC}" <<EOF
build --cxxopt=-std=c++17
build --host_cxxopt=-std=c++17
build --java_runtime_version=remotejdk_21
build --tool_java_runtime_version=remotejdk_21
build --action_env=CC=clang
build --action_env=CXX=clang++
build --repo_env=CC=/usr/bin/clang
EOF

  if [[ -n "${BUILDBUDDY_API_KEY:-}" ]]; then
    cat >>"${SONIC_PINS_BAZELRC}" <<EOF
build --remote_cache=grpcs://4ward.buildbuddy.io
build --remote_download_toplevel
build --remote_upload_local_results
build --remote_timeout=3s
build --remote_cache_compression
build --bes_results_url=https://4ward.buildbuddy.io/invocation/
build --bes_backend=grpcs://4ward.buildbuddy.io
common --remote_header=x-buildbuddy-api-key=${BUILDBUDDY_API_KEY}
EOF
  fi

  if [[ "$(uname -s)" == "Darwin" ]]; then
    cat >>"${SONIC_PINS_BAZELRC}" <<EOF
build --repo_env=BAZEL_USE_CPP_ONLY_TOOLCHAIN=1
build --action_env=CC=clang
build --action_env=CXX=clang++
build --repo_env=CC=/usr/bin/clang
build --macos_minimum_os=10.15
build --host_macos_minimum_os=10.15
build --linkopt=-fuse-ld=${DARWIN_LINKER}
build --host_linkopt=-fuse-ld=${DARWIN_LINKER}
build --per_file_copt=external/zlib/.*@-UTARGET_OS_MAC
build --host_per_file_copt=external/zlib/.*@-UTARGET_OS_MAC
EOF
  fi
}

apply_darwin_sonic_pins_patches() {
  local ir_cc="${SONIC_PINS_DIR}/p4_infra/p4_pdpi/utils/ir.cc"
  if [[ -f "${ir_cc}" ]]; then
    # WORKAROUND: The pinned sonic-pins snapshot assumes Linux's <endian.h>.
    # Darwin does not ship that header, but it provides equivalent byte-order
    # helpers via <libkern/OSByteOrder.h>. Patch the transient checkout only;
    # the intended steady state is upstream code that includes the right header
    # per platform instead of us rewriting it here.
    perl -0pi -e 's|#include <arpa/inet\.h>\n#include <endian\.h>\n|#include <arpa/inet.h>\n#ifdef __APPLE__\n#include <libkern/OSByteOrder.h>\n#define be64toh OSSwapBigToHostInt64\n#else\n#include <endian.h>\n#endif\n|g' \
      "${ir_cc}"
    # WORKAROUND: The same pinned snapshot also includes Linux's
    # <netinet/ether.h>, which Darwin does not provide. Nothing in this file
    # uses that header, so drop it in the transient checkout instead of
    # carrying a fork of sonic-pins just for Apple builds.
    perl -0pi -e 's|#include <netinet/ether\.h>\n||g' \
      "${ir_cc}"
  fi
}

run_sonic_pins_bazel() {
  if (( ${#SONIC_PINS_BAZEL_ARGS[@]} > 0 )); then
    bazel "${SONIC_PINS_BAZEL_STARTUP_ARGS[@]}" "$@" "${SONIC_PINS_BAZEL_ARGS[@]}"
  else
    bazel "${SONIC_PINS_BAZEL_STARTUP_ARGS[@]}" "$@"
  fi
}

if [[ "${GITHUB_ACTIONS:-}" == "true" && "${DVAAS_POC_WRAP_GDB}" == "0" ]] &&
  command -v gdb >/dev/null 2>&1; then
  DVAAS_POC_WRAP_GDB=1
fi

bazel build "${BAZEL_ARGS[@]}" \
  //p4runtime:p4runtime_server \
  //e2e_tests/dvaas_poc:dvaas_poc_pb

if [[ ! -d "${SONIC_PINS_DIR}/.git" ]]; then
  git -C "${SONIC_PINS_DIR}" init -q
  git -C "${SONIC_PINS_DIR}" remote add origin https://github.com/sonic-net/sonic-pins.git
fi
git -C "${SONIC_PINS_DIR}" fetch --depth 1 origin "${SONIC_PINS_REF}"
git -C "${SONIC_PINS_DIR}" checkout --detach FETCH_HEAD
git -C "${SONIC_PINS_DIR}" reset --hard FETCH_HEAD
git -C "${SONIC_PINS_DIR}" clean -fd
git -C "${SONIC_PINS_DIR}" apply --whitespace=nowarn \
  "${ROOT}/tools/dvaas_overlay/sonic_pins_dvaas_poc.patch"
mkdir -p "${SONIC_PINS_DIR}/fourward_dvaas"
cp "${ROOT}/tools/dvaas_overlay/BUILD.bazel" "${SONIC_PINS_DIR}/fourward_dvaas/BUILD.bazel"
cp "${ROOT}/tools/dvaas_overlay/validate_dataplane_poc.cc" "${SONIC_PINS_DIR}/fourward_dvaas/validate_dataplane_poc.cc"
write_sonic_pins_bazelrc
if [[ "$(uname -s)" == "Darwin" ]]; then
  apply_darwin_sonic_pins_patches
fi

"${ROOT}/bazel-bin/p4runtime/p4runtime_server" \
  --port="${SUT_PORT}" \
  --pipeline="${ROOT}/bazel-bin/e2e_tests/dvaas_poc/dvaas_poc.txtpb" \
  --cpu_port="${CPU_PORT}" \
  >"${ARTIFACT_DIR}/sut.log" 2>&1 &
SUT_PID=$!

"${ROOT}/bazel-bin/p4runtime/p4runtime_server" \
  --port="${CONTROL_PORT}" \
  --pipeline="${ROOT}/bazel-bin/e2e_tests/dvaas_poc/dvaas_poc.txtpb" \
  --cpu_port="${CPU_PORT}" \
  --peer_dataplane="localhost:${SUT_PORT}" \
  >"${ARTIFACT_DIR}/control.log" 2>&1 &
CONTROL_PID=$!

wait_for_port localhost "${SUT_PORT}"
wait_for_port localhost "${CONTROL_PORT}"

if [[ "$(uname -s)" == "Darwin" ]]; then
  (
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel build --nobuild //fourward_dvaas:validate_dataplane_poc >/dev/null
  )
  SONIC_PINS_OUTPUT_BASE="$(
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel info output_base
  )"
  SONIC_PINS_GRPC_BASIC_SEQ="$(
    find "${SONIC_PINS_OUTPUT_BASE}/external" \
      -path '*com_github_grpc_grpc/src/core/lib/promise/detail/basic_seq.h' \
      -print -quit
  )"
  if [[ -n "${SONIC_PINS_GRPC_BASIC_SEQ}" ]]; then
    # WORKAROUND: The pinned sonic-pins snapshot vendors a grpc header that
    # uses `Traits::template CallSeqFactory(...)` even though CallSeqFactory is
    # not a template. Apple clang rejects that construct, while Linux CI
    # accepts it. Patch the transient Bazel external only.
    perl -0pi -e 's/Traits::template CallSeqFactory/Traits::CallSeqFactory/g' \
      "${SONIC_PINS_GRPC_BASIC_SEQ}"
  fi
fi

(
  cd "${SONIC_PINS_DIR}" &&
    run_sonic_pins_bazel build //fourward_dvaas:validate_dataplane_poc
)
if ! (
  cd "${SONIC_PINS_DIR}" &&
    TEST_UNDECLARED_OUTPUTS_DIR="${ARTIFACT_DIR}" \
    TEST_TMPDIR="${ARTIFACT_DIR}" \
    bazel-bin/fourward_dvaas/validate_dataplane_poc \
      "localhost:${SUT_PORT}" "localhost:${CONTROL_PORT}" "${ARTIFACT_DIR}"
); then
  if [[ "${DVAAS_POC_WRAP_GDB}" == "1" ]] && command -v gdb >/dev/null 2>&1; then
    (
      cd "${SONIC_PINS_DIR}" &&
        TEST_UNDECLARED_OUTPUTS_DIR="${ARTIFACT_DIR}" \
        TEST_TMPDIR="${ARTIFACT_DIR}" \
        gdb -q -batch -ex run -ex bt --args \
          bazel-bin/fourward_dvaas/validate_dataplane_poc \
          "localhost:${SUT_PORT}" "localhost:${CONTROL_PORT}" "${ARTIFACT_DIR}"
    )
  else
    exit 1
  fi
fi

echo "Artifacts written to ${ARTIFACT_DIR}"
