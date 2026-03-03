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

#ifndef P4C_BACKEND_BACKEND_H_
#define P4C_BACKEND_BACKEND_H_

#include <string>

#include "frontends/common/resolveReferences/referenceMap.h"
#include "frontends/p4/typeMap.h"
#include "ir.pb.h"
#include "ir/ir.h"
#include "ir/visitor.h"
#include "p4c_backend/options.h"

namespace P4::FourWard {

// -----------------------------------------------------------------------
// IR-to-proto converters
//
// Each Emit* function converts a p4c IR node to the corresponding proto
// message. They are free functions (not a visitor) to keep the code
// straightforward: we call them explicitly from the main backend pass.
// -----------------------------------------------------------------------

fourward::ir::v1::Type EmitType(const IR::Type* type, const TypeMap& typeMap);

fourward::ir::v1::Expr EmitExpr(const IR::Expression* expr,
                                const TypeMap& typeMap);

fourward::ir::v1::Stmt EmitStmt(const IR::StatOrDecl* stmt,
                                const TypeMap& typeMap);

fourward::ir::v1::BlockStmt EmitBlock(const IR::BlockStatement* block,
                                      const TypeMap& typeMap);

// -----------------------------------------------------------------------
// FourWardBackend
//
// The top-level backend pass. Walks the p4c ToplevelBlock after all midend
// passes and populates a PipelineConfig proto.
// -----------------------------------------------------------------------
class FourWardBackend : public Inspector {
 public:
  FourWardBackend(const FourWardOptions& options, const ReferenceMap& refMap,
                  const TypeMap& typeMap);

  // Called by the pass manager to trigger IR traversal.
  void process(const IR::ToplevelBlock* toplevel);

  // Injects the p4info produced by the P4Runtime serialiser into the config.
  void setP4Info(p4::config::v1::P4Info p4info);

  // Writes the accumulated PipelineConfig proto to the output file.
  // Returns true on success.
  bool writePipelineConfig() const;

 private:
  const FourWardOptions& options_;
  const ReferenceMap& refMap_;
  const TypeMap& typeMap_;

  fourward::ir::v1::PipelineConfig pipelineConfig_;
  fourward::ir::v1::P4BehavioralConfig* behavioral_;

  void emitTypeDecls(const IR::P4Program* program);
  void emitParser(const IR::P4Parser* parser);
  void emitControl(const IR::P4Control* control);
  void emitAction(const IR::P4Action* action,
                  fourward::ir::v1::ActionDecl* out);
  void emitArchitecture(const IR::ToplevelBlock* toplevel);

  std::string outputFilePath() const;
};

}  // namespace P4::FourWard

#endif  // P4C_BACKEND_BACKEND_H_
