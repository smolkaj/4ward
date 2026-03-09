#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ROOT}/.tmp/dvaas_poc_artifacts"
SONIC_PINS_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sonic-pins.XXXXXX")"
SONIC_PINS_REF="${SONIC_PINS_REF:-6052c041f299fdf8fad50236caf15483e95b56d4}"
FOURWARD_BAZEL_CONFIG="${FOURWARD_BAZEL_CONFIG:-}"
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

bazel build "${BAZEL_ARGS[@]}" \
  //p4runtime:p4runtime_server \
  //e2e_tests/dvaas_poc:dvaas_poc_pb

git clone https://github.com/sonic-net/sonic-pins.git "${SONIC_PINS_DIR}"
(cd "${SONIC_PINS_DIR}" && git checkout --detach "${SONIC_PINS_REF}")
mkdir -p "${SONIC_PINS_DIR}/fourward_dvaas"
cp "${ROOT}/tools/dvaas_overlay/BUILD.bazel" "${SONIC_PINS_DIR}/fourward_dvaas/BUILD.bazel"
cp "${ROOT}/tools/dvaas_overlay/validate_dataplane_poc.cc" "${SONIC_PINS_DIR}/fourward_dvaas/validate_dataplane_poc.cc"

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

(cd "${SONIC_PINS_DIR}" && bazel --bazelrc=/dev/null build //fourward_dvaas:validate_dataplane_poc)
(cd "${SONIC_PINS_DIR}" && bazel-bin/fourward_dvaas/validate_dataplane_poc \
  "localhost:${SUT_PORT}" "localhost:${CONTROL_PORT}" "${ARTIFACT_DIR}")

echo "Artifacts written to ${ARTIFACT_DIR}"
