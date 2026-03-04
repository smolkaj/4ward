"""Starlark macro for p4testgen-based STF tests.

Usage in e2e_tests/p4testgen/BUILD.bazel:

    load("//e2e_tests:p4testgen.bzl", "p4_testgen_test")

    p4_testgen_test(name = "opassign1-bmv2")

This creates:
  - a genrule that runs p4testgen to generate a single path-covering STF test
  - a genrule that compiles <name>.p4 → <name>.txtpb using p4c-4ward
  - a kt_jvm_test that runs the generated STF against the simulator
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

def p4_testgen_test(name, p4_src = None, tags = []):
    """Generates a p4testgen STF test and runs it against the 4ward simulator.

    Args:
        name: base name; also used to derive the p4_src filename.
        p4_src: P4 source file (default: @p4c//testdata/p4_16_samples:<name>.p4).
        tags: Bazel tags forwarded to the test.
    """
    if p4_src == None:
        p4_src = "@p4c//testdata/p4_16_samples:" + name + ".p4"

    stf_gen_name = name + "_stf_gen"
    pb_name = name + "_pb"

    # Generate the STF via p4testgen. With --max-tests 1, the output is
    # <name>_1.stf in the output directory. We rename it to <name>.stf.
    native.genrule(
        name = stf_gen_name,
        srcs = [p4_src],
        outs = [name + ".stf"],
        cmd = (
            "$(execpath @p4c//backends/p4tools:p4testgen)" +
            " --target bmv2 --arch v1model --test-backend stf" +
            " --max-tests 1 --seed 1" +
            " -I $$(dirname $(execpath @p4c//:core_p4))" +
            " $(SRCS) --out-dir $(@D)/testgen_tmp 2>/dev/null" +
            " && mv $(@D)/testgen_tmp/" + name + "_1.stf $@" +
            " && rm -rf $(@D)/testgen_tmp"
        ),
        tools = [
            "@p4c//backends/p4tools:p4testgen",
            "@p4c//:core_p4",
            "@p4c//:p4include",
        ],
        tags = tags,
    )

    # Compile P4 → PipelineConfig txtpb (same as corpus.bzl).
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
        tags = tags,
    )

    kt_jvm_test(
        name = name + "_test",
        srcs = ["//e2e_tests/stf:StfTest.kt"],
        test_class = "fourward.e2e.StfTest",
        tags = tags,
        data = [
            ":" + stf_gen_name,
            ":" + pb_name,
            "//simulator",
        ],
        deps = [
            "//e2e_tests/stf:stf_runner",
            "@maven//:junit_junit",
        ],
    )
