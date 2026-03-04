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

#include "p4c_backend/backend.h"

#include <filesystem>
#include <fstream>
#include <string>

#include "frontends/p4/coreLibrary.h"
#include "frontends/p4/enumInstance.h"
#include "google/protobuf/text_format.h"
#include "lib/error.h"
#include "lib/log.h"

namespace P4::FourWard {

// =============================================================================
// Type emission
// =============================================================================

fourward::ir::v1::Type FourWardBackend::emitType(const IR::Type* type) {
  fourward::ir::v1::Type out;

  if (const auto* bits = type->to<IR::Type_Bits>()) {
    if (bits->isSigned) {
      out.mutable_signed_int()->set_width(bits->size);
    } else {
      out.mutable_bit()->set_width(bits->size);
    }
  } else if (const auto* vb = type->to<IR::Type_Varbits>()) {
    out.mutable_varbit()->set_max_width(vb->size);
  } else if (type->is<IR::Type_Boolean>()) {
    out.set_boolean(true);
  } else if (const auto* tn = type->to<IR::Type_Name>()) {
    // Resolve typedef aliases (e.g. `typedef bit<48> macAddr_t`) to their
    // underlying concrete types so the simulator sees bit widths instead of
    // opaque typedef names.
    const auto* decl = refMap_.getDeclaration(tn->path, false);
    if (const auto* td = decl ? decl->to<IR::Type_Typedef>() : nullptr) {
      return emitType(td->type);
    }
    out.set_named(tn->path->name.name.c_str());
  } else if (const auto* hdr = type->to<IR::Type_Header>()) {
    out.set_named(hdr->name.name.c_str());
  } else if (const auto* st = type->to<IR::Type_Struct>()) {
    out.set_named(st->name.name.c_str());
  } else if (const auto* stack = type->to<IR::Type_Array>()) {
    auto* hs = out.mutable_header_stack();
    if (const auto* elemType = stack->elementType->to<IR::Type_Name>()) {
      hs->set_element_type(elemType->path->name.name.c_str());
    }
    if (const auto* size = stack->size->to<IR::Constant>()) {
      hs->set_size(size->asInt());
    }
  } else if (type->is<IR::Type_Error>()) {
    out.set_error(true);
  } else {
    LOG1("WARNING: unhandled type " << type->node_type_name()
                                    << "; emitting as unnamed");
  }
  return out;
}

// =============================================================================
// Expression emission
// =============================================================================

// Returns true if `expr` is a PathExpression referring to a P4Table, and if so
// sets `*tableName` to the table's original (pre-midend-rename) name.
static bool isTableApply(const IR::Expression* expr, const ReferenceMap& refMap,
                         std::string* tableName) {
  const auto* mc = expr->to<IR::MethodCallExpression>();
  if (!mc) return false;
  const auto* mem = mc->method->to<IR::Member>();
  if (!mem || mem->member != "apply") return false;
  const auto* pe = mem->expr->to<IR::PathExpression>();
  if (!pe) return false;
  const auto* decl = refMap.getDeclaration(pe->path);
  if (!decl || !decl->is<IR::P4Table>()) return false;
  if (tableName)
    *tableName = decl->to<IR::P4Table>()->name.originalName.c_str();
  return true;
}

fourward::ir::v1::Expr FourWardBackend::emitExpr(const IR::Expression* expr) {
  fourward::ir::v1::Expr out;

  if (const auto* cnst = expr->to<IR::Constant>()) {
    auto* lit = out.mutable_literal();
    // Use big_integer for values that don't fit in 64 bits.
    if (cnst->fitsUint64()) {
      lit->set_integer(cnst->asUint64());
    } else {
      // Serialise as big-endian bytes using boost::multiprecision::export_bits.
      std::string bytes;
      auto v = cnst->value;
      while (v != 0) {
        bytes.push_back(static_cast<char>(static_cast<uint8_t>(v & 0xFF)));
        v >>= 8;
      }
      std::reverse(bytes.begin(), bytes.end());
      lit->set_big_integer(bytes);
    }
  } else if (const auto* b = expr->to<IR::BoolLiteral>()) {
    out.mutable_literal()->set_boolean(b->value);
  } else if (const auto* pe = expr->to<IR::PathExpression>()) {
    out.mutable_name_ref()->set_name(pe->path->name.name.c_str());
  } else if (const auto* mem = expr->to<IR::Member>()) {
    std::string tableName;
    if (isTableApply(mem->expr, refMap_, &tableName)) {
      if (mem->member == "action_run") {
        // Switch subject: no type annotation needed.
        out.mutable_table_apply()->set_table_name(tableName);
        return out;
      }
      // hit/miss: type annotation (bool) is added by the common block below.
      auto* ta = out.mutable_table_apply();
      ta->set_table_name(tableName);
      if (mem->member == "hit" || mem->member == "miss") {
        ta->set_access_kind(mem->member == "hit"
                                ? fourward::ir::v1::TableApplyExpr::HIT
                                : fourward::ir::v1::TableApplyExpr::MISS);
      }
    } else if (mem->expr->is<IR::TypeNameExpression>()) {
      // Qualified enum/error member access: `error.NoError` or `MyEnum.Val`.
      // The base TypeNameExpression has no runtime value of its own; the whole
      // expression reduces to a compile-time literal.
      const auto* exprType = typeMap_.getType(expr);
      if (exprType && exprType->is<IR::Type_Error>()) {
        // error.X → literal { error_member: "X" }
        out.mutable_literal()->set_error_member(mem->member.name.c_str());
      } else if (const auto* serEnum =
                     exprType ? exprType->to<IR::Type_SerEnum>() : nullptr) {
        // SerializableEnum.X → literal { integer: X.value }
        // The underlying integer value is stored in the SerEnumMember after
        // constant folding by the frontend.
        const auto* decl = serEnum->getDeclByName(mem->member.name);
        const auto* sem = decl ? decl->to<IR::SerEnumMember>() : nullptr;
        const auto* cnst = sem ? sem->value->to<IR::Constant>() : nullptr;
        if (cnst) {
          auto* lit = out.mutable_literal();
          if (cnst->fitsUint64()) {
            lit->set_integer(cnst->asUint64());
          } else {
            std::string bytes;
            auto v = cnst->value;
            while (v != 0) {
              bytes.push_back(
                  static_cast<char>(static_cast<uint8_t>(v & 0xFF)));
              v >>= 8;
            }
            std::reverse(bytes.begin(), bytes.end());
            lit->set_big_integer(bytes);
          }
        } else {
          LOG1("WARNING: could not resolve SerEnum member value for "
               << mem->member.name);
        }
      } else {
        LOG1("WARNING: unhandled TypeNameExpression member "
             << mem->member.name);
      }
    } else {
      auto* fa = out.mutable_field_access();
      *fa->mutable_expr() = emitExpr(mem->expr);
      fa->set_field_name(mem->member.name.c_str());
    }
  } else if (const auto* slice = expr->to<IR::Slice>()) {
    auto* s = out.mutable_slice();
    *s->mutable_expr() = emitExpr(slice->e0);
    s->set_hi(slice->getH());
    s->set_lo(slice->getL());
  } else if (const auto* cat = expr->to<IR::Concat>()) {
    *out.mutable_concat()->mutable_left() = emitExpr(cat->left);
    *out.mutable_concat()->mutable_right() = emitExpr(cat->right);
  } else if (const auto* cast = expr->to<IR::Cast>()) {
    *out.mutable_cast()->mutable_target_type() = emitType(cast->destType);
    *out.mutable_cast()->mutable_expr() = emitExpr(cast->expr);
  } else if (const auto* ai = expr->to<IR::ArrayIndex>()) {
    // IR::ArrayIndex is a subclass of IR::Operation_Binary; check it first so
    // it maps to array_index rather than being caught as an unknown binary op.
    auto* a = out.mutable_array_index();
    *a->mutable_expr() = emitExpr(ai->left);
    *a->mutable_index() = emitExpr(ai->right);
  } else if (const auto* binop = expr->to<IR::Operation_Binary>()) {
    auto* b = out.mutable_binary_op();
    *b->mutable_left() = emitExpr(binop->left);
    *b->mutable_right() = emitExpr(binop->right);

    if (binop->is<IR::Add>())
      b->set_op(fourward::ir::v1::BinaryOperator::ADD);
    else if (binop->is<IR::Sub>())
      b->set_op(fourward::ir::v1::BinaryOperator::SUB);
    else if (binop->is<IR::Mul>())
      b->set_op(fourward::ir::v1::BinaryOperator::MUL);
    else if (binop->is<IR::Div>())
      b->set_op(fourward::ir::v1::BinaryOperator::DIV);
    else if (binop->is<IR::Mod>())
      b->set_op(fourward::ir::v1::BinaryOperator::MOD);
    else if (binop->is<IR::AddSat>())
      b->set_op(fourward::ir::v1::BinaryOperator::ADD_SAT);
    else if (binop->is<IR::SubSat>())
      b->set_op(fourward::ir::v1::BinaryOperator::SUB_SAT);
    else if (binop->is<IR::BAnd>())
      b->set_op(fourward::ir::v1::BinaryOperator::BIT_AND);
    else if (binop->is<IR::BOr>())
      b->set_op(fourward::ir::v1::BinaryOperator::BIT_OR);
    else if (binop->is<IR::BXor>())
      b->set_op(fourward::ir::v1::BinaryOperator::BIT_XOR);
    else if (binop->is<IR::Shl>())
      b->set_op(fourward::ir::v1::BinaryOperator::SHL);
    else if (binop->is<IR::Shr>())
      b->set_op(fourward::ir::v1::BinaryOperator::SHR);
    else if (binop->is<IR::Equ>())
      b->set_op(fourward::ir::v1::BinaryOperator::EQ);
    else if (binop->is<IR::Neq>())
      b->set_op(fourward::ir::v1::BinaryOperator::NEQ);
    else if (binop->is<IR::Lss>())
      b->set_op(fourward::ir::v1::BinaryOperator::LT);
    else if (binop->is<IR::Grt>())
      b->set_op(fourward::ir::v1::BinaryOperator::GT);
    else if (binop->is<IR::Leq>())
      b->set_op(fourward::ir::v1::BinaryOperator::LE);
    else if (binop->is<IR::Geq>())
      b->set_op(fourward::ir::v1::BinaryOperator::GE);
    else if (binop->is<IR::LAnd>())
      b->set_op(fourward::ir::v1::BinaryOperator::AND);
    else if (binop->is<IR::LOr>())
      b->set_op(fourward::ir::v1::BinaryOperator::OR);
    else
      LOG1("WARNING: unhandled binary operator: " << binop->node_type_name());
  } else if (const auto* unop = expr->to<IR::Operation_Unary>()) {
    auto* u = out.mutable_unary_op();
    *u->mutable_expr() = emitExpr(unop->expr);
    if (unop->is<IR::Neg>())
      u->set_op(fourward::ir::v1::UnaryOperator::NEG);
    else if (unop->is<IR::Cmpl>())
      u->set_op(fourward::ir::v1::UnaryOperator::BIT_NOT);
    else if (unop->is<IR::LNot>())
      u->set_op(fourward::ir::v1::UnaryOperator::NOT);
    else
      LOG1("WARNING: unhandled unary operator: " << unop->node_type_name());
  } else if (const auto* mux = expr->to<IR::Mux>()) {
    auto* m = out.mutable_mux();
    *m->mutable_condition() = emitExpr(mux->e0);
    *m->mutable_then_expr() = emitExpr(mux->e1);
    *m->mutable_else_expr() = emitExpr(mux->e2);
  } else if (const auto* mc = expr->to<IR::MethodCallExpression>()) {
    // Special case: table.apply() — emit as TableApplyExpr.
    std::string tableName;
    if (isTableApply(expr, refMap_, &tableName)) {
      out.mutable_table_apply()->set_table_name(tableName);
      return out;  // no type annotation for TableApplyExpr
    }

    auto* call = out.mutable_method_call();
    // The method is typically a Member expression: target.method
    if (const auto* mem = mc->method->to<IR::Member>()) {
      *call->mutable_target() = emitExpr(mem->expr);
      call->set_method(mem->member.name.c_str());
    } else {
      *call->mutable_target() = emitExpr(mc->method);
      call->set_method("__call__");
    }
    for (const auto* arg : *mc->arguments) {
      *call->add_args() = emitExpr(arg->expression);
    }
  } else {
    LOG1("WARNING: unhandled expression " << expr->node_type_name());
  }

  // Always populate the type annotation.
  if (const auto* type = typeMap_.getType(expr)) {
    *out.mutable_type() = emitType(type);
  }

  return out;
}

// =============================================================================
// Statement emission
// =============================================================================

fourward::ir::v1::Stmt FourWardBackend::emitStmt(const IR::StatOrDecl* node) {
  fourward::ir::v1::Stmt out;

  if (const auto* assign = node->to<IR::AssignmentStatement>()) {
    auto* a = out.mutable_assignment();
    *a->mutable_lhs() = emitExpr(assign->left);
    *a->mutable_rhs() = emitExpr(assign->right);
  } else if (const auto* mc = node->to<IR::MethodCallStatement>()) {
    *out.mutable_method_call()->mutable_call() = emitExpr(mc->methodCall);
  } else if (const auto* ifst = node->to<IR::IfStatement>()) {
    auto* i = out.mutable_if_stmt();
    *i->mutable_condition() = emitExpr(ifst->condition);
    // SimplifyControlFlow normally wraps branches in BlockStatements, but some
    // downstream passes (e.g. LocalCopyPropagation) may produce bare
    // statements.
    auto emitBranch =
        [&](const IR::Statement* stmt) -> fourward::ir::v1::BlockStmt {
      if (const auto* blk = stmt->to<IR::BlockStatement>())
        return emitBlock(blk);
      fourward::ir::v1::BlockStmt branch;
      // IR::EmptyStatement (produced by RemoveReturns for void-return branches)
      // has no IR representation; skip it to avoid an empty Stmt{} in the
      // output.
      if (!stmt->is<IR::EmptyStatement>()) *branch.add_stmts() = emitStmt(stmt);
      return branch;
    };
    *i->mutable_then_block() = emitBranch(ifst->ifTrue);
    if (ifst->ifFalse) {
      *i->mutable_else_block() = emitBranch(ifst->ifFalse);
    }
  } else if (const auto* sw = node->to<IR::SwitchStatement>()) {
    auto* s = out.mutable_switch_stmt();
    *s->mutable_subject() = emitExpr(sw->expression);
    for (const auto* c : sw->cases) {
      if (c->label->is<IR::DefaultExpression>()) {
        if (const auto* b = c->statement->to<IR::BlockStatement>()) {
          *s->mutable_default_block() = emitBlock(b);
        }
      } else {
        auto* sc = s->add_cases();
        if (const auto* pe = c->label->to<IR::PathExpression>()) {
          // Use the original (pre-rename) action name to match p4info aliases.
          sc->set_action_name(pe->path->name.originalName.c_str());
        }
        if (c->statement) {
          if (const auto* b = c->statement->to<IR::BlockStatement>()) {
            *sc->mutable_block() = emitBlock(b);
          }
        }
      }
    }
  } else if (const auto* blk = node->to<IR::BlockStatement>()) {
    *out.mutable_block() = emitBlock(blk);
  } else if (node->is<IR::ExitStatement>()) {
    out.mutable_exit();
  } else if (const auto* ret = node->to<IR::ReturnStatement>()) {
    if (ret->expression) {
      *out.mutable_return_stmt()->mutable_value() = emitExpr(ret->expression);
    } else {
      out.mutable_return_stmt();
    }
  } else {
    LOG1("WARNING: unhandled statement " << node->node_type_name());
  }
  return out;
}

fourward::ir::v1::BlockStmt FourWardBackend::emitBlock(
    const IR::BlockStatement* block) {
  fourward::ir::v1::BlockStmt out;
  for (const auto* stmt : block->components) {
    *out.add_stmts() = emitStmt(stmt);
  }
  return out;
}

// =============================================================================
// FourWardBackend
// =============================================================================

FourWardBackend::FourWardBackend(const FourWardOptions& options,
                                 const ReferenceMap& refMap,
                                 const TypeMap& typeMap)
    : options_(options), refMap_(refMap), typeMap_(typeMap) {
  behavioral_ = pipelineConfig_.mutable_behavioral();
}

void FourWardBackend::process(const IR::ToplevelBlock* toplevel) {
  const auto* program = toplevel->getProgram();

  emitTypeDecls(program);
  emitArchitecture(toplevel);

  for (const auto* decl : *program->getDeclarations()) {
    if (const auto* parser = decl->to<IR::P4Parser>()) {
      emitParser(parser);
    } else if (const auto* control = decl->to<IR::P4Control>()) {
      emitControl(control);
    }
  }
}

void FourWardBackend::setP4Info(p4::config::v1::P4Info p4info) {
  *pipelineConfig_.mutable_p4info() = std::move(p4info);
}

void FourWardBackend::emitTypeDecls(const IR::P4Program* program) {
  for (const auto* decl : *program->getDeclarations()) {
    if (const auto* hdr = decl->to<IR::Type_Header>()) {
      auto* td = behavioral_->add_types();
      td->set_name(hdr->name.name.c_str());
      auto* hdecl = td->mutable_header();
      for (const auto* field : hdr->fields) {
        auto* fd = hdecl->add_fields();
        fd->set_name(field->name.name.c_str());
        *fd->mutable_type() = emitType(field->type);
      }
    } else if (const auto* st = decl->to<IR::Type_Struct>()) {
      auto* td = behavioral_->add_types();
      td->set_name(st->name.name.c_str());
      auto* sdecl = td->mutable_struct_();
      for (const auto* field : st->fields) {
        auto* fd = sdecl->add_fields();
        fd->set_name(field->name.name.c_str());
        *fd->mutable_type() = emitType(field->type);
      }
    } else if (const auto* serEnum = decl->to<IR::Type_SerEnum>()) {
      // Serializable enums (enum bit<N> E { ... }) can appear as header field
      // types; emit their underlying bit width so the simulator can compute
      // wire offsets.
      auto* td = behavioral_->add_types();
      td->set_name(serEnum->name.name.c_str());
      auto* edecl = td->mutable_enum_();
      if (const auto* bits = serEnum->type->to<IR::Type_Bits>()) {
        edecl->set_width(bits->size);
      }
      for (const auto* member : serEnum->members) {
        edecl->add_members(member->name.name.c_str());
      }
    }
    // Header unions: TODO
  }
}

void FourWardBackend::emitParser(const IR::P4Parser* parser) {
  auto* pd = behavioral_->add_parsers();
  pd->set_name(parser->name.name.c_str());

  for (const auto* param : parser->getApplyParameters()->parameters) {
    auto* p = pd->add_params();
    p->set_name(param->name.name.c_str());
    *p->mutable_type() = emitType(param->type);
    switch (param->direction) {
      case IR::Direction::In:
        p->set_direction(fourward::ir::v1::Direction::IN);
        break;
      case IR::Direction::Out:
        p->set_direction(fourward::ir::v1::Direction::OUT);
        break;
      case IR::Direction::InOut:
        p->set_direction(fourward::ir::v1::Direction::INOUT);
        break;
      default:
        break;
    }
  }

  for (const auto* state : parser->states) {
    auto* ps = pd->add_states();
    ps->set_name(state->name.name.c_str());

    for (const auto* stmt : state->components) {
      *ps->add_stmts() = emitStmt(stmt);
    }

    // accept/reject are terminal states with no selectExpression.
    if (!state->selectExpression) {
    } else if (const auto* sel =
                   state->selectExpression->to<IR::SelectExpression>()) {
      auto* selectTrans = ps->mutable_transition()->mutable_select();
      for (const auto* key : sel->select->components) {
        *selectTrans->add_keys() = emitExpr(key);
      }
      for (const auto* sc : sel->selectCases) {
        if (sc->keyset->is<IR::DefaultExpression>()) {
          selectTrans->set_default_state(sc->state->path->name.name.c_str());
          continue;
        }
        auto* c = selectTrans->add_cases();
        auto* k = c->add_keysets();
        *k->mutable_exact() = emitExpr(sc->keyset);
        c->set_next_state(sc->state->path->name.name.c_str());
      }
    } else if (const auto* path =
                   state->selectExpression->to<IR::PathExpression>()) {
      ps->mutable_transition()->set_next_state(path->path->name.name.c_str());
    }
  }
}

void FourWardBackend::emitControl(const IR::P4Control* control) {
  controlName_ = control->name.name.c_str();

  auto* cd = behavioral_->add_controls();
  cd->set_name(controlName_);

  for (const auto* param : control->getApplyParameters()->parameters) {
    auto* p = cd->add_params();
    p->set_name(param->name.name.c_str());
    *p->mutable_type() = emitType(param->type);
    switch (param->direction) {
      case IR::Direction::In:
        p->set_direction(fourward::ir::v1::Direction::IN);
        break;
      case IR::Direction::Out:
        p->set_direction(fourward::ir::v1::Direction::OUT);
        break;
      case IR::Direction::InOut:
        p->set_direction(fourward::ir::v1::Direction::INOUT);
        break;
      default:
        break;
    }
  }

  for (const auto* decl : control->controlLocals) {
    if (const auto* action = decl->to<IR::P4Action>()) {
      auto* ad = cd->add_local_actions();
      emitAction(action, ad);
    } else if (const auto* table = decl->to<IR::P4Table>()) {
      emitTable(table);
    } else if (const auto* varDecl = decl->to<IR::Declaration_Variable>()) {
      auto* vd = cd->add_local_vars();
      vd->set_name(varDecl->name.name.c_str());
      *vd->mutable_type() = emitType(varDecl->type);
      if (varDecl->initializer) {
        *vd->mutable_initializer() = emitExpr(varDecl->initializer);
      }
    }
  }

  for (const auto* stmt : control->body->components) {
    *cd->add_apply_body() = emitStmt(stmt);
  }
}

void FourWardBackend::emitAction(const IR::P4Action* action,
                                 fourward::ir::v1::ActionDecl* out) {
  // Use the original (pre-midend) name as the canonical key so it matches the
  // p4info alias and allows table-dispatch lookups to succeed.
  out->set_name(action->name.originalName.c_str());
  // If the midend renamed this action (e.g. "do_thing" → "do_thing_1"), also
  // record the current name so the interpreter can resolve direct call sites
  // that use it.
  if (action->name.name != action->name.originalName) {
    out->set_current_name(action->name.name.c_str());
  }
  for (const auto* param : action->parameters->parameters) {
    auto* p = out->add_params();
    p->set_name(param->name.name.c_str());
    *p->mutable_type() = emitType(param->type);
  }
  for (const auto* stmt : action->body->components) {
    *out->add_body() = emitStmt(stmt);
  }
}

void FourWardBackend::emitTable(const IR::P4Table* table) {
  // originalName matches the p4info alias (e.g. "port_table" from
  // "MyIngress.port_table").
  const std::string tableName = table->name.originalName.c_str();

  // Look up the p4info table by qualified name to retrieve match field IDs.
  // The qualified name is "controlName.tableName" (e.g.
  // "MyIngress.port_table").
  const std::string qualifiedName = controlName_ + "." + tableName;
  const p4::config::v1::Table* p4Table = nullptr;
  for (const auto& t : pipelineConfig_.p4info().tables()) {
    if (t.preamble().name() == qualifiedName) {
      p4Table = &t;
      break;
    }
  }
  if (!p4Table) {
    LOG1("WARNING: no p4info table found for " << qualifiedName
                                               << "; skipping emitTable");
    return;
  }

  auto* tb = behavioral_->add_tables();
  tb->set_name(tableName);

  // Emit one TableKey per match field. The field_name is the p4info match
  // field ID as a string; this is what TableStore.lookup compares against
  // FieldMatch.fieldId from P4Runtime write requests.
  const IR::Key* key = table->getKey();
  if (!key) return;
  int keyIdx = 0;
  for (const auto* keyElem : key->keyElements) {
    if (keyIdx >= p4Table->match_fields_size()) break;
    auto* tk = tb->add_keys();
    tk->set_field_name(std::to_string(p4Table->match_fields(keyIdx).id()));
    *tk->mutable_expr() = emitExpr(keyElem->expression);
    ++keyIdx;
  }
}

void FourWardBackend::emitArchitecture(const IR::ToplevelBlock* toplevel) {
  auto* arch = behavioral_->mutable_architecture();

  const auto* main = toplevel->getMain();
  if (!main) return;

  std::string archName;
  if (main->type->name == "V1Switch") {
    archName = "v1model";
    arch->set_name(archName);

    auto addStage = [&](const std::string& name, const std::string& blockName,
                        fourward::ir::v1::StageKind kind) {
      auto* stage = arch->add_stages();
      stage->set_name(name);
      stage->set_block_name(blockName);
      stage->set_kind(kind);
    };

    // V1Switch(parser, verify_checksum, ingress, egress, compute_checksum,
    // deparser)
    const std::vector<std::pair<std::string, fourward::ir::v1::StageKind>>
        stageSpec = {
            {"parser", fourward::ir::v1::StageKind::PARSER},
            {"verify_checksum", fourward::ir::v1::StageKind::CONTROL},
            {"ingress", fourward::ir::v1::StageKind::CONTROL},
            {"egress", fourward::ir::v1::StageKind::CONTROL},
            {"compute_checksum", fourward::ir::v1::StageKind::CONTROL},
            {"deparser", fourward::ir::v1::StageKind::DEPARSER},
        };

    size_t i = 0;
    for (const auto& arg :
         *main->node->to<IR::Declaration_Instance>()->arguments) {
      if (i >= stageSpec.size()) break;
      std::string blockName;
      const auto* expr = arg->expression;
      if (const auto* pe = expr->to<IR::PathExpression>()) {
        // Already-resolved reference (e.g. after some midend passes).
        blockName = pe->path->name.name.c_str();
      } else if (const auto* cce = expr->to<IR::ConstructorCallExpression>()) {
        // Constructor call: MyParser() — the type name is the block name.
        if (const auto* tn = cce->constructedType->to<IR::Type_Name>()) {
          blockName = tn->path->name.name.c_str();
        }
      } else if (const auto* mc = expr->to<IR::MethodCallExpression>()) {
        if (const auto* pe2 = mc->method->to<IR::PathExpression>()) {
          blockName = pe2->path->name.name.c_str();
        }
      }
      if (!blockName.empty()) {
        addStage(stageSpec[i].first, blockName, stageSpec[i].second);
      }
      ++i;
    }
  } else {
    // Unknown architecture: emit the name and leave stages empty.
    // The simulator will reject it with a clear error.
    arch->set_name(main->type->name.name.c_str());
    ::P4::error(
        "4ward: unsupported architecture '%1%'. Only v1model is supported "
        "currently.",
        main->type->name);
  }
}

bool FourWardBackend::writePipelineConfig() const {
  const std::string path = outputFilePath();
  std::ofstream out(path, std::ios::binary);
  if (!out) {
    ::P4::error("4ward: cannot open output file '%1%'", path);
    return false;
  }
  std::string text;
  if (!google::protobuf::TextFormat::PrintToString(pipelineConfig_, &text)) {
    ::P4::error("4ward: failed to serialise PipelineConfig to '%1%'", path);
    return false;
  }
  out << text;
  LOG1("4ward: wrote PipelineConfig to " << path);
  return true;
}

std::string FourWardBackend::outputFilePath() const {
  if (options_.outputFile) return *options_.outputFile;
  // Default: replace input extension with .txtpb
  return std::filesystem::path(options_.file)
      .replace_extension(".txtpb")
      .string();
}

}  // namespace P4::FourWard
