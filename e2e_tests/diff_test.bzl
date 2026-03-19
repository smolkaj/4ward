# Golden file diff testing, adapted from sonic-pins (gutil/diff_test.bzl).
#
# diff_test: compares two files, succeeds if they match.
# cmd_diff_test: runs a command, captures stdout, compares against expected.
#
# To update expected files: bazel run <target> -- --update

def _diff_test_script(ctx, actual_file):
    """Returns bash script to be executed by the diff_test target."""
    return """
if [[ "$1" == "--update" || "$1" == "--test" ]]; then
    cp -f "{actual}" "${{BUILD_WORKSPACE_DIRECTORY}}/{expected}"
fi
diff -u "{expected}" "{actual}"
if [[ $? = 0 ]]; then
    if [[ "$1" == "--update" ]]; then
        echo "Successfully updated: {expected}."
    elif [[ "$1" == "--test" ]]; then
        echo "Successfully updated: {expected}."
        echo ""
        cat {expected}
    else
        echo "PASSED"
    fi
    exit 0
else
    if [[ "$1" == "--update" ]]; then
        echo "Failed to update: {expected}. Try updating manually."
    else
        cat << EOF
Output not as expected. To update $(basename {expected}), run:
  bazel run {target} -- --update
EOF
    fi
    exit 1
fi
    """.format(
        actual = actual_file,
        expected = ctx.file.expected.short_path,
        target = ctx.label,
    )

def _cmd_diff_test_script(ctx):
    """Returns bash script to be executed by the cmd_diff_test target."""
    actual_cmd = ctx.expand_location(
        ctx.attr.actual_cmd,
        targets = ctx.attr.tools + ctx.attr.data,
    ).replace(ctx.var["BINDIR"] + "/", "")
    pre_script = """\
ACTUAL="${{TEST_UNDECLARED_OUTPUTS_DIR}}/actual_cmd.output"
echo "$({actual_cmd})" > "$ACTUAL"
""".format(actual_cmd = actual_cmd)
    return "\n".join([
        pre_script,
        _diff_test_script(ctx, actual_file = '"${ACTUAL}"'),
    ])

def _diff_test_impl(ctx):
    ctx.actions.write(
        output = ctx.outputs.executable,
        content = _diff_test_script(ctx, actual_file = ctx.file.actual.short_path),
        is_executable = True,
    )
    return DefaultInfo(
        runfiles = ctx.runfiles(files = [ctx.file.actual, ctx.file.expected]),
    )

diff_test = rule(
    doc = """Compares two files, succeeding if they match.
    To update the expected file: bazel run <target> -- --update
    """,
    implementation = _diff_test_impl,
    test = True,
    attrs = {
        "actual": attr.label(
            doc = "Actual file (typically generated).",
            mandatory = True,
            allow_single_file = True,
        ),
        "expected": attr.label(
            doc = "Expected file (golden file, checked in).",
            mandatory = True,
            allow_single_file = True,
        ),
    },
)

def _cmd_diff_test_impl(ctx):
    ctx.actions.write(
        output = ctx.outputs.executable,
        content = _cmd_diff_test_script(ctx),
        is_executable = True,
    )
    return DefaultInfo(
        runfiles = ctx.runfiles(ctx.files.tools + ctx.files.data + [ctx.file.expected]),
    )

cmd_diff_test = rule(
    doc = """Runs a command to get "actual" output and diffs it against expected.
    To update the expected file: bazel run <target> -- --update
    """,
    implementation = _cmd_diff_test_impl,
    test = True,
    attrs = {
        "actual_cmd": attr.string(
            doc = "Shell command whose stdout will be diffed against expected.",
            mandatory = True,
        ),
        "expected": attr.label(
            doc = "Expected file (golden file, checked in).",
            mandatory = True,
            allow_single_file = True,
        ),
        "tools": attr.label_list(
            doc = "Executables used in actual_cmd.",
            allow_files = True,
            cfg = "target",
        ),
        "data": attr.label_list(
            doc = "Non-executable files accessed by actual_cmd.",
            allow_files = True,
        ),
    },
)
