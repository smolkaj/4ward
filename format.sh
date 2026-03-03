#!/usr/bin/env zsh
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

WORKSPACE=$(bazel info workspace)

# Only files with these extensions will be formatted by clang-format.
CLANG_FORMAT_EXTENSIONS="cc|h|proto"

# Run clang-format.
find . -not -path "./third_party/**" \
       -not -path "./.claude/*" \
  | egrep "\.(${CLANG_FORMAT_EXTENSIONS})\$" \
  | xargs clang-format --verbose -style=google -i

# Run buildifier on Starlark files, excluding generated symlinks and .claude/.
BZL_SOURCES=(${(f)"$(find "${WORKSPACE}" \
  -path "${WORKSPACE}/bazel-*" -prune -o \
  -path "${WORKSPACE}/.claude" -prune -o \
  \( -name "*.bazel" -o -name "*.bzl" \) \
  -print | sort)"})
[[ ${#BZL_SOURCES[@]} -gt 0 ]] && \
  bazel run -- @buildifier_prebuilt//:buildifier --lint=fix "${BZL_SOURCES[@]}"

# Run ktfmt on Kotlin sources (Google style, matching our style guide).
KT_SOURCES=(${(f)"$(find "${WORKSPACE}" \
  -path "${WORKSPACE}/bazel-*" -prune -o \
  -path "${WORKSPACE}/.claude" -prune -o \
  -name "*.kt" -print | sort)"})
[[ ${#KT_SOURCES[@]} -gt 0 ]] && \
  bazel run //:ktfmt -- --google-style "${KT_SOURCES[@]}"
