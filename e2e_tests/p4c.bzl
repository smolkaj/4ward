"""Shared p4c-4ward compilation helper for e2e test macros."""

def p4c_compile(name, src_p4, includes = [], tags = [], visibility = None):
    """Compiles a P4 source file to a PipelineConfig txtpb using p4c-4ward.

    Creates a genrule named `<name>_pb` that produces `<name>.txtpb`.

    Args:
        name:       base name (e.g. "opassign1-bmv2").
        src_p4:     P4 source file label.
        includes:   extra P4 file labels whose directories are added to the
                    include path via `-I`.
        tags:       Bazel tags forwarded to the genrule.
        visibility: Bazel visibility for the genrule output.
    """
    include_flags = "".join([
        " -I $$(dirname $(execpath " + inc + "))"
        for inc in includes
    ])
    native.genrule(
        name = name + "_pb",
        srcs = [src_p4] + includes,
        outs = [name + ".txtpb"],
        cmd = "$(execpath //p4c_backend:p4c-4ward) -I $$(dirname $(execpath @p4c//:core_p4))" + include_flags + " -o $@ $(execpath " + src_p4 + ")",
        tools = [
            "//p4c_backend:p4c-4ward",
            "@p4c//:core_p4",
            "@p4c//:p4include",
        ],
        tags = tags,
        visibility = visibility,
    )
