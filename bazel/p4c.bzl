"""Public p4c-4ward compilation rules for downstream consumers.

Usage from a sonic-pins BUILD file:

    load("@fourward//bazel:p4c.bzl", "fourward_compile")

    fourward_compile(
        name = "sai_middleblock_fourward",
        src = "//sai_p4/instantiations/google:middleblock.p4",
    )

This produces two targets:
  - `sai_middleblock_fourward_pb`  — genrule producing `<name>.txtpb`
    (4ward PipelineConfig in text proto format)
  - `sai_middleblock_fourward_p4rt` — genrule producing `<name>.binpb`
    (P4Runtime ForwardingPipelineConfig in binary proto format)
"""

def fourward_compile(name, src, includes = [], extra_srcs = [], defines = [], tags = [], visibility = None):
    """Compiles a P4 source file using p4c-4ward.

    Produces two outputs:
      - `<name>.txtpb`: 4ward-native PipelineConfig (text proto).
      - `<name>.binpb`: P4Runtime ForwardingPipelineConfig (binary proto).

    Args:
        name:       base name for the generated targets.
        src:        P4 source file label.
        includes:   extra P4 file labels whose directories are added to the
                    include path via `-I`. Must be single-file labels (not
                    filegroups) since they are expanded via $(execpath).
        extra_srcs: additional file dependencies (e.g. filegroups) that do not
                    need `-I` flags. Useful when the P4 source uses relative
                    `#include` paths.
        defines:    preprocessor defines passed to p4c via `-D`.
        tags:       Bazel tags forwarded to the genrules.
        visibility: Bazel visibility for the genrule outputs.
    """
    include_flags = "".join([
        " -I $$(dirname $(execpath " + inc + "))"
        for inc in includes
    ])
    define_flags = "".join([" -D" + d for d in defines])
    all_srcs = [src] + includes + extra_srcs
    tools = [
        "@fourward//p4c_backend:p4c-4ward",
        "@p4c//p4include:core.p4",
        "@p4c//p4include",
    ]

    # 4ward-native PipelineConfig (text proto).
    native.genrule(
        name = name + "_pb",
        srcs = all_srcs,
        outs = [name + ".txtpb"],
        cmd = "$(execpath @fourward//p4c_backend:p4c-4ward)" +
              include_flags +
              " -I $$(dirname $(execpath @p4c//p4include:core.p4))" +
              define_flags +
              " -o $@ $(execpath " + src + ")",
        tools = tools,
        tags = tags,
        visibility = visibility,
    )

    # P4Runtime ForwardingPipelineConfig (binary proto).
    native.genrule(
        name = name + "_p4rt",
        srcs = all_srcs,
        outs = [name + ".binpb"],
        cmd = "$(execpath @fourward//p4c_backend:p4c-4ward)" +
              include_flags +
              " -I $$(dirname $(execpath @p4c//p4include:core.p4))" +
              define_flags +
              " --format p4runtime" +
              " -o $@ $(execpath " + src + ")",
        tools = tools,
        tags = tags,
        visibility = visibility,
    )
