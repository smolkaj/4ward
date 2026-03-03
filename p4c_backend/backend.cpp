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

#include "google/protobuf/text_format.h"

#include "frontends/p4/coreLibrary.h"
#include "lib/error.h"
#include "lib/log.h"

namespace P4::FourWard {

// =============================================================================
// Type emission
// =============================================================================

fourward::ir::v1::Type EmitType(const IR::Type *type, const TypeMap &typeMap) {
    fourward::ir::v1::Type out;

    if (const auto *bits = type->to<IR::Type_Bits>()) {
        if (bits->isSigned) {
            out.mutable_signed_int()->set_width(bits->size);
        } else {
            out.mutable_bit()->set_width(bits->size);
        }
    } else if (const auto *vb = type->to<IR::Type_Varbits>()) {
        out.mutable_varbit()->set_max_width(vb->size);
    } else if (type->is<IR::Type_Boolean>()) {
        out.set_boolean(true);
    } else if (const auto *tn = type->to<IR::Type_Name>()) {
        out.set_named(tn->path->name.name.c_str());
    } else if (const auto *hdr = type->to<IR::Type_Header>()) {
        out.set_named(hdr->name.name.c_str());
    } else if (const auto *st = type->to<IR::Type_Struct>()) {
        out.set_named(st->name.name.c_str());
    } else if (const auto *stack = type->to<IR::Type_Array>()) {
        auto *hs = out.mutable_header_stack();
        if (const auto *elemType = stack->elementType->to<IR::Type_Name>()) {
            hs->set_element_type(elemType->path->name.name.c_str());
        }
        if (const auto *size = stack->size->to<IR::Constant>()) {
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

fourward::ir::v1::Expr EmitExpr(const IR::Expression *expr, const TypeMap &typeMap) {
    fourward::ir::v1::Expr out;

    if (const auto *cnst = expr->to<IR::Constant>()) {
        auto *lit = out.mutable_literal();
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
    } else if (const auto *b = expr->to<IR::BoolLiteral>()) {
        out.mutable_literal()->set_boolean(b->value);
    } else if (const auto *pe = expr->to<IR::PathExpression>()) {
        out.mutable_name_ref()->set_name(pe->path->name.name.c_str());
    } else if (const auto *mem = expr->to<IR::Member>()) {
        auto *fa = out.mutable_field_access();
        *fa->mutable_expr() = EmitExpr(mem->expr, typeMap);
        fa->set_field_name(mem->member.name.c_str());
    } else if (const auto *slice = expr->to<IR::Slice>()) {
        auto *s = out.mutable_slice();
        *s->mutable_expr() = EmitExpr(slice->e0, typeMap);
        s->set_hi(slice->getH());
        s->set_lo(slice->getL());
    } else if (const auto *cat = expr->to<IR::Concat>()) {
        *out.mutable_concat()->mutable_left()  = EmitExpr(cat->left, typeMap);
        *out.mutable_concat()->mutable_right() = EmitExpr(cat->right, typeMap);
    } else if (const auto *cast = expr->to<IR::Cast>()) {
        *out.mutable_cast()->mutable_target_type() = EmitType(cast->destType, typeMap);
        *out.mutable_cast()->mutable_expr()         = EmitExpr(cast->expr, typeMap);
    } else if (const auto *binop = expr->to<IR::Operation_Binary>()) {
        auto *b = out.mutable_binary_op();
        *b->mutable_left()  = EmitExpr(binop->left, typeMap);
        *b->mutable_right() = EmitExpr(binop->right, typeMap);

        if      (binop->is<IR::Add>())     b->set_op(fourward::ir::v1::BinaryOperator::ADD);
        else if (binop->is<IR::Sub>())     b->set_op(fourward::ir::v1::BinaryOperator::SUB);
        else if (binop->is<IR::Mul>())     b->set_op(fourward::ir::v1::BinaryOperator::MUL);
        else if (binop->is<IR::Div>())     b->set_op(fourward::ir::v1::BinaryOperator::DIV);
        else if (binop->is<IR::Mod>())     b->set_op(fourward::ir::v1::BinaryOperator::MOD);
        else if (binop->is<IR::AddSat>())  b->set_op(fourward::ir::v1::BinaryOperator::ADD_SAT);
        else if (binop->is<IR::SubSat>())  b->set_op(fourward::ir::v1::BinaryOperator::SUB_SAT);
        else if (binop->is<IR::BAnd>())    b->set_op(fourward::ir::v1::BinaryOperator::BIT_AND);
        else if (binop->is<IR::BOr>())     b->set_op(fourward::ir::v1::BinaryOperator::BIT_OR);
        else if (binop->is<IR::BXor>())    b->set_op(fourward::ir::v1::BinaryOperator::BIT_XOR);
        else if (binop->is<IR::Shl>())     b->set_op(fourward::ir::v1::BinaryOperator::SHL);
        else if (binop->is<IR::Shr>())     b->set_op(fourward::ir::v1::BinaryOperator::SHR);
        else if (binop->is<IR::Equ>())     b->set_op(fourward::ir::v1::BinaryOperator::EQ);
        else if (binop->is<IR::Neq>())     b->set_op(fourward::ir::v1::BinaryOperator::NEQ);
        else if (binop->is<IR::Lss>())     b->set_op(fourward::ir::v1::BinaryOperator::LT);
        else if (binop->is<IR::Grt>())     b->set_op(fourward::ir::v1::BinaryOperator::GT);
        else if (binop->is<IR::Leq>())     b->set_op(fourward::ir::v1::BinaryOperator::LE);
        else if (binop->is<IR::Geq>())     b->set_op(fourward::ir::v1::BinaryOperator::GE);
        else if (binop->is<IR::LAnd>())    b->set_op(fourward::ir::v1::BinaryOperator::AND);
        else if (binop->is<IR::LOr>())     b->set_op(fourward::ir::v1::BinaryOperator::OR);
        else LOG1("WARNING: unhandled binary operator: " << binop->node_type_name());
    } else if (const auto *unop = expr->to<IR::Operation_Unary>()) {
        auto *u = out.mutable_unary_op();
        *u->mutable_expr() = EmitExpr(unop->expr, typeMap);
        if      (unop->is<IR::Neg>())    u->set_op(fourward::ir::v1::UnaryOperator::NEG);
        else if (unop->is<IR::Cmpl>())   u->set_op(fourward::ir::v1::UnaryOperator::BIT_NOT);
        else if (unop->is<IR::LNot>())   u->set_op(fourward::ir::v1::UnaryOperator::NOT);
        else LOG1("WARNING: unhandled unary operator: " << unop->node_type_name());
    } else if (const auto *mc = expr->to<IR::MethodCallExpression>()) {
        auto *call = out.mutable_method_call();
        // The method is typically a Member expression: target.method
        if (const auto *mem = mc->method->to<IR::Member>()) {
            *call->mutable_target() = EmitExpr(mem->expr, typeMap);
            call->set_method(mem->member.name.c_str());
        } else {
            *call->mutable_target() = EmitExpr(mc->method, typeMap);
            call->set_method("__call__");
        }
        for (const auto *arg : *mc->arguments) {
            *call->add_args() = EmitExpr(arg->expression, typeMap);
        }
    } else {
        LOG1("WARNING: unhandled expression " << expr->node_type_name());
    }

    // Always populate the type annotation.
    if (const auto *type = typeMap.getType(expr)) {
        *out.mutable_type() = EmitType(type, typeMap);
    }

    return out;
}

// =============================================================================
// Statement emission
// =============================================================================

fourward::ir::v1::Stmt EmitStmt(const IR::StatOrDecl *node, const TypeMap &typeMap) {
    fourward::ir::v1::Stmt out;

    if (const auto *assign = node->to<IR::AssignmentStatement>()) {
        auto *a = out.mutable_assignment();
        *a->mutable_lhs() = EmitExpr(assign->left, typeMap);
        *a->mutable_rhs() = EmitExpr(assign->right, typeMap);
    } else if (const auto *mc = node->to<IR::MethodCallStatement>()) {
        *out.mutable_method_call()->mutable_call() = EmitExpr(mc->methodCall, typeMap);
    } else if (const auto *ifst = node->to<IR::IfStatement>()) {
        auto *i = out.mutable_if_stmt();
        *i->mutable_condition() = EmitExpr(ifst->condition, typeMap);
        if (const auto *tb = ifst->ifTrue->to<IR::BlockStatement>()) {
            *i->mutable_then_block() = EmitBlock(tb, typeMap);
        }
        if (ifst->ifFalse) {
            if (const auto *fb = ifst->ifFalse->to<IR::BlockStatement>()) {
                *i->mutable_else_block() = EmitBlock(fb, typeMap);
            }
        }
    } else if (const auto *sw = node->to<IR::SwitchStatement>()) {
        auto *s = out.mutable_switch_stmt();
        *s->mutable_subject() = EmitExpr(sw->expression, typeMap);
        for (const auto *c : sw->cases) {
            if (c->label->is<IR::DefaultExpression>()) {
                if (const auto *b = c->statement->to<IR::BlockStatement>()) {
                    *s->mutable_default_block() = EmitBlock(b, typeMap);
                }
            } else {
                auto *sc = s->add_cases();
                if (const auto *pe = c->label->to<IR::PathExpression>()) {
                    sc->set_action_name(pe->path->name.name.c_str());
                }
                if (c->statement) {
                    if (const auto *b = c->statement->to<IR::BlockStatement>()) {
                        *sc->mutable_block() = EmitBlock(b, typeMap);
                    }
                }
            }
        }
    } else if (const auto *blk = node->to<IR::BlockStatement>()) {
        *out.mutable_block() = EmitBlock(blk, typeMap);
    } else if (node->is<IR::ExitStatement>()) {
        out.mutable_exit();
    } else if (const auto *ret = node->to<IR::ReturnStatement>()) {
        if (ret->expression) {
            *out.mutable_return_stmt()->mutable_value() = EmitExpr(ret->expression, typeMap);
        } else {
            out.mutable_return_stmt();
        }
    } else {
        LOG1("WARNING: unhandled statement " << node->node_type_name());
    }
    return out;
}

fourward::ir::v1::BlockStmt EmitBlock(const IR::BlockStatement *block, const TypeMap &typeMap) {
    fourward::ir::v1::BlockStmt out;
    for (const auto *stmt : block->components) {
        *out.add_stmts() = EmitStmt(stmt, typeMap);
    }
    return out;
}

// =============================================================================
// FourWardBackend
// =============================================================================

FourWardBackend::FourWardBackend(const FourWardOptions &options, const ReferenceMap &refMap,
                                 const TypeMap &typeMap)
    : options_(options), refMap_(refMap), typeMap_(typeMap) {
    behavioral_ = pipelineConfig_.mutable_behavioral();
}

void FourWardBackend::process(const IR::ToplevelBlock *toplevel) {
    const auto *program = toplevel->getProgram();

    emitTypeDecls(program);
    emitArchitecture(toplevel);

    for (const auto *decl : *program->getDeclarations()) {
        if (const auto *parser = decl->to<IR::P4Parser>()) {
            emitParser(parser);
        } else if (const auto *control = decl->to<IR::P4Control>()) {
            emitControl(control);
        }
    }
}

void FourWardBackend::setP4Info(p4::config::v1::P4Info p4info) {
    *pipelineConfig_.mutable_p4info() = std::move(p4info);
}

void FourWardBackend::emitTypeDecls(const IR::P4Program *program) {
    for (const auto *decl : *program->getDeclarations()) {
        if (const auto *hdr = decl->to<IR::Type_Header>()) {
            auto *td = behavioral_->add_types();
            td->set_name(hdr->name.name.c_str());
            auto *hdecl = td->mutable_header();
            for (const auto *field : hdr->fields) {
                auto *fd = hdecl->add_fields();
                fd->set_name(field->name.name.c_str());
                *fd->mutable_type() = EmitType(field->type, typeMap_);
            }
        } else if (const auto *st = decl->to<IR::Type_Struct>()) {
            auto *td = behavioral_->add_types();
            td->set_name(st->name.name.c_str());
            auto *sdecl = td->mutable_struct_();
            for (const auto *field : st->fields) {
                auto *fd = sdecl->add_fields();
                fd->set_name(field->name.name.c_str());
                *fd->mutable_type() = EmitType(field->type, typeMap_);
            }
        }
        // Enums and header unions: TODO
    }
}

void FourWardBackend::emitParser(const IR::P4Parser *parser) {
    auto *pd = behavioral_->add_parsers();
    pd->set_name(parser->name.name.c_str());

    for (const auto *param : parser->getApplyParameters()->parameters) {
        auto *p = pd->add_params();
        p->set_name(param->name.name.c_str());
        *p->mutable_type() = EmitType(param->type, typeMap_);
        switch (param->direction) {
            case IR::Direction::In:    p->set_direction(fourward::ir::v1::Direction::IN); break;
            case IR::Direction::Out:   p->set_direction(fourward::ir::v1::Direction::OUT); break;
            case IR::Direction::InOut: p->set_direction(fourward::ir::v1::Direction::INOUT); break;
            default: break;
        }
    }

    for (const auto *state : parser->states) {
        auto *ps = pd->add_states();
        ps->set_name(state->name.name.c_str());

        for (const auto *stmt : state->components) {
            *ps->add_stmts() = EmitStmt(stmt, typeMap_);
        }

        // accept/reject are terminal states with no selectExpression.
        if (!state->selectExpression) {
        } else if (const auto *sel = state->selectExpression->to<IR::SelectExpression>()) {
            auto *selectTrans = ps->mutable_transition()->mutable_select();
            for (const auto *key : sel->select->components) {
                *selectTrans->add_keys() = EmitExpr(key, typeMap_);
            }
            for (const auto *sc : sel->selectCases) {
                auto *c = selectTrans->add_cases();
                if (sc->keyset->is<IR::DefaultExpression>()) {
                    selectTrans->set_default_state(sc->state->path->name.name.c_str());
                    continue;
                }
                auto *k = c->add_keysets();
                *k->mutable_exact() = EmitExpr(sc->keyset, typeMap_);
                c->set_next_state(sc->state->path->name.name.c_str());
            }
        } else if (const auto *path = state->selectExpression->to<IR::PathExpression>()) {
            ps->mutable_transition()->set_next_state(path->path->name.name.c_str());
        }
    }
}

void FourWardBackend::emitControl(const IR::P4Control *control) {
    auto *cd = behavioral_->add_controls();
    cd->set_name(control->name.name.c_str());

    for (const auto *param : control->getApplyParameters()->parameters) {
        auto *p = cd->add_params();
        p->set_name(param->name.name.c_str());
        *p->mutable_type() = EmitType(param->type, typeMap_);
        switch (param->direction) {
            case IR::Direction::In:    p->set_direction(fourward::ir::v1::Direction::IN); break;
            case IR::Direction::Out:   p->set_direction(fourward::ir::v1::Direction::OUT); break;
            case IR::Direction::InOut: p->set_direction(fourward::ir::v1::Direction::INOUT); break;
            default: break;
        }
    }

    for (const auto *decl : control->controlLocals) {
        if (const auto *action = decl->to<IR::P4Action>()) {
            auto *ad = cd->add_local_actions();
            emitAction(action, ad);
        }
    }

    for (const auto *stmt : control->body->components) {
        *cd->add_apply_body() = EmitStmt(stmt, typeMap_);
    }
}

void FourWardBackend::emitAction(const IR::P4Action *action,
                                 fourward::ir::v1::ActionDecl *out) {
    out->set_name(action->name.name.c_str());
    for (const auto *param : action->parameters->parameters) {
        auto *p = out->add_params();
        p->set_name(param->name.name.c_str());
        *p->mutable_type() = EmitType(param->type, typeMap_);
    }
    for (const auto *stmt : action->body->components) {
        *out->add_body() = EmitStmt(stmt, typeMap_);
    }
}

void FourWardBackend::emitArchitecture(const IR::ToplevelBlock *toplevel) {
    auto *arch = behavioral_->mutable_architecture();

    const auto *main = toplevel->getMain();
    if (!main) return;

    std::string archName;
    if (main->type->name == "V1Switch") {
        archName = "v1model";
        arch->set_name(archName);

        auto addStage = [&](const std::string &name, const std::string &blockName,
                             fourward::ir::v1::StageKind kind) {
            auto *stage = arch->add_stages();
            stage->set_name(name);
            stage->set_block_name(blockName);
            stage->set_kind(kind);
        };

        // V1Switch(parser, verify_checksum, ingress, egress, compute_checksum, deparser)
        const std::vector<std::pair<std::string, fourward::ir::v1::StageKind>> stageSpec = {
            {"parser",            fourward::ir::v1::StageKind::PARSER},
            {"verify_checksum",   fourward::ir::v1::StageKind::CONTROL},
            {"ingress",           fourward::ir::v1::StageKind::CONTROL},
            {"egress",            fourward::ir::v1::StageKind::CONTROL},
            {"compute_checksum",  fourward::ir::v1::StageKind::CONTROL},
            {"deparser",          fourward::ir::v1::StageKind::DEPARSER},
        };

        size_t i = 0;
        for (const auto &arg : *main->node->to<IR::Declaration_Instance>()->arguments) {
            if (i >= stageSpec.size()) break;
            std::string blockName;
            const auto *expr = arg->expression;
            if (const auto *pe = expr->to<IR::PathExpression>()) {
                // Already-resolved reference (e.g. after some midend passes).
                blockName = pe->path->name.name.c_str();
            } else if (const auto *cce = expr->to<IR::ConstructorCallExpression>()) {
                // Constructor call: MyParser() — the type name is the block name.
                if (const auto *tn = cce->constructedType->to<IR::Type_Name>()) {
                    blockName = tn->path->name.name.c_str();
                }
            } else if (const auto *mc = expr->to<IR::MethodCallExpression>()) {
                if (const auto *pe2 = mc->method->to<IR::PathExpression>()) {
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
        ::P4::error("4ward: unsupported architecture '%1%'. Only v1model is supported currently.",
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
    return std::filesystem::path(options_.file).replace_extension(".txtpb").string();
}

}  // namespace P4::FourWard
