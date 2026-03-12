"""Rule for assembling multiple documentation outputs into a single site directory."""

def _docs_bundle_impl(ctx):
    out = ctx.actions.declare_directory(ctx.attr.name)

    # Each section copies one output into <subdir>/:
    #   - A single directory → copied as-is.
    #   - A single file → becomes <subdir>/index.html.
    #   - Multiple outputs → the directory among them is used (e.g., rules_doxygen
    #     produces html/ + Doxyfile; we want the html/).
    commands = []
    inputs = []

    for target, name in ctx.attr.sections.items():
        files = target.files.to_list()
        inputs.extend(files)
        dirs = [f for f in files if f.is_directory]
        if len(dirs) == 1:
            commands.append('cp -r "{src}" "{out}/{name}"'.format(
                src = dirs[0].path,
                out = out.path,
                name = name,
            ))
        elif len(files) == 1:
            commands.append(
                'mkdir -p "{out}/{name}" && cp "{src}" "{out}/{name}/index.html"'.format(
                    src = files[0].path,
                    out = out.path,
                    name = name,
                ),
            )
        else:
            fail("Section '{}': expected one directory or one file, got {} files ({} directories).".format(
                name,
                len(files),
                len(dirs),
            ))

    if ctx.file.index:
        inputs.append(ctx.file.index)
        commands.append('cp "{src}" "{out}/index.html"'.format(
            src = ctx.file.index.path,
            out = out.path,
        ))

    if not commands:
        fail("docs_bundle requires at least one section or an index file.")

    ctx.actions.run_shell(
        command = " && ".join(commands),
        inputs = inputs,
        outputs = [out],
        mnemonic = "DocsBundle",
        progress_message = "Assembling documentation site: %s" % out.short_path,
    )

    return [DefaultInfo(files = depset([out]))]

docs_bundle = rule(
    implementation = _docs_bundle_impl,
    doc = "Assemble multiple documentation outputs into a single directory.",
    attrs = {
        "sections": attr.label_keyed_string_dict(
            mandatory = True,
            doc = "Map from documentation targets to subdirectory names.",
        ),
        "index": attr.label(
            allow_single_file = True,
            doc = "Landing page HTML file, copied as index.html.",
        ),
    },
)
