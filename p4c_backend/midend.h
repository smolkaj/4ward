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

#ifndef P4C_BACKEND_MIDEND_H_
#define P4C_BACKEND_MIDEND_H_

#include "frontends/common/resolveReferences/referenceMap.h"
#include "frontends/p4/evaluator/evaluator.h"
#include "frontends/p4/typeMap.h"
#include "ir/ir.h"
#include "midend/actionSynthesis.h"
#include "midend/complexComparison.h"
#include "midend/eliminateInvalidHeaders.h"
#include "midend/eliminateTuples.h"
#include "midend/flattenHeaders.h"
#include "midend/local_copyprop.h"
#include "midend/simplifyKey.h"
#include "midend/simplifySelectCases.h"
#include "midend/simplifySelectList.h"
#include "p4c_backend/options.h"

namespace P4::FourWard {

// MidEnd runs the standard p4c midend passes that simplify the IR before
// the 4ward backend emits the proto. The passes here mirror what other
// reference backends (p4test, BMv2) do: type resolution, constant folding,
// header stack simplification, etc.
class MidEnd : public PassManager {
 public:
  ReferenceMap refMap;
  TypeMap typeMap;
  IR::ToplevelBlock* toplevel = nullptr;

  explicit MidEnd(FourWardOptions& options);

  IR::ToplevelBlock* process(const IR::P4Program*& program) {
    program = program->apply(*this);
    return toplevel;
  }
};

}  // namespace P4::FourWard

#endif  // P4C_BACKEND_MIDEND_H_
