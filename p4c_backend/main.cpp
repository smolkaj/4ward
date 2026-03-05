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

// p4c-4ward: the 4ward p4c backend.
//
// Compiles a P4 program to a PipelineConfig proto binary suitable for loading
// into the 4ward simulator. Usage:
//
//   p4c-4ward --arch v1model -o output.txtpb input.p4

#include <map>
#include <set>
#include <string>

#include "control-plane/p4RuntimeSerializer.h"
#include "frontends/common/applyOptionsPragmas.h"
#include "frontends/common/parseInput.h"
#include "frontends/common/parser_options.h"
#include "frontends/p4/evaluator/evaluator.h"
#include "frontends/p4/frontend.h"
#include "ir/ir.h"
#include "lib/compile_context.h"
#include "lib/crash.h"
#include "lib/error.h"
#include "lib/gc.h"
#include "lib/log.h"
#include "lib/nullstream.h"
#include "p4c_backend/backend.h"
#include "p4c_backend/midend.h"
#include "p4c_backend/options.h"

using namespace P4;

// p4c's P4Runtime serializer auto-assigns descending priorities (N, N-1, …, 1)
// to const table entries, ignoring @priority annotations.  BMv2's JSON backend
// respects those annotations, so the STF corpus expects @priority(1) = highest
// precedence.  Fix up by inverting the auto-assigned priorities for any const
// table that carries @priority annotations.
static void fixConstEntryPriorities(const IR::P4Program* program,
                                    const p4::config::v1::P4Info& p4Info,
                                    p4::v1::WriteRequest* entries) {
  // 1. Walk IR to find tables whose const entries have @priority.
  std::set<std::string> tablesWithPriority;
  forAllMatching<IR::P4Table>(program, [&](const IR::P4Table* table) {
    const auto* el = table->getEntries();
    if (!el) return;
    for (const auto* e : el->entries) {
      if (e->getAnnotation("priority"_cs)) {
        auto name = std::string(table->externalName().c_str());
        if (!name.empty() && name[0] == '.') name = name.substr(1);
        tablesWithPriority.insert(name);
        return;
      }
    }
  });
  if (tablesWithPriority.empty()) return;

  // 2. Resolve affected table names to p4info table IDs.
  std::set<uint32_t> affectedIds;
  for (const auto& t : p4Info.tables()) {
    if (tablesWithPriority.contains(t.preamble().name())) {
      affectedIds.insert(t.preamble().id());
    }
  }

  // 4. Count entries per affected table, then invert priorities.
  std::map<uint32_t, int32_t> entryCount;
  for (const auto& u : entries->updates()) {
    if (!u.entity().has_table_entry()) continue;
    auto tid = u.entity().table_entry().table_id();
    if (affectedIds.contains(tid)) entryCount[tid]++;
  }
  for (auto& u : *entries->mutable_updates()) {
    if (!u.entity().has_table_entry()) continue;
    auto* te = u.mutable_entity()->mutable_table_entry();
    auto it = entryCount.find(te->table_id());
    if (it == entryCount.end()) continue;
    te->set_priority(it->second + 1 - te->priority());
  }
}

int main(int argc, char* const argv[]) {
  setup_gc_logging();
  setup_signals();

  AutoCompileContext autoContext(
      new P4CContextWithOptions<FourWard::FourWardOptions>);
  auto& options =
      P4CContextWithOptions<FourWard::FourWardOptions>::get().options();
  options.langVersion = CompilerOptions::FrontendVersion::P4_16;

  if (options.process(argc, argv) != nullptr) {
    options.setInputFile();
  }
  if (::P4::errorCount() > 0) return 1;

  const IR::P4Program* program = parseP4File(options);
  if (program == nullptr || ::P4::errorCount() > 0) return 1;

  FrontEnd frontend;
  program = frontend.run(options, program);
  if (program == nullptr || ::P4::errorCount() > 0) return 1;

  // Generate p4info from the post-frontend program (before midend
  // simplifications strip out information needed for the control-plane API).
  auto p4Runtime = generateP4Runtime(program, "v1model"_cs);
  if (::P4::errorCount() > 0) return 1;

  auto entries = *p4Runtime.entries;
  fixConstEntryPriorities(program, *p4Runtime.p4Info, &entries);

  FourWard::MidEnd midend(options);
  const IR::ToplevelBlock* toplevel = midend.process(program);
  if (toplevel == nullptr || ::P4::errorCount() > 0) return 1;

  FourWard::FourWardBackend backend(options, midend.refMap, midend.typeMap);
  // setP4Info must come before process so emitTable can look up match field
  // IDs.
  backend.setP4Info(*p4Runtime.p4Info);
  backend.setStaticEntries(entries);
  backend.process(toplevel);

  if (!backend.writePipelineConfig()) return 1;
  return ::P4::errorCount() > 0 ? 1 : 0;
}
