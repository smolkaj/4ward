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
    data = ["//simulator"]

    # Build -I flags for any extra includes.
    if includes:
        # All includes are assumed to be in the same directory; one -I suffices.
        include_flag = " -I $$(dirname $(execpath " + includes[0] + "))"
    else:
        include_flag = ""

    for test in tests:
        p4_src = "@p4c//testdata/p4_16_samples:" + test + ".p4"

        native.genrule(
            name = test + "_pb",
            srcs = [p4_src] + includes,
            outs = [test + ".txtpb"],
            cmd = "$(execpath //p4c_backend:p4c-4ward) -I $$(dirname $(execpath @p4c//:core_p4))" + include_flag + " -o $@ $(execpath " + p4_src + ")",
            tags = tags,
            tools = [
                "//p4c_backend:p4c-4ward",
                "@p4c//:core_p4",
                "@p4c//:p4include",
            ],
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

        data.append(":" + test + "_pb")

    kt_jvm_test(
        name = name,
        srcs = ["CorpusStfTest.kt"],
        test_class = "fourward.e2e.corpus.CorpusStfTest",
        tags = tags,
        data = data,
        deps = [
            "//e2e_tests/stf:stf_runner",
            "@maven//:junit_junit",
        ],
    )
