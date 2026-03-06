# Testing Strategy

The README asks "[Should you trust AI-written code?](../README.md#should-you-trust-ai-written-code)"
and gives the short answer: trust the tests, not the author. This document
tells the full story.

## The key insight

If success criteria are machine-checkable, *who* writes the code doesn't
matter — only whether the tests are good. There's no ambiguity, no judgment
call on "is this done?" A test passes or it doesn't.

This makes the project automatable: AI agents run a tight loop — pick a
failing test, implement, green, ship. And it makes the output trustworthy:
three independent oracles agreeing is stronger evidence of correctness than
any code review.

## Three layers, three oracles

### Layer 1: STF corpus — breadth

p4c ships over 200 [STF](https://github.com/p4lang/p4c/tree/main/testdata)
(Simple Test Framework) test programs. Each is a self-contained spec: a P4
program, table entries, input packets, and expected output. The STF runner
compiles through p4c + the 4ward backend, loads the pipeline, sends packets,
and diffs actual output against expectations.

The source of truth is hand-written expectations by the language authors — a
direct statement of what correct behavior looks like. This catches regressions,
feature gaps, and basic correctness across the full breadth of P4₁₆. The blind
spot: only paths someone thought to write get tested.

The failing-test list *is* the feature backlog — pick one, make it pass, ship.

### Layer 2: p4testgen — depth

[p4testgen](https://github.com/p4lang/p4c/tree/main/backends/p4tools/modules/testgen)
symbolically executes P4 programs with an SMT solver, generating concrete test
cases that exercise each reachable path — including ones no human would think
to write.

The source of truth is p4testgen's own model of P4 and BMv2 semantics, an
independent implementation that shares no code with 4ward or BMv2. This catches
bugs on paths humans miss. The blind spot: p4testgen's model could disagree
with the real BMv2 implementation.

### Layer 3: BMv2 differential testing — correctness

Identical inputs go through BMv2 and 4ward. Every output packet, every drop
decision, every egress port is compared. If they disagree, one of them has a
bug.

The source of truth is the reference implementation itself — not a model, not
a spec. This catches the case that the other layers can't: 4ward produces
output, but the output is *wrong*. The blind spot: BMv2 has its own bugs, and
can't serve as oracle for features unique to 4ward (like trace trees).

## Unit tests

Underneath the three layers, unit tests provide fast development feedback —
bit-precise arithmetic, match kinds, select expressions, packet I/O. Not part
of the correctness strategy, but they catch problems early, before the
expensive end-to-end tests run.
