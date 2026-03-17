/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef P4C_BACKEND_OPTIONS_H_
#define P4C_BACKEND_OPTIONS_H_

#include <cstdint>
#include <optional>
#include <string>

#include "frontends/common/options.h"

namespace P4::FourWard {

class FourWardOptions : public CompilerOptions {
 public:
  // Output file path. Extension determines encoding:
  //   .txtpb → text-format protobuf
  //   .binpb → binary protobuf
  std::optional<std::string> outputFile;

  // Output message type.
  //   "native" (default): 4ward-native PipelineConfig.
  //   "p4runtime": P4Runtime ForwardingPipelineConfig (Pipeline proto
  //                serialized into p4_device_config bytes).
  enum class Format : std::uint8_t { kNative, kP4runtime };
  Format format = Format::kNative;

  FourWardOptions();
};

}  // namespace P4::FourWard

#endif  // P4C_BACKEND_OPTIONS_H_
