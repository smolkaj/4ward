"""Rule for generating HTML documentation from Kotlin source files using Dokka CLI."""

def _dokka_html_impl(ctx):
    out_dir = ctx.actions.declare_directory(ctx.attr.name)

    java_runtime = ctx.toolchains["@bazel_tools//tools/jdk:runtime_toolchain_type"].java_runtime
    java = java_runtime.java_executable_exec_path

    cli_jar = ctx.file._cli
    plugin_jars = [
        ctx.file._base,
        ctx.file._analysis,
        ctx.file._kotlinx_html,
        ctx.file._freemarker,
    ]

    # Collect source files and deduplicate their parent directories.
    src_files = []
    src_dirs = {}
    for src in ctx.attr.srcs:
        for f in src.files.to_list():
            src_files.append(f)
            src_dirs[f.dirname] = True

    # Collect classpath JARs from deps.
    cp_jars = []
    for dep in ctx.attr.deps:
        for f in dep.files.to_list():
            if f.path.endswith(".jar"):
                cp_jars.append(f)

    # Generate Dokka JSON configuration.
    config = ctx.actions.declare_file(ctx.attr.name + "_dokka.json")

    # Build JSON manually (no json module in Starlark).  Values are Bazel paths
    # and identifiers, which never contain quotes or backslashes.
    source_roots = ", ".join(['"{}"'.format(d) for d in sorted(src_dirs.keys())])
    classpath_entries = ", ".join(['"{}"'.format(j.path) for j in cp_jars])
    plugins = ", ".join(['"{}"'.format(j.path) for j in plugin_jars])

    json_content = """\
{{
  "moduleName": "{name}",
  "outputDir": "{out}",
  "sourceSets": [
    {{
      "sourceSetID": {{ "scopeId": "{name}", "sourceSetName": "main" }},
      "sourceRoots": [{source_roots}],
      "classpath": [{classpath}],
      "analysisPlatform": "jvm",
      "skipEmptyPackages": true,
      "reportUndocumented": false
    }}
  ],
  "pluginsClasspath": [{plugins}]
}}
""".format(
        name = ctx.attr.module_name,
        out = out_dir.path,
        source_roots = source_roots,
        classpath = classpath_entries,
        plugins = plugins,
    )

    ctx.actions.write(output = config, content = json_content)

    ctx.actions.run_shell(
        command = '{java} -jar "{cli}" "{config}"'.format(
            java = java,
            cli = cli_jar.path,
            config = config.path,
        ),
        inputs = depset(
            [cli_jar, config] + plugin_jars + src_files + cp_jars,
            transitive = [java_runtime.files],
        ),
        outputs = [out_dir],
        mnemonic = "DokkaHtml",
        progress_message = "Generating Kotlin documentation: %s" % out_dir.short_path,
    )

    return [DefaultInfo(files = depset([out_dir]))]

dokka_html = rule(
    implementation = _dokka_html_impl,
    attrs = {
        "srcs": attr.label_list(mandatory = True, allow_files = [".kt"]),
        "deps": attr.label_list(),
        "module_name": attr.string(default = "4ward"),
        "_cli": attr.label(default = "@dokka_cli//file:dokka-cli.jar", allow_single_file = True),
        "_base": attr.label(default = "@dokka_base//file:dokka-base.jar", allow_single_file = True),
        "_analysis": attr.label(default = "@dokka_analysis//file:analysis-kotlin-descriptors.jar", allow_single_file = True),
        "_kotlinx_html": attr.label(default = "@dokka_kotlinx_html//file:kotlinx-html-jvm.jar", allow_single_file = True),
        "_freemarker": attr.label(default = "@dokka_freemarker//file:freemarker.jar", allow_single_file = True),
    },
    toolchains = ["@bazel_tools//tools/jdk:runtime_toolchain_type"],
)
