"""Starlark macro for BMv2 differential tests.

Usage in e2e_tests/bmv2_diff/BUILD.bazel:

    load("//e2e_tests:bmv2_diff.bzl", "bmv2_diff_test_suite")

    bmv2_diff_test_suite(
        name = "bmv2_diff_test",
        tests = ["opassign1-bmv2", "flag_lost-bmv2", ...],
    )

For each test this creates:
  - a genrule compiling <name>.p4 → <name>.json using p4c-bm2-ss (BMv2 backend)
  - a genrule compiling <name>.p4 → <name>.txtpb using p4c-4ward (for P4Info)
  - a genrule copying <name>.stf from @p4c
  - a single kt_jvm_test that runs all tests in one JVM, driving both the 4ward
    simulator and BMv2's simple_switch, and comparing output packets bit-for-bit.
"""

load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")
load("//e2e_tests:p4c.bzl", "p4c_compile")

def bmv2_diff_test_suite(name, tests, tags = [], includes = []):
    """Compiles corpus P4 files for both backends and runs differential tests.

    Args:
        name:     name of the batched kt_jvm_test target.
        tests:    list of test base names (e.g. "opassign1-bmv2").
        tags:     Bazel tags forwarded to the test.
        includes: extra P4 file labels needed as #include dependencies.
    """
    data = [
        ":bmv2_driver",
    ]

    include_flags = "".join([
        " -I $$(dirname $(execpath " + inc + "))"
        for inc in includes
    ])

    for test in tests:
        p4_src = "@p4c//testdata/p4_16_samples:" + test + ".p4"

        # Compile to BMv2 JSON.
        native.genrule(
            name = test + "_json",
            srcs = [p4_src] + includes,
            outs = [test + ".json"],
            cmd = "$(execpath @p4c//:p4c_bmv2) -I $$(dirname $(execpath @p4c//:core_p4))" + include_flags + " -o $@ $(execpath " + p4_src + ")",
            tags = tags,
            tools = [
                "@p4c//:p4c_bmv2",
                "@p4c//:core_p4",
                "@p4c//:p4include",
            ],
        )

        # Compile to 4ward PipelineConfig (needed for P4Info).
        p4c_compile(test, p4_src, includes, tags)

        native.genrule(
            name = test + "_stf",
            srcs = ["@p4c//testdata/p4_16_samples:" + test + ".stf"],
            outs = [test + ".stf"],
            cmd = "cp $< $@",
            tags = tags,
        )

        data.extend([
            ":" + test + "_json",
            ":" + test + "_pb",
            ":" + test + "_stf",
        ])

    kt_jvm_test(
        name = name,
        srcs = [
            "Bmv2DiffTest.kt",
            "Bmv2Runner.kt",
        ],
        test_class = "fourward.e2e.bmv2.Bmv2DiffTest",
        tags = tags,
        data = data,
        deps = [
            "//e2e_tests/stf:stf_runner",
            "//simulator:simulator_java_proto",
            "//simulator:simulator_lib",
            "@maven//:com_google_protobuf_protobuf_java",
            "@maven//:junit_junit",
        ],
    )
