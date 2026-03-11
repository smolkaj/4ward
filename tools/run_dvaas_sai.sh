#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export FOURWARD_DVAAS_PIPELINE_TARGET=//e2e_tests/sai_p4:sai_middleblock_pb
export FOURWARD_DVAAS_PIPELINE_TXTPB=e2e_tests/sai_p4/sai_middleblock.txtpb
export FOURWARD_DVAAS_HELPER_BINARY=validate_dataplane_sai

"${ROOT}/tools/run_dvaas_poc.sh"
