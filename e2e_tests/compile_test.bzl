"""Starlark macro for compile-only P4 tests.

Verifies that P4 programs compile successfully through p4c-4ward without
needing STF test files. This catches compiler bugs and ensures the backend
handles a wide range of P4 programs.

Usage in BUILD.bazel:

    load("//e2e_tests:compile_test.bzl", "compile_test_suite")

    compile_test_suite(
        name = "psa_compile_test",
        tests = ["psa-action-profile1", "psa-counter1", ...],
    )
"""

load("@bazel_skylib//rules:build_test.bzl", "build_test")
load("//e2e_tests:p4c.bzl", "p4c_compile")

def compile_test_suite(name, tests, tags = [], includes = []):
    """Compiles P4 programs and verifies compilation succeeds.

    Args:
        name:     name of the build_test target.
        tests:    list of test base names (e.g. "psa-counter1").
        tags:     Bazel tags forwarded to the build_test.
        includes: extra P4 file labels for #include dependencies.
    """
    targets = []

    for test in tests:
        p4_src = "@p4c//testdata/p4_16_samples:" + test + ".p4"
        p4c_compile(test, p4_src, includes, tags)
        targets.append(":" + test + "_pb")

    build_test(
        name = name,
        targets = targets,
        tags = tags,
    )
