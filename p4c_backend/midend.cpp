/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

#include "p4c_backend/midend.h"

#include "frontends/common/constantFolding.h"
#include "frontends/common/resolveReferences/resolveReferences.h"
#include "frontends/p4/evaluator/evaluator.h"
#include "frontends/p4/simplify.h"
#include "frontends/p4/typeChecking/typeChecker.h"
#include "frontends/p4/unusedDeclarations.h"
#include "midend/complexComparison.h"
#include "midend/copyStructures.h"
#include "midend/eliminateInvalidHeaders.h"
#include "midend/eliminateTuples.h"
#include "midend/flattenHeaders.h"
#include "midend/local_copyprop.h"
#include "midend/parserUnroll.h"
#include "midend/simplifyKey.h"
#include "midend/simplifySelectCases.h"
#include "midend/simplifySelectList.h"

namespace P4::FourWard {

MidEnd::MidEnd(FourWardOptions& options) {
  auto* evaluator = new P4::EvaluatorPass(&refMap, &typeMap);

  addPasses({
      new P4::ResolveReferences(&refMap),
      new P4::TypeInference(&typeMap, false),
      new P4::ConstantFolding(&typeMap),
      new P4::SimplifyControlFlow(&typeMap, true),
      new P4::FlattenHeaders(&typeMap),
      new P4::EliminateTuples(&typeMap),
      new P4::CopyStructures(&typeMap, /* errorOnMethodCall= */ false),
      new P4::SimplifyComparisons(&typeMap),
      new P4::LocalCopyPropagation(&typeMap),
      new P4::SimplifyKey(
          &typeMap,
          new P4::OrPolicy(new P4::IsLikeLeftValue, new P4::IsValid(&typeMap))),
      new P4::SimplifySelectCases(&typeMap, false),
      new P4::SimplifySelectList(&typeMap),
      evaluator,
      new VisitFunctor(
          [this, evaluator]() { toplevel = evaluator->getToplevelBlock(); }),
  });
}

}  // namespace P4::FourWard
