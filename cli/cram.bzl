"""Bazel macro for cram tests."""

load("@pip//:requirements.bzl", "requirement")
load("@rules_python//python:defs.bzl", "py_test")

def cram_test(name, src, env_args, data):
    """Runs a cram .t file with environment variables from Bazel rootpaths.

    Args:
        name: Test target name.
        src: The .t cram test file.
        env_args: List of "KEY=$(rootpath ...)" strings passed as env vars.
        data: Runfiles needed by the test (binaries, example files, etc.).
    """
    py_test(
        name = name,
        srcs = ["//cli:run_cram.py"],
        main = "run_cram.py",
        args = env_args + ["$(rootpath %s)" % src],
        data = [src] + data,
        deps = [requirement("cram")],
    )
