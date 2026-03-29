"""Bazel rule for compiling P4 programs to 4ward pipeline configs.

Usage:

    load("@fourward//bazel:fourward_pipeline.bzl", "fourward_pipeline")

    fourward_pipeline(
        name = "sai_middleblock",
        src = "//sai_p4/instantiations/google:middleblock.p4",
    )

This produces two outputs, accessible as `name.pipeline` and `name.p4rt`:
  - `<name>.pipeline.txtpb` — 4ward-native PipelineConfig (text proto)
  - `<name>.p4rt.binpb` — P4Runtime ForwardingPipelineConfig (binary proto)
"""

def _fourward_pipeline_impl(ctx):
    include_dirs = []
    for inc in ctx.files.includes:
        include_dirs.append(inc.dirname)

    # Shared arguments for both compilation modes.
    shared_args = []
    for d in include_dirs:
        shared_args += ["-I", d]
    shared_args += ["-I", ctx.file._p4include_anchor.dirname]
    for define in ctx.attr.defines:
        shared_args += ["-D" + define]

    # All input files (source + includes + extra_srcs + p4include).
    inputs = depset(
        [ctx.file.src] + ctx.files.includes + ctx.files.extra_srcs,
        transitive = [ctx.attr._p4include[DefaultInfo].files],
    )

    # p4c shells out to the system C preprocessor (cpp → cc1), which needs
    # PATH to resolve. use_default_shell_env ensures the tool action
    # inherits the host environment.
    run_kwargs = dict(
        inputs = inputs,
        executable = ctx.executable._p4c_4ward,
        use_default_shell_env = True,
    )

    # 4ward-native PipelineConfig (text proto).
    ctx.actions.run(
        outputs = [ctx.outputs.pipeline],
        arguments = shared_args + ["-o", ctx.outputs.pipeline.path, ctx.file.src.path],
        mnemonic = "FourwardCompile",
        progress_message = "Compiling %{input} to 4ward pipeline",
        **run_kwargs
    )

    # P4Runtime ForwardingPipelineConfig (binary proto).
    ctx.actions.run(
        outputs = [ctx.outputs.p4rt],
        arguments = shared_args + ["--format", "p4runtime", "-o", ctx.outputs.p4rt.path, ctx.file.src.path],
        mnemonic = "FourwardP4rt",
        progress_message = "Compiling %{input} to P4Runtime config",
        **run_kwargs
    )

    return [DefaultInfo(files = depset([ctx.outputs.pipeline, ctx.outputs.p4rt]))]

fourward_pipeline = rule(
    implementation = _fourward_pipeline_impl,
    doc = "Compiles a P4 source file to a 4ward PipelineConfig and a P4Runtime ForwardingPipelineConfig.",
    attrs = {
        "src": attr.label(
            mandatory = True,
            allow_single_file = [".p4"],
            doc = "P4 source file.",
        ),
        "includes": attr.label_list(
            allow_files = True,
            doc = "P4 files whose directories are added to the include path via -I. " +
                  "Useful for shadowing standard library files (e.g. a modified v1model.p4).",
        ),
        "extra_srcs": attr.label_list(
            allow_files = True,
            doc = "Additional file dependencies (e.g. filegroups) that don't need -I flags. " +
                  "Useful when the P4 source uses relative #include paths.",
        ),
        "defines": attr.string_list(
            doc = "Preprocessor defines passed to p4c via -D.",
        ),
        "_p4c_4ward": attr.label(
            default = "@fourward//p4c_backend:p4c-4ward",
            executable = True,
            cfg = "exec",
        ),
        "_p4include_anchor": attr.label(
            default = "@p4c//p4include:core.p4",
            allow_single_file = True,
        ),
        "_p4include": attr.label(
            default = "@p4c//p4include",
        ),
    },
    outputs = {
        "pipeline": "%{name}.pipeline.txtpb",
        "p4rt": "%{name}.p4rt.binpb",
    },
)
