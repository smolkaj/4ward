#!/usr/bin/env bash
# Copyright 2020 The P4-Constraints Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0

# Formats source files according to Google's style guide. Requires clang-format.
# Uses `git ls-files` to respect .gitignore as the single source of truth for
# which files belong to the project — no manual excludes needed.
#
# With --check, reports unformatted files and exits non-zero instead of
# modifying them.
#
# Usage:
#   ./tools/format.sh          # fix formatting in-place
#   ./tools/format.sh --check  # check only (used by lint.sh)

set -euo pipefail

CHECK=0
if [[ "${1:-}" == "--check" ]]; then
  CHECK=1
fi

cd "$(git rev-parse --show-toplevel)" || exit 1
REPO_ROOT=$(pwd)

if [[ "$CHECK" == "1" ]]; then
  rc=0

  git ls-files '*.cpp' '*.h' '*.proto' \
    | xargs clang-format --style=google --dry-run -Werror || rc=1

  BZL_SOURCES=()
  while IFS= read -r f; do BZL_SOURCES+=("$f"); done < <(git ls-files '*.bazel' '*.bzl')
  if [[ ${#BZL_SOURCES[@]} -gt 0 ]]; then
    bazel run -- @buildifier_prebuilt//:buildifier --mode=check --lint=warn \
      "${BZL_SOURCES[@]/#/$REPO_ROOT/}" || rc=1
  fi

  KT_SOURCES=()
  while IFS= read -r f; do KT_SOURCES+=("$f"); done < <(git ls-files '*.kt')
  if [[ ${#KT_SOURCES[@]} -gt 0 ]]; then
    bazel run //:ktfmt -- --google-style --dry-run --set-exit-if-changed \
      "${KT_SOURCES[@]/#/$REPO_ROOT/}" || rc=1
  fi

  exit $rc
fi

# Run clang-format.
git ls-files '*.cpp' '*.h' '*.proto' | xargs clang-format --verbose -style=google -i

# Run buildifier on Starlark files.
# Absolute paths are needed because `bazel run` changes the working directory.
BZL_SOURCES=()
while IFS= read -r f; do BZL_SOURCES+=("$f"); done < <(git ls-files '*.bazel' '*.bzl')
[[ ${#BZL_SOURCES[@]} -gt 0 ]] && \
  bazel run -- @buildifier_prebuilt//:buildifier --lint=fix "${BZL_SOURCES[@]/#/$REPO_ROOT/}"

# Run ktfmt on Kotlin sources (Google style, matching our style guide).
# Absolute paths are needed because `bazel run` changes the working directory.
KT_SOURCES=()
while IFS= read -r f; do KT_SOURCES+=("$f"); done < <(git ls-files '*.kt')
[[ ${#KT_SOURCES[@]} -gt 0 ]] && \
  bazel run //:ktfmt -- --google-style "${KT_SOURCES[@]/#/$REPO_ROOT/}"
