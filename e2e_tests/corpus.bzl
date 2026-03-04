"""Starlark macro for p4c corpus STF tests.

Usage in e2e_tests/corpus/BUILD.bazel:

    load("//e2e_tests:corpus.bzl", "p4_stf_test")

    p4_stf_test(name = "opassign1-bmv2")

This creates:
  - a genrule that compiles <name>.p4 → <name>.txtpb using p4c-4ward
  - a genrule that copies <name>.stf from @p4c into this package (so the test
    runner can locate it via TEST_TARGET without needing to know the canonical
    @p4c runfiles path)
  - a kt_jvm_test that runs CorpusStfTest against that txtpb + <name>.stf
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

def p4_stf_test(name, p4_src = None, stf_src = None, tags = []):
    """Compiles a p4c corpus P4 file and runs its STF test against 4ward.

    Args:
        name:    base name; also used to derive default p4_src/stf_src filenames.
        p4_src:  P4 source file (default: @p4c//testdata/p4_16_samples:<name>.p4).
        stf_src: STF test file (default: @p4c//testdata/p4_16_samples:<name>.stf).
        tags:    Bazel tags forwarded to the kt_jvm_test (e.g. ["manual"] to
                 exclude from //... until the required feature is implemented).
    """
    if p4_src == None:
        p4_src = "@p4c//testdata/p4_16_samples:" + name + ".p4"
    if stf_src == None:
        stf_src = "@p4c//testdata/p4_16_samples:" + name + ".stf"

    pb_name = name + "_pb"
    stf_name = name + "_stf"

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

    # Copy the STF file from @p4c into this package so the test runner can
    # locate it via TEST_TARGET (at _main/<pkg>/<name>.stf) without needing to
    # know @p4c's canonical Bzlmod runfiles path.
    native.genrule(
        name = stf_name,
        srcs = [stf_src],
        outs = [name + ".stf"],
        cmd = "cp $< $@",
    )

    kt_jvm_test(
        name = name + "_test",
        srcs = ["CorpusStfTest.kt"],
        test_class = "fourward.e2e.corpus.CorpusStfTest",
        tags = tags,
        data = [
            ":" + stf_name,
            ":" + pb_name,
            "//simulator",
        ],
        deps = [
            "//e2e_tests/stf:stf_runner",
            "@maven//:junit_junit",
        ],
    )
