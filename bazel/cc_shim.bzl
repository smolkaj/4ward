"""Emits an executable shim named `cc` that forwards to the CC toolchain compiler.

p4c shells out to `cc` by name for preprocessing. Hermetic test sandboxes
(blaze/google3, some OSS CI setups) have no system `cc` on PATH, so any
runtime that invokes p4c via plain `ProcessBuilder` / `exec` has to provide
`cc` itself. Prepend the shim's parent directory to PATH before spawning
p4c and the preprocessing step works.

For Starlark actions, prefer the `export -f cc` trick used in
`fourward_pipeline.bzl` — it doesn't require a separate file.
"""

load("@rules_cc//cc:find_cc_toolchain.bzl", "find_cc_toolchain", "use_cc_toolchain")

def _cc_shim_impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)
    cc = cc_toolchain.compiler_executable

    # The shim file must be named literally `cc` so PATH lookup finds it.
    # `declare_file("cc")` would collide with the target name, so nest it
    # in a per-target subdirectory; the caller takes `.parent` to get the
    # dir to prepend to PATH.
    shim = ctx.actions.declare_file(ctx.label.name + "/cc")
    ctx.actions.write(
        output = shim,
        content = "#!/bin/sh\nexec \"{}\" \"$@\"\n".format(cc),
        is_executable = True,
    )

    # `cc_toolchain.all_files` includes the compiler binary and anything it
    # transitively needs at runtime (e.g. macOS's cc_wrapper.sh). Without
    # it, the shim resolves to a runfile path the test sandbox hasn't
    # staged.
    return [DefaultInfo(
        files = depset([shim]),
        runfiles = ctx.runfiles(
            files = [shim],
            transitive_files = cc_toolchain.all_files,
        ),
    )]

cc_shim = rule(
    implementation = _cc_shim_impl,
    toolchains = use_cc_toolchain(),
    doc = "Produces a `cc` executable shim forwarding to the CC toolchain compiler.",
)
