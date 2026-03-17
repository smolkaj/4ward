/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

#include "p4c_backend/options.h"

namespace P4::FourWard {

FourWardOptions::FourWardOptions() {
  registerOption(
      "-o", "file",
      [this](const char* arg) {
        outputFile = arg;
        return true;
      },
      "write the compiled pipeline to <file> "
      "(.txtpb for text-format, .pb.bin for binary)");
  registerOption(
      "--format", "native|p4runtime",
      [this](const char* arg) {
        std::string fmt(arg);
        if (fmt == "native") {
          format = Format::NATIVE;
        } else if (fmt == "p4runtime") {
          format = Format::P4RUNTIME;
        } else {
          ::P4::error(
              "4ward: unknown format '%1%' (expected 'native' or 'p4runtime')",
              fmt);
          return false;
        }
        return true;
      },
      "output message type: 'native' (default, 4ward PipelineConfig) "
      "or 'p4runtime' (P4Runtime ForwardingPipelineConfig)");
}

}  // namespace P4::FourWard
