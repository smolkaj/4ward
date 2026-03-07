# README Review — Fresh Eyes Analysis

> First-impressions review of the README and surrounding docs, conducted
> 2026-03-06. Intended as a working doc for improvements, not a permanent
> fixture.

## 1. The README sells the vision but doesn't show how to use it

The Quick Start is build + test. But a P4 engineer who just found this repo
wants to simulate *their own* program. There's no `4ward run my_program.p4`
example. The trace tree demo is `bazel test` with three flags — that's a test
invocation, not a user workflow. A "Run your first simulation" section with a
3-step recipe would be the single highest-value addition.

**Status:** Track 7 (standalone CLI) added to the roadmap. Until the CLI
exists, the README should at least show the Bazel-based workflow clearly.

## 2. The comparison table mixes reality with aspiration

"100% spec-compliant **(planned)**" sits in the same visual column as features
that actually work. A skimming reader will miss the "(planned)" and think it's
all done. Consider splitting the table or using a different visual treatment
for roadmap items — maybe an empty checkbox vs. a filled one instead of bold
links for everything.

## 3. The AI angle might be overshadowing the technical one

Two full README sections ("Should you trust AI-written code?" and "Why
Kotlin?") are about the *how* of development rather than the *what* of the
product. The `@p4runtime_translation` section — arguably a more unique
technical differentiator — gets less airtime than the AI narrative. A P4
engineer evaluating this tool probably cares more about "can it handle my
program?" than "who wrote it?" Consider whether the AI story belongs in the
README at all vs. just being linked from a one-liner.

## 4. AI_WORKFLOW.md is refreshingly honest — maybe too honest

> "Most of the time, I don't try to understand the AI's code. Most of the
> time, I don't even read it."

This is fascinating as a practitioner note, but a potential adopter reading
this might think "the author doesn't understand their own codebase." The three
testing oracles are the right counterargument, but the framing could land
badly. The doc could benefit from leading with "why this works" before "what I
don't do."

## 5. The "Why Kotlin? / Why not X?" section is dismissive

"Java? Kotlin, but worse." "Go? Weaker type system." These are defensible
positions but stated as one-liners they read as drive-by opinions rather than
considered tradeoffs. Either flesh them out (a sentence on what you'd actually
miss) or cut them — the Kotlin case already stands on its own merits without
dunking on alternatives.

## 6. ARCHITECTURE.md contradicts the README on trace trees

The README showcases a forking trace tree (clone example with two branches).
ARCHITECTURE.md says: "Status: zero-fork trees. Forking under active
development." If the clone example actually works now (Track 3 is marked
complete in the roadmap), ARCHITECTURE.md needs updating. If the README example
is cherry-picked from a test that doesn't reflect general capability, that's
misleading.

## 7. Ten docs is a lot for a pre-1.0 project

Not wrong per se, but it's a lot of surface area to maintain. STATUS.md (daily
log), LIMITATIONS.md, REFACTORING.md, ROADMAP.md — these overlap in purpose.
A newcomer hitting the docs table might freeze on "which of these 10 do I
actually need?" Consider whether LIMITATIONS and REFACTORING could fold into
ROADMAP, or whether some docs are really internal tools masquerading as public
documentation.

## 8. Minor nits

- `detekt.yml` in the repo root feels out of place — config files typically
  live in a config dir or `.config/`. Slightly clutters the root.
- No `.editorconfig` — would help contributors match formatting expectations
  without reading the docs.
- The README uses "optimised" (British) — fine, just be consistent throughout.

## Bottom line

The main gap is that the README optimizes for *explaining the project* rather
than *getting someone to try it*. A newcomer needs to go from "interesting" to
"I ran it" in under 5 minutes, and right now there's no clear path for that.
