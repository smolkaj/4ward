#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ROOT}/.tmp/dvaas_poc_artifacts"
SONIC_PINS_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sonic-pins.XXXXXX")"
SONIC_PINS_BAZELRC="${SONIC_PINS_DIR}/dvaas-poc.bazelrc"
SONIC_PINS_REF="${SONIC_PINS_REF:-6052c041f299fdf8fad50236caf15483e95b56d4}"
FOURWARD_BAZEL_CONFIG="${FOURWARD_BAZEL_CONFIG:-}"
DVAAS_POC_WRAP_GDB="${DVAAS_POC_WRAP_GDB:-0}"
SUT_PORT=9560
CONTROL_PORT=9561
CPU_PORT=510

mkdir -p "${ARTIFACT_DIR}"

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
  rm -rf "${SONIC_PINS_DIR}"
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

SONIC_PINS_BAZEL_STARTUP_ARGS=("--bazelrc=${SONIC_PINS_BAZELRC}")
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
common --nosystem_rc
common --noworkspace_rc
common --nohome_rc
common --enable_bzlmod
common --noenable_workspace
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

git -C "${SONIC_PINS_DIR}" init -q
git -C "${SONIC_PINS_DIR}" remote add origin https://github.com/sonic-net/sonic-pins.git
git -C "${SONIC_PINS_DIR}" fetch --depth 1 origin "${SONIC_PINS_REF}"
git -C "${SONIC_PINS_DIR}" checkout --detach FETCH_HEAD
mkdir -p "${SONIC_PINS_DIR}/fourward_dvaas"
cp "${ROOT}/tools/dvaas_overlay/BUILD.bazel" "${SONIC_PINS_DIR}/fourward_dvaas/BUILD.bazel"
cp "${ROOT}/tools/dvaas_overlay/validate_dataplane_poc.cc" "${SONIC_PINS_DIR}/fourward_dvaas/validate_dataplane_poc.cc"
write_sonic_pins_bazelrc

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
if [[ "${DVAAS_POC_WRAP_GDB}" == "1" ]] && command -v gdb >/dev/null 2>&1; then
  (
    cd "${SONIC_PINS_DIR}" &&
      gdb -q -batch -ex run -ex bt --args \
        bazel-bin/fourward_dvaas/validate_dataplane_poc \
        "localhost:${SUT_PORT}" "localhost:${CONTROL_PORT}" "${ARTIFACT_DIR}"
  )
else
  (
    cd "${SONIC_PINS_DIR}" &&
      bazel-bin/fourward_dvaas/validate_dataplane_poc \
        "localhost:${SUT_PORT}" "localhost:${CONTROL_PORT}" "${ARTIFACT_DIR}"
  )
fi

echo "Artifacts written to ${ARTIFACT_DIR}"
