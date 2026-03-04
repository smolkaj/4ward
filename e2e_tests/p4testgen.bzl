"""Starlark macro for p4testgen-based STF tests.

Usage in e2e_tests/p4testgen/BUILD.bazel:

    load("//e2e_tests:p4testgen.bzl", "p4_testgen_test")

    p4_testgen_test(
        name = "opassign1-bmv2",
        src_p4 = "@p4c//testdata/p4_16_samples:opassign1-bmv2.p4",
    )

This creates:
  - a _p4testgen_stfs rule that runs p4testgen into a tree artifact directory
  - a genrule that compiles the P4 source → <name>.txtpb using p4c-4ward
  - a kt_jvm_test that discovers and runs all generated STFs against the simulator
"""

load("@rules_cc//cc:find_cc_toolchain.bzl", "CC_TOOLCHAIN_TYPE", "find_cc_toolchain", "use_cc_toolchain")
load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_test")

def _p4testgen_stfs_impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)
    p4testgen = ctx.executable._p4testgen

    out_dir = ctx.actions.declare_directory(ctx.label.name)
    args = " ".join([
        "--target bmv2 --arch v1model --test-backend stf",
        "--max-tests " + str(ctx.attr.max_tests),
        "--seed " + str(ctx.attr.seed),
        "-I " + ctx.file._core_p4.dirname,
        ctx.file.src_p4.path,
        "--out-dir " + out_dir.path,
    ])

    # p4c hard-codes `popen("cc -E …")` for P4 preprocessing. Define a
    # shell function that redirects `cc` to the Bazel CC toolchain compiler
    # (same approach as p4lang/p4c's own p4_library rule).
    ctx.actions.run_shell(
        command = """
            function cc () {{ "{cc}" "$@"; }}
            export -f cc
            "{p4testgen}" {args}
        """.format(
            cc = cc_toolchain.compiler_executable,
            p4testgen = p4testgen.path,
            args = args,
        ),
        inputs = [ctx.file.src_p4] + ctx.files._p4include,
        outputs = [out_dir],
        tools = depset(
            direct = [p4testgen],
            transitive = [cc_toolchain.all_files],
        ),
        toolchain = CC_TOOLCHAIN_TYPE,
        # The toolchain compiler (e.g. gcc) may need cc1 on PATH.
        use_default_shell_env = True,
    )
    return [DefaultInfo(files = depset([out_dir]))]

_p4testgen_stfs = rule(
    implementation = _p4testgen_stfs_impl,
    attrs = {
        "src_p4": attr.label(allow_single_file = [".p4"]),
        "max_tests": attr.int(default = 0),
        "seed": attr.int(default = 0),
        "_p4testgen": attr.label(
            default = "@p4c//backends/p4tools:p4testgen",
            executable = True,
            cfg = "exec",
        ),
        "_core_p4": attr.label(
            default = "@p4c//:core_p4",
            allow_single_file = True,
        ),
        "_p4include": attr.label(
            default = "@p4c//:p4include",
        ),
    },
    toolchains = use_cc_toolchain(),
    fragments = ["cpp"],
)

def p4_testgen_test(name, src_p4 = None, max_tests = 0, seed = 0, tags = []):
    """Generates p4testgen STF tests and runs them against the 4ward simulator.

    Args:
        name: base name; also used to derive the src_p4 filename.
        src_p4: P4 source file (default: @p4c//testdata/p4_16_samples:<name>.p4).
        max_tests: upper bound on STF tests to generate (default: 0 = unlimited).
                   p4testgen explores paths until exhausted or the limit is hit.
        seed: random seed for p4testgen's path exploration (default: 0).
              Different seeds explore different execution paths.
        tags: Bazel tags forwarded to the test.
    """
    if src_p4 == None:
        src_p4 = "@p4c//testdata/p4_16_samples:" + name + ".p4"

    stfs_name = name + "_stfs"
    pb_name = name + "_pb"

    _p4testgen_stfs(
        name = stfs_name,
        src_p4 = src_p4,
        max_tests = max_tests,
        seed = seed,
        tags = tags,
    )

    # Compile P4 → PipelineConfig txtpb (same as corpus.bzl).
    native.genrule(
        name = pb_name,
        srcs = [src_p4],
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
        test_class = "fourward.e2e.p4testgen.P4TestgenTest",
        tags = tags,
        data = [
            ":" + stfs_name,
            ":" + pb_name,
            "//simulator",
        ],
        deps = [
            "//e2e_tests/p4testgen:p4testgen_test_class",
            "@maven//:junit_junit",
        ],
    )
