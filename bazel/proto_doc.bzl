"""Bazel rule: HTML documentation from proto_library targets via protoc-gen-doc."""

load("@protobuf//bazel/common:proto_info.bzl", "ProtoInfo")

def _proto_doc_impl(ctx):
    out = ctx.outputs.out
    proto_infos = [dep[ProtoInfo] for dep in ctx.attr.protos]

    # All .proto files needed as action inputs (direct + transitive).
    transitive_sources = depset(
        transitive = [pi.transitive_sources for pi in proto_infos],
    )

    # All import roots (--proto_path flags).
    transitive_proto_path = depset(
        transitive = [pi.transitive_proto_path for pi in proto_infos],
    )

    protoc = ctx.executable._protoc
    wrapper = ctx.executable._wrapper
    plugin = ctx.executable._plugin

    args = ctx.actions.args()
    args.add("--plugin=protoc-gen-doc=" + wrapper.path)
    args.add("--doc_out=" + out.dirname)
    args.add("--doc_opt=html," + out.basename)
    args.add_all(transitive_proto_path, format_each = "--proto_path=%s")

    # Descriptor paths: relative to their proto_source_root.
    for pi in proto_infos:
        for src in pi.direct_sources:
            root = pi.proto_source_root
            path = src.path
            if root and path.startswith(root + "/"):
                args.add(path[len(root) + 1:])
            else:
                args.add(path)

    # Collect runfiles for the py_binary wrapper.
    wrapper_runfiles = ctx.attr._wrapper[DefaultInfo].default_runfiles.files

    ctx.actions.run(
        executable = protoc,
        arguments = [args],
        inputs = depset(transitive = [transitive_sources, wrapper_runfiles]),
        outputs = [out],
        tools = [wrapper, plugin],
        env = {"PROTOC_GEN_DOC_REAL": plugin.path},
        mnemonic = "ProtoDoc",
        progress_message = "Generating proto documentation: %s" % out.short_path,
    )

    return [DefaultInfo(files = depset([out]))]

proto_doc = rule(
    implementation = _proto_doc_impl,
    attrs = {
        "protos": attr.label_list(mandatory = True, providers = [ProtoInfo]),
        "out": attr.output(mandatory = True),
        "_protoc": attr.label(default = "@protobuf//:protoc", executable = True, cfg = "exec"),
        "_wrapper": attr.label(default = "//bazel:protoc_gen_doc_wrapper", executable = True, cfg = "exec"),
        "_plugin": attr.label(default = "//bazel:protoc_gen_doc", executable = True, cfg = "exec"),
    },
)
