"""Starlark macro for p4c corpus STF tests.

Usage in e2e_tests/corpus/BUILD.bazel:

    load("//e2e_tests:corpus.bzl", "p4_stf_test")

    p4_stf_test(name = "opassign1-bmv2")

This creates:
  - a genrule that compiles <name>.p4 → <name>.txtpb using p4c-4ward
  - a kt_jvm_test that runs CorpusStfTest against that txtpb + <name>.stf

CorpusStfTest derives the file paths from Bazel's TEST_TARGET env var, which it
receives automatically. The txtpb and stf files are expected at
_main/<package>/<name>.txtpb and _main/<package>/<name>.stf under JAVA_RUNFILES.
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

def p4_stf_test(name, p4_src = None, stf_src = None):
    """Compiles a p4c corpus P4 file and runs its STF test against 4ward.

    Args:
        name:    base name; also used to derive default p4_src/stf_src filenames.
        p4_src:  P4 source file (default: <name>.p4).
        stf_src: STF test file (default: <name>.stf).
    """
    if p4_src == None:
        p4_src = name + ".p4"
    if stf_src == None:
        stf_src = name + ".stf"

    pb_name = name + "_pb"

    native.genrule(
        name = pb_name,
        srcs = [p4_src],
        outs = [name + ".txtpb"],
        cmd = "$(execpath //p4c_backend:p4c-4ward) -I $$(dirname $(execpath @p4c//:core_p4)) -o $@ $(SRCS)",
        tools = [
            "//p4c_backend:p4c-4ward",
            "@p4c//:core_p4",
            "@p4c//:p4include",
        ],
    )

    kt_jvm_test(
        name = name + "_test",
        srcs = ["CorpusStfTest.kt"],
        test_class = "fourward.e2e.corpus.CorpusStfTest",
        data = [
            stf_src,
            ":" + pb_name,
            "//simulator",
        ],
        deps = [
            "//e2e_tests/stf:stf_runner",
            "@maven//:junit_junit",
        ],
    )
