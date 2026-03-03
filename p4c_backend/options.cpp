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
        [this](const char *arg) {
            outputFile = arg;
            return true;
        },
        "write the PipelineConfig proto binary to <file> (default: <input>.pb)");
}

}  // namespace P4::FourWard
