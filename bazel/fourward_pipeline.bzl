"""Bazel rule for compiling P4 programs using p4c-4ward.

Usage:

    load("@fourward//bazel:fourward_pipeline.bzl", "fourward_pipeline")

    fourward_pipeline(
        name = "sai_middleblock",
        src = "//sai_p4/instantiations/google:middleblock.p4",
        out = "sai_middleblock.binpb",
        out_format = "p4runtime",
    )

Output formats (`out_format`):
  - `"native"` (default): `fourward.ir.PipelineConfig`
  - `"p4runtime"`: `p4.v1.ForwardingPipelineConfig` (for `SetForwardingPipelineConfig`)

File extension of `out` determines serialization: `.txtpb` for text proto,
`.binpb` for binary proto.
"""

_VALID_OUT_FORMATS = ["native", "p4runtime"]

def _fourward_pipeline_impl(ctx):
    include_dirs = []
    for inc in ctx.files.includes:
        include_dirs.append(inc.dirname)

    arguments = []
    for directory in include_dirs:
        arguments += ["-I", directory]
    arguments += ["-I", ctx.file._p4include_anchor.dirname]
    for define in ctx.attr.defines:
        arguments.append("-D" + define)

    if ctx.attr.out_format == "p4runtime":
        arguments += ["--format", "p4runtime"]

    arguments += ["-o", ctx.outputs.out.path, ctx.file.src.path]

    inputs = depset(
        [ctx.file.src] + ctx.files.includes + ctx.files.extra_srcs,
        transitive = [ctx.attr._p4include[DefaultInfo].files],
    )

    # p4c shells out to the system C preprocessor (cpp -> cc1), which needs
    # PATH to resolve.
    ctx.actions.run(
        outputs = [ctx.outputs.out],
        inputs = inputs,
        executable = ctx.executable._p4c_4ward,
        arguments = arguments,
        use_default_shell_env = True,
        mnemonic = "FourwardPipeline",
        progress_message = "Compiling %{input} to 4ward pipeline",
    )

    return [DefaultInfo(files = depset([ctx.outputs.out]))]

fourward_pipeline = rule(
    implementation = _fourward_pipeline_impl,
    doc = "Compiles a P4 source file using p4c-4ward.",
    attrs = {
        "src": attr.label(
            mandatory = True,
            allow_single_file = [".p4"],
            doc = "P4 source file.",
        ),
        "out": attr.output(
            mandatory = True,
            doc = "Output file. Extension determines serialization: " +
                  "`.txtpb` for text proto, `.binpb` for binary proto. " +
                  "The proto message type is determined by `out_format`.",
        ),
        "out_format": attr.string(
            default = "native",
            doc = "Output proto message type. " +
                  "`\"native\"` (default): `fourward.ir.PipelineConfig`. " +
                  "`\"p4runtime\"`: `p4.v1.ForwardingPipelineConfig` " +
                  "(suitable for `SetForwardingPipelineConfig`).",
            values = _VALID_OUT_FORMATS,
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
)
