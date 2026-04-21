"""Emits an executable shim named `cc` forwarding to the CC toolchain compiler.

For sandboxed environments without a system `cc` on PATH: consumers prepend
the shim's parent dir to their subprocess's PATH, and p4c (or any tool that
shells out to `cc`) finds one. For Starlark actions, prefer `export -f cc`
inside `run_shell` — no separate file needed.
"""

load("@rules_cc//cc/common:cc_common.bzl", "cc_common")

def _cc_shim_impl(ctx):
    # cfg = "exec" on the attr gives a compiler runnable on the test machine,
    # not on the (possibly cross-compile) target platform.
    cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
    cc = cc_toolchain.compiler_executable

    # Subdir so the output basename can be literally `cc` without colliding
    # with the target name; callers take `.parent` to get a dir for PATH.
    shim = ctx.actions.declare_file(ctx.label.name + "/cc")
    ctx.actions.write(
        output = shim,
        content = "#!/bin/sh\nexec \"{}\" \"$@\"\n".format(cc),
        is_executable = True,
    )

    # `all_files` covers wrapper scripts (e.g. macOS cc_wrapper.sh) that
    # `compiler_executable` references but doesn't itself carry.
    return [DefaultInfo(
        files = depset([shim]),
        runfiles = ctx.runfiles(
            files = [shim],
            transitive_files = cc_toolchain.all_files,
        ),
    )]

cc_shim = rule(
    implementation = _cc_shim_impl,
    attrs = {
        "_cc_toolchain": attr.label(
            default = "@bazel_tools//tools/cpp:current_cc_toolchain",
            cfg = "exec",
            providers = [cc_common.CcToolchainInfo],
        ),
    },
)
