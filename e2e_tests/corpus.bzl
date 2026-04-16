"""Starlark macro for batched p4c corpus STF tests.

Usage in e2e_tests/corpus/BUILD.bazel:

    load("//e2e_tests:corpus.bzl", "corpus_test_suite")

    corpus_test_suite(
        name = "corpus_test",
        tests = ["opassign1-bmv2", "flag_lost-bmv2", ...],
    )

This creates:
  - per-test genrules that compile <name>.p4 → <name>.txtpb using p4c-4ward
  - per-test genrules that copy <name>.stf from @p4c into this package
  - a single kt_jvm_test that runs all tests in one JVM via @Parameterized
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")
load("//bazel:fourward_pipeline.bzl", "fourward_pipeline")

def corpus_test_suite(name, tests, tags = [], includes = [], stf_overrides = {}):
    """Compiles p4c corpus P4 files and runs all STF tests in a single JVM.

    Args:
        name:     name of the batched kt_jvm_test target.
        tests:    list of test base names (e.g. "opassign1-bmv2").
        tags:     Bazel tags forwarded to the kt_jvm_test.
        includes: extra P4 file labels needed as #include dependencies (e.g.
                  skeleton headers). Their directory is added to the p4c
                  include path.
        stf_overrides: dict mapping test base names to local STF file labels,
                  overriding the upstream @p4c STF. Use when the upstream STF
                  expectation is wrong (e.g. missing un-parsed payload).
    """
    if not tests:
        return
    data = []

    for test in tests:
        p4_src = "@p4c//testdata/p4_16_samples:" + test + ".p4"

        fourward_pipeline(
            name = test,
            src = p4_src,
            out = test + ".txtpb",
            includes = includes,
            tags = tags,
        )

        if test in stf_overrides:
            # Local override: use the file directly, no genrule needed.
            data.append(stf_overrides[test])
        else:
            native.genrule(
                name = test + "_stf",
                srcs = ["@p4c//testdata/p4_16_samples:" + test + ".stf"],
                outs = [test + ".stf"],
                cmd = "cp $< $@",
                tags = tags,
            )
            data.append(":" + test + "_stf")

        data.append(":" + test)

    kt_jvm_test(
        name = name,
        srcs = ["CorpusStfTest.kt"],
        test_class = "fourward.e2e.corpus.CorpusStfTest",
        tags = tags,
        data = data,
        deps = [
            "//stf",
            "@fourward_maven//:junit_junit",
        ],
    )
