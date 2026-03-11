#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ROOT}/.tmp/dvaas_poc_artifacts"
PATCH_DIR="${ROOT}/tools/dvaas_overlay/patches"
SONIC_PINS_REF="${SONIC_PINS_REF:-6052c041f299fdf8fad50236caf15483e95b56d4}"
FOURWARD_BAZEL_CONFIG="${FOURWARD_BAZEL_CONFIG:-}"
DVAAS_POC_WRAP_GDB="${DVAAS_POC_WRAP_GDB:-0}"
FOURWARD_DVAAS_PIPELINE_TARGET="${FOURWARD_DVAAS_PIPELINE_TARGET:-//e2e_tests/dvaas_poc:dvaas_poc_pb}"
FOURWARD_DVAAS_PIPELINE_TXTPB="${FOURWARD_DVAAS_PIPELINE_TXTPB:-e2e_tests/dvaas_poc/dvaas_poc.txtpb}"
FOURWARD_DVAAS_HELPER_BINARY="${FOURWARD_DVAAS_HELPER_BINARY:-validate_dataplane_poc}"
SUT_PORT=9560
CONTROL_PORT=9561
CPU_PORT=510
OS_NAME="$(uname -s)"

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

if [[ "${OS_NAME}" == "Darwin" ]]; then
  if command -v ld64.lld >/dev/null 2>&1; then
    DARWIN_LINKER="$(command -v ld64.lld)"
  else
    DARWIN_LINKER="ld"
  fi
fi

apply_patch_file() {
  local target_dir="$1"
  local patch_file="$2"
  if patch --forward --batch --silent --dry-run -p1 -d "${target_dir}" < "${patch_file}"; then
    patch --forward --batch --silent -p1 -d "${target_dir}" < "${patch_file}"
    return
  fi
  if patch --reverse --batch --silent --dry-run -p1 -d "${target_dir}" < "${patch_file}"; then
    return
  fi
  patch --forward --batch -p1 -d "${target_dir}" < "${patch_file}"
}

write_sonic_pins_bazelrc() {
  local python_bin
  local python_dir
  python_bin="$(command -v python3)"
  python_dir="${SONIC_PINS_DIR}/bin"
  cat >"${SONIC_PINS_BAZELRC}" <<EOF
build --cxxopt=-std=c++17
build --host_cxxopt=-std=c++17
build --java_runtime_version=remotejdk_21
build --tool_java_runtime_version=remotejdk_21
build --action_env=CC=clang
build --action_env=CXX=clang++
build --action_env=PATH=${python_dir}:/usr/local/bin:/usr/bin:/bin
build --action_env=PYTHON=${python_bin}
build --host_action_env=PATH=${python_dir}:/usr/local/bin:/usr/bin:/bin
build --host_action_env=PYTHON=${python_bin}
build --repo_env=CC=/usr/bin/clang
build --repo_env=PATH=${python_dir}:/usr/local/bin:/usr/bin:/bin
build --repo_env=PYTHON=${python_bin}
build --repo_env=PYTHON_BIN_PATH=${python_bin}
build --sandbox_add_mount_pair=${python_dir}:/usr/local/bin
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

  if [[ "${OS_NAME}" == "Darwin" ]]; then
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

build_fourward_targets() {
  local bazel_cmd=(bazel build)
  if (( ${#BAZEL_ARGS[@]} > 0 )); then
    bazel_cmd+=("${BAZEL_ARGS[@]}")
  fi
  bazel_cmd+=(
    //p4runtime:p4runtime_server
    "${FOURWARD_DVAAS_PIPELINE_TARGET}"
  )
  "${bazel_cmd[@]}"
}

prepare_sonic_pins_checkout() {
  local python_bin
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
  mkdir -p "${SONIC_PINS_DIR}/bin"
  python_bin="$(command -v python3)"
  ln -sf "${python_bin}" "${SONIC_PINS_DIR}/bin/python"
  ln -sf "${python_bin}" "${SONIC_PINS_DIR}/python"
  cp "${ROOT}/tools/dvaas_overlay/overlay.BUILD.bazel" "${SONIC_PINS_DIR}/fourward_dvaas/BUILD.bazel"
  cp "${ROOT}/tools/dvaas_overlay/"*.cc "${SONIC_PINS_DIR}/fourward_dvaas/"
  write_sonic_pins_bazelrc

  if [[ "${OS_NAME}" == "Darwin" ]]; then
    # WORKAROUND: The pinned sonic-pins snapshot assumes Linux-only headers in
    # `ir.cc`. Apply a named patch instead of ad hoc source rewrites so the
    # Darwin-specific delta stays reviewable and easy to drop once upstream is fixed.
    apply_patch_file "${SONIC_PINS_DIR}" "${PATCH_DIR}/sonic_pins_ir_darwin.patch"
  fi
}

start_fourward_server() {
  local port="$1"
  local log="$2"
  shift 2
  "${ROOT}/bazel-bin/p4runtime/p4runtime_server" \
    --port="${port}" \
    --pipeline="${ROOT}/bazel-bin/${FOURWARD_DVAAS_PIPELINE_TXTPB}" \
    --cpu_port="${CPU_PORT}" \
    --strict \
    "$@" >"${log}" 2>&1 &
}

kill_listener_on_port() {
  local port="$1"
  local pids=()
  local pid
  if ! command -v lsof >/dev/null 2>&1; then
    return
  fi
  while IFS= read -r pid; do
    [[ -n "${pid}" ]] && pids+=("${pid}")
  done < <(lsof -ti "tcp:${port}" -sTCP:LISTEN 2>/dev/null || true)
  if (( ${#pids[@]} == 0 )); then
    return
  fi
  kill "${pids[@]}" >/dev/null 2>&1 || true
  sleep 1
}

start_testbed() {
  kill_listener_on_port "${SUT_PORT}"
  kill_listener_on_port "${CONTROL_PORT}"
  start_fourward_server "${SUT_PORT}" "${ARTIFACT_DIR}/sut.log"
  SUT_PID=$!
  start_fourward_server "${CONTROL_PORT}" "${ARTIFACT_DIR}/control.log" \
    --peer_dataplane="localhost:${SUT_PORT}"
  CONTROL_PID=$!
  wait_for_port localhost "${SUT_PORT}"
  wait_for_port localhost "${CONTROL_PORT}"
}

apply_darwin_grpc_patch() {
  local output_base
  local grpc_file
  local grpc_root

  (
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel build --nobuild //fourward_dvaas:${FOURWARD_DVAAS_HELPER_BINARY} >/dev/null
  )
  output_base="$(
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel info output_base
  )"
  grpc_file="$(
    find "${output_base}/external" \
      -path '*com_github_grpc_grpc/src/core/lib/promise/detail/basic_seq.h' \
      -print -quit
  )"
  if [[ -n "${grpc_file}" ]]; then
    if grep -q 'Traits::CallSeqFactory' "${grpc_file}"; then
      return
    fi
    grpc_root="${grpc_file}"
    grpc_root="$(dirname "$(dirname "$(dirname "$(dirname "$(dirname "$(dirname "${grpc_root}")")")")")")"
    # WORKAROUND: The pinned gRPC header uses `Traits::template CallSeqFactory`
    # even though `CallSeqFactory` is not a template. Apple clang rejects it.
    # Patch the transient Bazel external with a checked-in diff so the exact
    # workaround is visible and removable once the vendored dependency updates.
    apply_patch_file "${grpc_root}" "${PATCH_DIR}/grpc_basic_seq_darwin.patch"
  fi
}

apply_darwin_z3_patch() {
  local output_base
  local z3_configure
  local z3_root

  (
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel build --nobuild //fourward_dvaas:${FOURWARD_DVAAS_HELPER_BINARY} >/dev/null
  )
  output_base="$(
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel info output_base
  )"
  z3_configure="$(
    find "${output_base}/external" \
      -path '*/com_github_z3prover_z3/configure' \
      -print -quit
  )"
  if [[ -n "${z3_configure}" ]]; then
    z3_root="$(dirname "${z3_configure}")"
    # WORKAROUND: The vendored Z3 configure script defaults to `python`, but the
    # Darwin sandbox only guarantees `python3` in the standard toolchain paths.
    # Patch the transient Bazel external so the checked-in diff documents the
    # exact workaround and can be dropped once the dependency stops assuming a
    # `python` alias.
    apply_patch_file "${z3_root}" "${PATCH_DIR}/z3_configure_python3_darwin.patch"
    # WORKAROUND: The vendored Z3 Makefile generator assumes x86 SSE flags on
    # Darwin. Apple Silicon clang rejects `-msse*`, so patch the transient
    # external to skip those flags on arm64 Darwin until upstream handles that
    # host architecture correctly.
    if ! grep -q 'ARM64-DARWIN' "${z3_root}/scripts/mk_util.py"; then
      apply_patch_file "${z3_root}" "${PATCH_DIR}/z3_mk_util_arm64_darwin.patch"
    fi
    if ! grep -q "config.write('AR=ar" "${z3_root}/scripts/mk_util.py"; then
      apply_patch_file "${z3_root}" "${PATCH_DIR}/z3_mk_util_ar_darwin.patch"
    fi
  fi
}

apply_darwin_gmp_patch() {
  local output_base
  local gmp_configure

  (
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel build --nobuild //fourward_dvaas:${FOURWARD_DVAAS_HELPER_BINARY} >/dev/null
  )
  output_base="$(
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel info output_base
  )"
  gmp_configure="$(
    find "${output_base}/external" \
      -path '*/com_gnu_gmp/configure' \
      -print -quit
  )"
  if [[ -n "${gmp_configure}" ]]; then
    # WORKAROUND: The vendored GMP configure script trusts the foreign-cc
    # wrapper's injected `AR=/usr/bin/libtool`, but GMP's static archive build
    # path expects GNU `ar` semantics there. Patch the transient external so
    # Darwin arm64 rewrites that one bad tool choice to `/usr/bin/ar`.
    apply_patch_file "$(dirname "${gmp_configure}")" "${PATCH_DIR}/gmp_configure_ar_darwin.patch"
  fi
}

build_dvaas_helper() {
  if [[ "${OS_NAME}" == "Darwin" ]]; then
    apply_darwin_grpc_patch
    apply_darwin_z3_patch
    apply_darwin_gmp_patch
  fi
  (
    cd "${SONIC_PINS_DIR}" &&
      run_sonic_pins_bazel build //fourward_dvaas:${FOURWARD_DVAAS_HELPER_BINARY}
  )
}

run_dvaas_helper() {
  (
    cd "${SONIC_PINS_DIR}" &&
      TEST_UNDECLARED_OUTPUTS_DIR="${ARTIFACT_DIR}" \
      TEST_TMPDIR="${ARTIFACT_DIR}" \
      "bazel-bin/fourward_dvaas/${FOURWARD_DVAAS_HELPER_BINARY}" \
        "localhost:${SUT_PORT}" "localhost:${CONTROL_PORT}" "${ARTIFACT_DIR}"
  )
}

run_dvaas_helper_with_gdb() {
    (
      cd "${SONIC_PINS_DIR}" &&
        TEST_UNDECLARED_OUTPUTS_DIR="${ARTIFACT_DIR}" \
        TEST_TMPDIR="${ARTIFACT_DIR}" \
        gdb -q -batch -ex run -ex bt --args \
          "bazel-bin/fourward_dvaas/${FOURWARD_DVAAS_HELPER_BINARY}" \
          "localhost:${SUT_PORT}" "localhost:${CONTROL_PORT}" "${ARTIFACT_DIR}"
    )
  }

build_fourward_targets
prepare_sonic_pins_checkout
start_testbed
build_dvaas_helper
if ! run_dvaas_helper; then
  if [[ "${DVAAS_POC_WRAP_GDB}" == "1" ]] && command -v gdb >/dev/null 2>&1; then
    run_dvaas_helper_with_gdb
  else
    exit 1
  fi
fi

echo "Artifacts written to ${ARTIFACT_DIR}"
